package com.eyemouse.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import kotlin.math.sin

/** 화면의 9개 점을 차례로 바라보게 해서 시선 → 화면 좌표 변환을 학습한다. */
class CalibrationActivity : Activity() {

    private lateinit var calView: CalView
    private val handler = Handler(Looper.getMainLooper())

    private val points = arrayOf(
        floatArrayOf(0.08f, 0.07f), floatArrayOf(0.5f, 0.07f), floatArrayOf(0.92f, 0.07f),
        floatArrayOf(0.92f, 0.5f), floatArrayOf(0.5f, 0.5f), floatArrayOf(0.08f, 0.5f),
        floatArrayOf(0.08f, 0.93f), floatArrayOf(0.5f, 0.93f), floatArrayOf(0.92f, 0.93f)
    )
    private var idx = 0
    private var retries = 0
    private var waitCount = 0
    private var okCount = 0
    private var done = false

    private val waitFace = object : Runnable {
        override fun run() {
            if (done) return
            if (EyeTrackingService.running && GazeEngine.faceFound) okCount++ else okCount = 0
            waitCount++
            if (okCount >= 4) {
                calView.setMessage("좋아요! 곧 시작해요.\n머리는 가만히 두고, 눈만 움직여\n나타나는 점을 바라보세요", false)
                handler.postDelayed({ startPoint() }, 2800)
                return
            }
            if (waitCount > 40) {
                fail("얼굴을 찾지 못했어요. 밝은 곳에서 얼굴이 카메라 정면에 오게 하고 다시 시도해 주세요.")
                return
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        hideSystemUi()

        calView = CalView(this)
        setContentView(calView)

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "카메라 권한을 먼저 허용해 주세요.", Toast.LENGTH_LONG).show()
            done = true
            finish()
            return
        }

        GazeEngine.calibrating = true
        GazeEngine.clearSamples()
        if (!EyeTrackingService.running) {
            startForegroundService(Intent(this, EyeTrackingService::class.java))
        }
        calView.setMessage("카메라를 준비하고 있어요…\n폰을 세워서 얼굴이 정면에 오게 해 주세요", false)
        handler.postDelayed(waitFace, 500)
    }

    override fun onDestroy() {
        done = true
        handler.removeCallbacksAndMessages(null)
        GazeEngine.endCollect()
        GazeEngine.calibrating = false
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    private fun startPoint() {
        if (done) return
        if (idx >= points.size) {
            finishCalibration()
            return
        }
        val p = points[idx]
        calView.setTarget(p[0], p[1], "점을 바라보세요  ${idx + 1} / ${points.size}")
        handler.postDelayed({
            if (done) return@postDelayed
            GazeEngine.beginCollect(p[0], p[1])
            handler.postDelayed({ checkCollected() }, 1500)
        }, 1000)
    }

    private fun checkCollected() {
        if (done) return
        val n = GazeEngine.endCollect()
        if (n >= 10) {
            idx++
            retries = 0
        } else {
            retries++
            if (retries >= 4) {
                fail("얼굴이 계속 인식되지 않았어요. 다시 시도해 주세요.")
                return
            }
        }
        startPoint()
    }

    private fun finishCalibration() {
        val err = GazeEngine.fit()
        if (err == null) {
            fail("보정에 실패했어요. 밝은 곳에서 다시 시도해 주세요.")
            return
        }
        GazeEngine.saveModel(this)
        done = true
        Toast.makeText(
            this,
            "보정 완료! (평균 오차 약 ${"%.1f".format(err * 100f)}%)",
            Toast.LENGTH_LONG
        ).show()
        finish()
    }

    private fun fail(msg: String) {
        done = true
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
    }
}

class CalView(context: Context) : View(context) {
    private var tx = 0.5f
    private var ty = 0.5f
    private var showDot = false
    private var msg = ""

    private val d = context.resources.displayMetrics.density
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFEB3B")
        style = Paint.Style.FILL
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f * d
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 17f * d
        textAlign = Paint.Align.CENTER
    }

    init {
        setBackgroundColor(Color.BLACK)
    }

    fun setTarget(x: Float, y: Float, message: String) {
        tx = x
        ty = y
        msg = message
        showDot = true
        invalidate()
    }

    fun setMessage(message: String, dot: Boolean) {
        msg = message
        showDot = dot
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        val lines = msg.split("\n")
        var y = if (showDot) h * 0.30f else h * 0.42f
        for (line in lines) {
            canvas.drawText(line, w / 2f, y, textPaint)
            y += textPaint.textSize * 1.5f
        }

        if (showDot) {
            val t = SystemClock.uptimeMillis() / 1000f
            val r = (11f + 4f * sin(t * 6f)) * d
            val cx = tx * w
            val cy = ty * h
            canvas.drawCircle(cx, cy, r, dotPaint)
            canvas.drawCircle(cx, cy, r * 2.2f, haloPaint)
            postInvalidateOnAnimation()
        }
    }
}
