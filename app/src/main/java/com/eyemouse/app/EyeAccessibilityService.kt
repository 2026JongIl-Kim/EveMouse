package com.eyemouse.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlin.math.hypot

/**
 * 화면 위에 시선 커서를 띄우고, 응시(체류)/눈 길게 감기로 화면을 대신 터치하는 접근성 서비스.
 */
class EyeAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: EyeAccessibilityService? = null
        private const val HN = 32
        private const val CLOSE_TH = 0.6f
    }

    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var view: CursorView? = null
    private lateinit var prefs: Prefs
    private var density = 3f
    private val tmpRect = RectF()
    private val tmpPos = FloatArray(2)

    private var paused = false

    // 응시 클릭 상태
    private var ax = 0f
    private var ay = 0f
    private var anchorValid = false
    private var dwellStart = 0L
    private var locked = false
    private var lockX = 0f
    private var lockY = 0f

    // 가장자리 영역 상태
    private var curZone = Zones.NONE
    private var zoneStart = 0L
    private var zoneLocked = false

    // 눈 감기 상태
    private var wasClosed = false
    private var closeStart = 0L
    private var bx = 0f
    private var by = 0f

    private var lastFaceMs = 0L
    private var flashUntil = 0L

    // 최근 커서 위치 기록(눈 감기 직전 위치를 복원하기 위함)
    private val histT = LongArray(HN)
    private val histX = FloatArray(HN)
    private val histY = FloatArray(HN)
    private var histPos = 0
    private var histN = 0

    private val ticker = object : Runnable {
        override fun run() {
            GazeEngine.smoothing = prefs.smoothing
            onGaze()
            main.postDelayed(this, 150)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        prefs = Prefs(this)
        density = resources.displayMetrics.density
        if (GazeEngine.model == null) GazeEngine.loadModel(applicationContext)
        addOverlay()
        GazeEngine.listener = { main.post { onGaze() } }
        main.post(ticker)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        main.removeCallbacksAndMessages(null)
        GazeEngine.listener = null
        try {
            view?.let { wm?.removeView(it) }
        } catch (e: Exception) {
            // 이미 제거됨
        }
        view = null
        if (instance === this) instance = null
    }

    private fun addOverlay() {
        val manager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = manager
        val v = CursorView(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        manager.addView(v, lp)
        view = v
    }

    // ------------------------------------------------------------------ 메인 로직

    private fun onGaze() {
        val v = view ?: return
        val w = v.width.toFloat()
        val h = v.height.toFloat()
        if (w <= 0f || h <= 0f) return
        val now = SystemClock.uptimeMillis()

        val active = EyeTrackingService.running && GazeEngine.model != null && !GazeEngine.calibrating
        v.show = active
        if (!active) {
            resetClickStates()
            v.invalidate()
            return
        }

        val px = GazeEngine.cursorX * w
        val py = GazeEngine.cursorY * h
        v.cx = px
        v.cy = py
        v.paused = paused
        v.dim = !GazeEngine.faceFound
        v.flash = if (now < flashUntil) (flashUntil - now) / 250f else 0f

        if (!GazeEngine.faceFound) {
            if (now - lastFaceMs > 700) {
                resetClickStates()
                v.dwell = 0f
                v.zone = Zones.NONE
                v.zoneProg = 0f
            }
            v.invalidate()
            return
        }
        lastFaceMs = now
        recordHist(now, px, py)

        val prefsBlinkMs = prefs.blinkMs

        // ---- 눈 감기 처리
        val closed = GazeEngine.blink > CLOSE_TH
        if (closed) {
            if (!wasClosed) {
                wasClosed = true
                closeStart = now
                posAt(now - 220, tmpPos)
                bx = tmpPos[0]
                by = tmpPos[1]
            }
            v.cx = bx
            v.cy = by
            v.dwell = if (prefs.blinkEnabled && !paused) {
                ((now - closeStart).toFloat() / prefsBlinkMs).coerceIn(0f, 1f)
            } else {
                0f
            }
            anchorValid = false
            v.invalidate()
            return
        } else if (wasClosed) {
            wasClosed = false
            val dur = now - closeStart
            if (prefs.blinkEnabled && !paused && dur >= prefsBlinkMs && dur <= 2500) {
                if (Zones.at(bx, by, w, h, tmpRect) == Zones.NONE) {
                    tap(bx, by)
                    flashUntil = now + 250
                }
            }
            locked = true
            lockX = bx
            lockY = by
            v.dwell = 0f
        }

        // ---- 가장자리 영역 처리
        val zone = Zones.at(px, py, w, h, tmpRect)
        val effZone = if (paused && zone != Zones.PAUSE) Zones.NONE else zone
        if (effZone != Zones.NONE) {
            if (effZone != curZone) {
                curZone = effZone
                zoneStart = now
                zoneLocked = false
            }
            val isScroll = effZone == Zones.SCROLL_UP || effZone == Zones.SCROLL_DOWN
            val need = if (isScroll) 700L else 1300L
            val prog = if (zoneLocked) 0f else ((now - zoneStart).toFloat() / need).coerceIn(0f, 1f)
            v.zone = effZone
            v.zoneProg = prog
            v.dwell = 0f
            anchorValid = false
            if (!zoneLocked && now - zoneStart >= need) {
                fireZone(effZone, w, h)
                flashUntil = now + 250
                if (isScroll) {
                    zoneStart = now + 500
                } else {
                    zoneLocked = true
                }
            }
        } else {
            curZone = Zones.NONE
            v.zone = Zones.NONE
            v.zoneProg = 0f
            handleDwell(v, px, py, w, now)
        }
        v.invalidate()
    }

    private fun handleDwell(v: CursorView, px: Float, py: Float, w: Float, now: Long) {
        if (!prefs.dwellEnabled || paused) {
            v.dwell = 0f
            anchorValid = false
            return
        }
        val radius = maxOf(w * 0.06f, 40f * density)
        if (locked) {
            v.dwell = 0f
            if (hypot(px - lockX, py - lockY) > radius * 1.6f) locked = false
            return
        }
        if (!anchorValid || hypot(px - ax, py - ay) > radius) {
            ax = px
            ay = py
            anchorValid = true
            dwellStart = now
        } else {
            ax += (px - ax) * 0.15f
            ay += (py - ay) * 0.15f
        }
        val prog = ((now - dwellStart).toFloat() / prefs.dwellMs).coerceIn(0f, 1f)
        v.dwell = prog
        if (prog >= 1f) {
            tap(ax, ay)
            locked = true
            lockX = ax
            lockY = ay
            anchorValid = false
            v.dwell = 0f
            flashUntil = now + 250
        }
    }

    private fun fireZone(z: Int, w: Float, h: Float) {
        when (z) {
            Zones.PAUSE -> {
                paused = !paused
                anchorValid = false
                locked = false
            }
            Zones.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            Zones.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            Zones.RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            Zones.SCROLL_UP -> swipe(w * 0.5f, h * 0.35f, w * 0.5f, h * 0.75f)
            Zones.SCROLL_DOWN -> swipe(w * 0.5f, h * 0.75f, w * 0.5f, h * 0.35f)
        }
    }

    private fun resetClickStates() {
        anchorValid = false
        locked = false
        curZone = Zones.NONE
        zoneLocked = false
        wasClosed = false
    }

    // ------------------------------------------------------------------ 제스처

    private fun tap(x: Float, y: Float) {
        val p = Path()
        p.moveTo(x, y)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0L, 50L))
            .build()
        dispatchGesture(g, null, null)
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        val p = Path()
        p.moveTo(x1, y1)
        p.lineTo(x2, y2)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0L, 350L))
            .build()
        dispatchGesture(g, null, null)
    }

    // ------------------------------------------------------------------ 위치 기록

    private fun recordHist(t: Long, x: Float, y: Float) {
        histT[histPos] = t
        histX[histPos] = x
        histY[histPos] = y
        histPos = (histPos + 1) % HN
        if (histN < HN) histN++
    }

    private fun posAt(t: Long, out: FloatArray) {
        if (histN == 0) return
        var i = (histPos - 1 + HN) % HN
        for (k in 0 until histN) {
            if (histT[i] <= t) {
                out[0] = histX[i]
                out[1] = histY[i]
                return
            }
            i = (i - 1 + HN) % HN
        }
        val o = (histPos - histN + HN) % HN
        out[0] = histX[o]
        out[1] = histY[o]
    }
}
