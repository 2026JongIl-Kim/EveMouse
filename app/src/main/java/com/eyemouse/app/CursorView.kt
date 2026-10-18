package com.eyemouse.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** 화면 가장자리 동작 영역 정의 */
object Zones {
    const val NONE = 0
    const val PAUSE = 1
    const val RECENTS = 2
    const val BACK = 3
    const val HOME = 4
    const val SCROLL_UP = 5
    const val SCROLL_DOWN = 6

    val labels = arrayOf("", "정지/재개", "최근앱", "뒤로", "홈", "▲ 위로", "▼ 아래로")

    fun rect(zone: Int, w: Float, h: Float, out: RectF) {
        val cw = w * 0.2f
        val ch = h * 0.085f
        val ez = h * 0.06f
        when (zone) {
            PAUSE -> out.set(0f, 0f, cw, ch)
            RECENTS -> out.set(w - cw, 0f, w, ch)
            BACK -> out.set(0f, h - ch, cw, h)
            HOME -> out.set(w - cw, h - ch, w, h)
            SCROLL_UP -> out.set(w * 0.3f, 0f, w * 0.7f, ez)
            SCROLL_DOWN -> out.set(w * 0.3f, h - ez, w * 0.7f, h)
            else -> out.set(0f, 0f, 0f, 0f)
        }
    }

    fun at(px: Float, py: Float, w: Float, h: Float, tmp: RectF): Int {
        for (z in 1..6) {
            rect(z, w, h, tmp)
            if (px >= tmp.left && px <= tmp.right && py >= tmp.top && py <= tmp.bottom) return z
        }
        return NONE
    }
}

/** 시선 커서와 가장자리 버튼을 그리는 오버레이 뷰 (터치는 모두 통과) */
class CursorView(context: Context) : View(context) {
    var cx = 0f
    var cy = 0f
    var show = false
    var dim = false
    var paused = false
    var dwell = 0f
    var zone = Zones.NONE
    var zoneProg = 0f
    var flash = 0f

    private val d = context.resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * d
        color = Color.WHITE
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f * d
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#FFEB3B")
    }
    private val zonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 13f * d
    }
    private val rect = RectF()
    private val oval = RectF()
    private val allZones = intArrayOf(1, 2, 3, 4, 5, 6)
    private val pauseOnly = intArrayOf(Zones.PAUSE)

    override fun onDraw(canvas: Canvas) {
        if (!show) return
        val w = width.toFloat()
        val h = height.toFloat()

        val zs = if (paused) pauseOnly else allZones
        for (z in zs) {
            Zones.rect(z, w, h, rect)
            val active = z == zone
            zonePaint.color = if (active) Color.argb(110, 255, 235, 59) else Color.argb(38, 255, 255, 255)
            canvas.drawRoundRect(rect, 12f * d, 12f * d, zonePaint)
            if (active && zoneProg > 0f) {
                zonePaint.color = Color.argb(150, 76, 175, 80)
                canvas.drawRoundRect(
                    rect.left, rect.top,
                    rect.left + rect.width() * zoneProg.coerceIn(0f, 1f), rect.bottom,
                    12f * d, 12f * d, zonePaint
                )
            }
            val label = if (z == Zones.PAUSE && paused) "재개" else Zones.labels[z]
            canvas.drawText(label, rect.centerX(), rect.centerY() + textPaint.textSize / 3f, textPaint)
        }

        val r = 16f * d
        fill.color = when {
            paused -> Color.argb(170, 158, 158, 158)
            dim -> Color.argb(110, 255, 82, 82)
            else -> Color.argb(150, 0, 229, 255)
        }
        canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy, r, outline)

        if (dwell > 0f) {
            oval.set(cx - r * 1.6f, cy - r * 1.6f, cx + r * 1.6f, cy + r * 1.6f)
            canvas.drawArc(oval, -90f, 360f * dwell.coerceIn(0f, 1f), false, ring)
        }
        if (flash > 0f) {
            fill.color = Color.argb((120 * flash).toInt(), 255, 255, 255)
            canvas.drawCircle(cx, cy, r * 2.2f, fill)
        }
    }
}
