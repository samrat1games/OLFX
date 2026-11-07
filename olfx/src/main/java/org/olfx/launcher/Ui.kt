package org.olfx.launcher

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.TextView

object C {
    // Filled from the Material 3 theme (dynamic colour when available) by init(); these are the fallbacks.
    var BG = 0xFF141218.toInt()
    var SURFACE = 0xFF211F26.toInt()          // surface container
    var CONTAINER_HIGH = 0xFF2B2930.toInt()   // surface container high
    var ACCENT = 0xFFD0BCFF.toInt()           // primary
    var ON_ACCENT = 0xFF381E72.toInt()
    var TEXT = 0xFFE6E0E9.toInt()             // on surface
    var MUTED = 0xFFCAC4D0.toInt()            // on surface variant
    var SEC_CONTAINER = 0xFF4A4458.toInt()
    var ON_SEC_CONTAINER = 0xFFE8DEF8.toInt()
    var TER_CONTAINER = 0xFF633B48.toInt()
    var ON_TER_CONTAINER = 0xFFFFD8E4.toInt()
    const val XR = 0xFF22C55E.toInt()
    const val QUEST = 0xFF2D7FF9.toInt()
    const val PICO = 0xFFFF7A1A.toInt()

    fun init(ctx: Context) {
        fun c(attr: Int, fallback: Int) = com.google.android.material.color.MaterialColors.getColor(ctx, attr, fallback)
        BG = c(com.google.android.material.R.attr.colorSurface, BG)
        SURFACE = c(com.google.android.material.R.attr.colorSurfaceContainer, SURFACE)
        CONTAINER_HIGH = c(com.google.android.material.R.attr.colorSurfaceContainerHigh, CONTAINER_HIGH)
        ACCENT = c(androidx.appcompat.R.attr.colorPrimary, ACCENT)
        ON_ACCENT = c(com.google.android.material.R.attr.colorOnPrimary, ON_ACCENT)
        TEXT = c(com.google.android.material.R.attr.colorOnSurface, TEXT)
        MUTED = c(com.google.android.material.R.attr.colorOnSurfaceVariant, MUTED)
        SEC_CONTAINER = c(com.google.android.material.R.attr.colorSecondaryContainer, SEC_CONTAINER)
        ON_SEC_CONTAINER = c(com.google.android.material.R.attr.colorOnSecondaryContainer, ON_SEC_CONTAINER)
        TER_CONTAINER = c(com.google.android.material.R.attr.colorTertiaryContainer, TER_CONTAINER)
        ON_TER_CONTAINER = c(com.google.android.material.R.attr.colorOnTertiaryContainer, ON_TER_CONTAINER)
    }
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
    setPadding(dp(10), dp(4), dp(10), dp(4)); minHeight = dp(24)
    background = round(color, dp(10).toFloat())
}

fun Context.button(text: String, filled: Boolean, onClick: () -> Unit) = TextView(this).apply {
    this.text = text
    textSize = 17f
    typeface = Typeface.DEFAULT_BOLD
    gravity = Gravity.CENTER
    setTextColor(if (filled) C.ON_ACCENT else C.ACCENT)
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
