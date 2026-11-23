package com.ielali.simpletv.guard

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The channel banner drawn over the YouTube app, so number entry and channel changes look the same as
 * on Simple TV's own screen. Uses TYPE_ACCESSIBILITY_OVERLAY, which an accessibility service may add
 * without the "display over other apps" permission. Never focusable or touchable: keys still reach
 * the guard and YouTube.
 */
class BannerOverlay(private val service: AccessibilityService) {

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var root: View? = null
    private lateinit var numberView: TextView
    private lateinit var nameView: TextView

    fun show(number: String, name: String) {
        if (root == null && !attach()) return
        numberView.text = number
        nameView.text = name
        nameView.visibility = if (name.isEmpty()) View.GONE else View.VISIBLE
    }

    fun hide() {
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
    }

    private fun attach(): Boolean {
        val density = service.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        numberView = TextView(service).apply {
            setTextColor(Color.rgb(0xFF, 0xC8, 0x57))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 96f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        nameView = TextView(service).apply {
            setTextColor(Color.rgb(0xF2, 0xF4, 0xF8))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 48f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(dp(32), 0, 0, 0)
        }
        val layout = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(40), dp(24), dp(40), dp(24))
            background = GradientDrawable().apply {
                setColor(Color.argb(0xCC, 0x0B, 0x12, 0x20))
                cornerRadius = dp(16).toFloat()
            }
            addView(numberView)
            addView(nameView)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(48)
            y = dp(48)
        }
        return runCatching {
            windowManager.addView(layout, params)
            root = layout
            Log.i(TAG, "Banner window added over YouTube")
            true
        }.getOrElse {
            Log.w(TAG, "Could not show the banner over YouTube", it)
            false
        }
    }

    private companion object { const val TAG = "BannerOverlay" }
}
