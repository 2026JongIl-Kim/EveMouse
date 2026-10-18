package com.eyemouse.app

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences("eyemouse", Context.MODE_PRIVATE)

    var dwellEnabled: Boolean
        get() = sp.getBoolean("dwell_enabled", true)
        set(v) {
            sp.edit().putBoolean("dwell_enabled", v).apply()
        }

    /** 응시 클릭까지 걸리는 시간(ms) */
    var dwellMs: Int
        get() = sp.getInt("dwell_ms", 1200)
        set(v) {
            sp.edit().putInt("dwell_ms", v).apply()
        }

    var blinkEnabled: Boolean
        get() = sp.getBoolean("blink_enabled", true)
        set(v) {
            sp.edit().putBoolean("blink_enabled", v).apply()
        }

    /** 눈을 감고 있어야 클릭되는 시간(ms) */
    var blinkMs: Int
        get() = sp.getInt("blink_ms", 500)
        set(v) {
            sp.edit().putInt("blink_ms", v).apply()
        }

    /** 0(빠름) ~ 1(아주 부드러움) */
    var smoothing: Float
        get() = sp.getFloat("smoothing", 0.6f)
        set(v) {
            sp.edit().putFloat("smoothing", v).apply()
        }

    var model: String?
        get() = sp.getString("model", null)
        set(v) {
            sp.edit().putString("model", v).apply()
        }
}
