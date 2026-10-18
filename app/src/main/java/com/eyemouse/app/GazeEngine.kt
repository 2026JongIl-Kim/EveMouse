package com.eyemouse.app

import android.content.Context
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * 카메라 특징값 -> 화면 좌표(0~1) 변환 엔진.
 *
 * 특징값(8개): [홍채 가로비, 홍채 세로비, 블렌드셰이프 가로, 블렌드셰이프 세로,
 *              머리 좌우(yaw), 머리 상하(pitch), 코 x, 코 y]
 * 9점 보정 데이터로 릿지 회귀(선형)를 학습해 화면 좌표를 예측한다.
 */
object GazeEngine {
    const val NF = 8
    private const val NP = NF + 1
    private const val FREEZE_BLINK = 0.5f

    class Model(
        val mean: FloatArray,
        val std: FloatArray,
        val wx: FloatArray,
        val wy: FloatArray
    )

    @Volatile var model: Model? = null
    @Volatile var faceFound = false
    @Volatile var blink = 0f
    @Volatile var cursorX = 0.5f
    @Volatile var cursorY = 0.5f
    @Volatile var smoothing = 0.6f
    @Volatile var calibrating = false
    @Volatile var listener: (() -> Unit)? = null

    private val lock = Any()
    private var collecting = false
    private var targetX = 0f
    private var targetY = 0f
    private var pointCount = 0
    private val sampleF = ArrayList<FloatArray>()
    private val sampleT = ArrayList<FloatArray>()

    private var sx = 0.5f
    private var sy = 0.5f
    private var smoothInit = false
    private val tmp = FloatArray(2)

    /** 카메라 스레드에서 프레임마다 호출. f == null 이면 얼굴 없음. */
    fun onFrame(f: FloatArray?, blinkValue: Float) {
        if (f == null) {
            faceFound = false
            listener?.invoke()
            return
        }
        faceFound = true
        blink = blinkValue

        var isCollecting = false
        synchronized(lock) {
            if (collecting) {
                isCollecting = true
                if (blinkValue < 0.3f) {
                    sampleF.add(f.copyOf())
                    sampleT.add(floatArrayOf(targetX, targetY))
                    pointCount++
                }
            }
        }

        if (!isCollecting) {
            val m = model
            // 눈을 감는 동안에는 커서를 고정한다(홍채 위치가 튀기 때문)
            if (m != null && blinkValue < FREEZE_BLINK) {
                predict(m, f, tmp)
                updateSmooth(tmp[0], tmp[1])
            }
        }
        listener?.invoke()
    }

    fun onStopped() {
        faceFound = false
        smoothInit = false
        listener?.invoke()
    }

    private fun updateSmooth(nx: Float, ny: Float) {
        val tx = nx.coerceIn(0f, 1f)
        val ty = ny.coerceIn(0f, 1f)
        if (!smoothInit) {
            sx = tx
            sy = ty
            smoothInit = true
        } else {
            val d = hypot(tx - sx, ty - sy)
            val base = 0.30f - 0.27f * smoothing.coerceIn(0f, 1f)
            val a = (base + d * 3f).coerceAtMost(0.85f)
            sx += (tx - sx) * a
            sy += (ty - sy) * a
        }
        cursorX = sx
        cursorY = sy
    }

    private fun predict(m: Model, f: FloatArray, out: FloatArray) {
        var x = m.wx[0]
        var y = m.wy[0]
        for (i in 0 until NF) {
            val z = (f[i] - m.mean[i]) / m.std[i]
            x += m.wx[i + 1] * z
            y += m.wy[i + 1] * z
        }
        out[0] = x
        out[1] = y
    }

    // ---------------------------------------------------------------- 보정

    fun clearSamples() {
        synchronized(lock) {
            sampleF.clear()
            sampleT.clear()
            pointCount = 0
            collecting = false
        }
    }

    fun beginCollect(x: Float, y: Float) {
        synchronized(lock) {
            targetX = x
            targetY = y
            pointCount = 0
            collecting = true
        }
    }

    /** 수집을 끝내고, 이번 점에서 모은 샘플 수를 돌려준다. */
    fun endCollect(): Int {
        synchronized(lock) {
            collecting = false
            return pointCount
        }
    }

