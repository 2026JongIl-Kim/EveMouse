package com.eyemouse.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var btnTrack: Button
    private lateinit var dwellLabel: TextView
    private lateinit var blinkLabel: TextView
    private lateinit var smoothLabel: TextView
    private val handler = Handler(Looper.getMainLooper())

    private val refresh = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 600)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        GazeEngine.loadModel(this)

        val sv = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(28), dp(20), dp(32))
        sv.addView(col)

        col.addv(tv("안구마우스", 28f, true), 0)
        col.addv(tv("전면 카메라로 시선을 읽어 화면에 커서를 띄우고, 응시하거나 눈을 길게 감으면 터치해 줘요.", 14f))

        status = tv("", 15f)
        col.addv(status, 16)

        col.addv(tv("1단계 · 준비", 17f, true), 20)
        col.addv(btn("카메라 권한 허용") { askPermissions() })
        col.addv(btn("접근성 서비스 켜기") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "목록에서 '안구마우스'를 찾아 켜 주세요", Toast.LENGTH_LONG).show()
        })
        col.addv(
            tv(
                "※ 스위치가 회색이라 못 켜면: 아래 '앱 정보 열기' → 오른쪽 위 ⋮ → '제한된 설정 허용' 후 다시 켜세요.",
                12f
            )
        )
        col.addv(btn("앱 정보 열기 (제한된 설정 해제)") {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        })

        col.addv(tv("2단계 · 시선 보정 (처음 1번, 자세가 바뀌면 다시)", 17f, true), 20)
        col.addv(btn("시선 보정 시작") {
            if (!hasCamera()) askPermissions()
            else startActivity(Intent(this, CalibrationActivity::class.java))
        })

        col.addv(tv("3단계 · 추적 시작", 17f, true), 20)
        btnTrack = btn("추적 시작") { toggleTracking() }
        col.addv(btnTrack)

        col.addv(tv("설정", 17f, true), 24)

        val swDwell = Switch(this)
        swDwell.text = "응시 클릭 (한 곳을 보면 터치)"
        swDwell.isChecked = prefs.dwellEnabled
        swDwell.setOnCheckedChangeListener { _, checked -> prefs.dwellEnabled = checked }
        col.addv(swDwell)

        dwellLabel = tv("", 13f)
        col.addv(dwellLabel, 8)
        col.addv(seek(20, (prefs.dwellMs - 600) / 100) { p ->
            prefs.dwellMs = 600 + p * 100
            updateLabels()
        }, 0)

        val swBlink = Switch(this)
        swBlink.text = "눈 길게 감기 클릭"
        swBlink.isChecked = prefs.blinkEnabled
        swBlink.setOnCheckedChangeListener { _, checked -> prefs.blinkEnabled = checked }
        col.addv(swBlink, 16)

        blinkLabel = tv("", 13f)
        col.addv(blinkLabel, 8)
        col.addv(seek(14, (prefs.blinkMs - 300) / 50) { p ->
            prefs.blinkMs = 300 + p * 50
            updateLabels()
        }, 0)

        smoothLabel = tv("", 13f)
        col.addv(smoothLabel, 16)
        col.addv(seek(10, (prefs.smoothing * 10f).toInt()) { p ->
            prefs.smoothing = p / 10f
            updateLabels()
        }, 0)

        col.addv(tv("사용법", 17f, true), 24)
        col.addv(
            tv(
                "• 커서가 가는 곳을 1초쯤 응시하면 노란 링이 차오르며 터치돼요.\n" +
                    "• 눈을 0.5초 이상 길게 감았다 뜨면 감기 직전 위치를 터치해요. (자연스러운 깜빡임은 무시)\n" +
                    "• 화면 모서리에 커서를 두고 잠시 응시하면:\n" +
                    "   왼쪽 위 = 정지/재개, 오른쪽 위 = 최근 앱,\n" +
                    "   왼쪽 아래 = 뒤로, 오른쪽 아래 = 홈,\n" +
                    "   위/아래 가운데 가장자리 = 스크롤\n" +
                    "• 폰을 거치대에 세워 두고 얼굴이 카메라 정면에 오게 하면 가장 정확해요.\n" +
                    "• 영상은 기기 안에서만 처리되고 저장·전송되지 않아요.",
                13f
            ),
            8
        )

        setContentView(sv)
        updateLabels()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        handler.postDelayed(refresh, 600)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresh)
    }

    private fun updateLabels() {
        dwellLabel.text = "응시 시간: ${"%.1f".format(prefs.dwellMs / 1000f)}초"
        blinkLabel.text = "눈 감는 시간: ${"%.2f".format(prefs.blinkMs / 1000f)}초 이상"
        smoothLabel.text = "커서 부드러움: ${(prefs.smoothing * 10f).toInt()} / 10 (높을수록 느리지만 안정적)"
    }

    private fun updateStatus() {
        val cam = hasCamera()
        val acc = EyeAccessibilityService.instance != null
        val cal = GazeEngine.model != null
        val run = EyeTrackingService.running
        val trackText = if (run) {
            if (GazeEngine.faceFound) "켜짐 (얼굴 인식됨)" else "켜짐 (얼굴 찾는 중)"
        } else {
            "꺼짐"
        }
        status.text = "카메라 권한: ${if (cam) "✔ 허용됨" else "✘ 필요"}\n" +
            "접근성 서비스: ${if (acc) "✔ 켜짐" else "✘ 꺼짐"}\n" +
            "시선 보정: ${if (cal) "✔ 완료" else "✘ 필요"}\n" +
            "추적: $trackText"
        btnTrack.text = if (run) "추적 중지" else "추적 시작"
    }

    private fun hasCamera(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun askPermissions() {
        val list = ArrayList<String>()
        list.add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(list.toTypedArray(), 100)
    }

    private fun toggleTracking() {
        if (EyeTrackingService.running) {
            stopService(Intent(this, EyeTrackingService::class.java))
            return
        }
        if (!hasCamera()) {
            askPermissions()
            return
        }
        if (GazeEngine.model == null) {
            Toast.makeText(this, "먼저 '시선 보정'을 해 주세요", Toast.LENGTH_LONG).show()
            return
        }
        startForegroundService(Intent(this, EyeTrackingService::class.java))
        if (EyeAccessibilityService.instance == null) {
            Toast.makeText(this, "접근성 서비스도 켜야 커서가 보여요", Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------ UI 도우미

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun tv(s: String, size: Float, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = size
        if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
        return t
    }

    private fun btn(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.setOnClickListener { onClick() }
        return b
    }

    private fun seek(maxValue: Int, value: Int, onChange: (Int) -> Unit): SeekBar {
        val s = SeekBar(this)
        s.max = maxValue
        s.progress = value.coerceIn(0, maxValue)
        s.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) onChange(progress)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        return s
    }

    private fun LinearLayout.addv(v: View, topDp: Int = 10) {
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(topDp)
        addView(v, lp)
    }
}
