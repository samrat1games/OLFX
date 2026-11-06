package org.olfx.launcher

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.TextView

object C {
    const val BG = 0xFF0B0D12.toInt()
    const val SURFACE = 0xFF161A23.toInt()
    const val ACCENT = 0xFF7C5CFF.toInt()
    const val TEXT = Color.WHITE
    const val MUTED = 0xFF8A93A6.toInt()
    const val XR = 0xFF22C55E.toInt()
    const val QUEST = 0xFF2D7FF9.toInt()
    const val PICO = 0xFFFF7A1A.toInt()
}

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun round(color: Int, radiusPx: Float, stroke: Int? = null, ctx: Context? = null) =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusPx
        if (stroke != null && ctx != null) setStroke(ctx.dp(2), stroke)
    }

fun Context.badge(text: String, color: Int) = TextView(this).apply {
    this.text = text
    textSize = 11f
    typeface = Typeface.DEFAULT_BOLD
    setTextColor(Color.WHITE)
    gravity = Gravity.CENTER
    setPadding(dp(8), dp(2), dp(8), dp(2))
    background = round(color, dp(10).toFloat())
}

fun Context.button(text: String, filled: Boolean, onClick: () -> Unit) = TextView(this).apply {
    this.text = text
    textSize = 17f
    typeface = Typeface.DEFAULT_BOLD
    gravity = Gravity.CENTER
    setTextColor(if (filled) Color.WHITE else C.ACCENT)
    setPadding(dp(16), dp(14), dp(16), dp(14))
    background = if (filled) round(C.ACCENT, dp(14).toFloat()) else round(Color.TRANSPARENT, dp(14).toFloat(), C.ACCENT, this@button)
    setOnClickListener { onClick() }
}

fun View.matchWidthParams(top: Int = 0) =
    android.widget.LinearLayout.LayoutParams(-1, -2).also { it.topMargin = top }

fun Context.badgesFor(e: AppEntry): List<TextView> = buildList {
    if (e.openXr) add(badge("OpenXR", C.XR))
    if (e.quest) add(badge("Meta Quest", C.QUEST))
    if (e.pico) add(badge("Pico", C.PICO))
}