    /** @return 학습 데이터 기준 RMS 오차(화면 비율). 실패하면 null */
    fun fit(): Float? {
        val (fs, ts) = synchronized(lock) {
            Pair(ArrayList(sampleF), ArrayList(sampleT))
        }
        val n = fs.size
        if (n < 60) return null

        val mean = FloatArray(NF)
        val std = FloatArray(NF)
        for (i in 0 until NF) {
            var s = 0.0
            for (r in fs) s += r[i]
            val mu = s / n
            var v = 0.0
            for (r in fs) {
                val d = r[i] - mu
                v += d * d
            }
            mean[i] = mu.toFloat()
            val sd = sqrt(v / n).toFloat()
            std[i] = if (sd < 1e-4f) 1f else sd
        }

        val ata = Array(NP) { DoubleArray(NP) }
        val atx = DoubleArray(NP)
        val aty = DoubleArray(NP)
        val row = DoubleArray(NP)
        for (k in 0 until n) {
            row[0] = 1.0
            for (i in 0 until NF) {
                row[i + 1] = ((fs[k][i] - mean[i]) / std[i]).toDouble()
            }
            val tx = ts[k][0].toDouble()
            val ty = ts[k][1].toDouble()
            for (a in 0 until NP) {
                for (b in 0 until NP) {
                    ata[a][b] += row[a] * row[b]
                }
                atx[a] += row[a] * tx
                aty[a] += row[a] * ty
            }
        }
        val lambda = 0.02 * n
        for (i in 1 until NP) ata[i][i] += lambda

        val wx = solve(Array(NP) { ata[it].copyOf() }, atx.copyOf()) ?: return null
        val wy = solve(Array(NP) { ata[it].copyOf() }, aty.copyOf()) ?: return null

        val m = Model(
            mean,
            std,
            FloatArray(NP) { wx[it].toFloat() },
            FloatArray(NP) { wy[it].toFloat() }
        )

        val o = FloatArray(2)
        var se = 0.0
        for (k in 0 until n) {
            predict(m, fs[k], o)
            val dx = (o[0] - ts[k][0]).toDouble()
            val dy = (o[1] - ts[k][1]).toDouble()
            se += dx * dx + dy * dy
        }
        model = m
        smoothInit = false
        return sqrt(se / n).toFloat()
    }

    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        for (i in 0 until n) {
            var piv = i
            for (r in i + 1 until n) {
                if (abs(a[r][i]) > abs(a[piv][i])) piv = r
            }
            if (abs(a[piv][i]) < 1e-12) return null
            if (piv != i) {
                val t = a[i]
                a[i] = a[piv]
                a[piv] = t
                val tb = b[i]
                b[i] = b[piv]
                b[piv] = tb
            }
            for (r in i + 1 until n) {
                val f = a[r][i] / a[i][i]
                for (c in i until n) a[r][c] -= f * a[i][c]
                b[r] -= f * b[i]
            }
        }
        val x = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            var s = b[i]
            for (c in i + 1 until n) s -= a[i][c] * x[c]
            x[i] = s / a[i][i]
        }
        return x
    }

    // ---------------------------------------------------------------- 저장/불러오기

    fun saveModel(ctx: Context) {
        val m = model ?: return
        val parts = listOf(
            m.mean.joinToString(","),
            m.std.joinToString(","),
            m.wx.joinToString(","),
            m.wy.joinToString(",")
        )
        Prefs(ctx).model = parts.joinToString(";")
    }

    fun loadModel(ctx: Context) {
        val s = Prefs(ctx).model ?: return
        try {
            val parts = s.split(";")
            if (parts.size != 4) return
            val arrays = parts.map { part ->
                part.split(",").map { it.toFloat() }.toFloatArray()
            }
            if (arrays[0].size != NF || arrays[1].size != NF) return
            if (arrays[2].size != NP || arrays[3].size != NP) return
            model = Model(arrays[0], arrays[1], arrays[2], arrays[3])
            smoothInit = false
        } catch (e: Exception) {
            // 저장된 값이 손상되었으면 무시하고 다시 보정하도록 둔다
        }
    }
}
