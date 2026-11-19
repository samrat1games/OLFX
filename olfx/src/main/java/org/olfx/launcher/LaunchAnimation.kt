// Copyright 2026, OLFX contributors
// SPDX-License-Identifier: BSL-1.0
package org.olfx.launcher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.shape.ShapeAppearanceModel
import org.freedesktop.monado.openxr_runtime.R

/** Short, allocation-free-per-frame launch transition. It never runs inside the XR render loop. */
object LaunchAnimation {
    interface Handle { fun cancel() }

    fun show(activity: Activity, entry: AppEntry, onComplete: () -> Unit): Handle {
        val content = activity.findViewById<FrameLayout>(android.R.id.content)
        val overlay = FrameLayout(activity).apply {
            setBackgroundColor(0xF20B0D12.toInt())
            isClickable = true
            isFocusable = true
            contentDescription = activity.getString(R.string.launch_animation_desc, entry.label)
        }
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val art = FrameLayout(activity)
        val rings = (0..2).map { i ->
            View(activity).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(0x00000000)
                    setStroke(maxOf(2, activity.resources.displayMetrics.density.toInt() * 2), if (i == 1) 0xFF5B8CFF.toInt() else 0xFF9B7BFF.toInt())
                }
                alpha = 0f
            }.also { art.addView(it, FrameLayout.LayoutParams(dp(activity, 180 + i * 34), dp(activity, 180 + i * 34), Gravity.CENTER)) }
        }
        val icon = ShapeableImageView(activity).apply {
            setImageDrawable(entry.icon)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            shapeAppearanceModel = ShapeAppearanceModel.builder().setAllCornerSizes(dp(activity, 32).toFloat()).build()
            alpha = 0f
            scaleX = 0.72f
            scaleY = 0.72f
            rotation = -8f
        }
        art.addView(icon, FrameLayout.LayoutParams(dp(activity, 132), dp(activity, 132), Gravity.CENTER))
        column.addView(art, LinearLayout.LayoutParams(dp(activity, 280), dp(activity, 280)))
        val title = TextView(activity).apply {
            text = entry.label
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(C.TEXT)
            alpha = 0f
            translationY = dp(activity, 18).toFloat()
        }
        column.addView(title, LinearLayout.LayoutParams(-1, -2))
        column.addView(TextView(activity).apply {
            text = activity.getString(R.string.launch_preparing, entry.label)
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(C.MUTED)
        }, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(activity, 8) })
        val progress = LinearProgressIndicator(activity).apply {
            isIndeterminate = true
            trackCornerRadius = dp(activity, 3)
        }
        column.addView(progress, LinearLayout.LayoutParams(dp(activity, 180), dp(activity, 4)).also {
            it.gravity = Gravity.CENTER_HORIZONTAL
            it.topMargin = dp(activity, 20)
        })
        overlay.addView(column, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        content.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val animations = mutableListOf<Animator>()
        fun add(a: ObjectAnimator, delay: Long = 0) { a.startDelay = delay; animations += a }
        add(ObjectAnimator.ofFloat(icon, View.ALPHA, 0f, 1f), 0)
        add(ObjectAnimator.ofFloat(icon, View.SCALE_X, 0.72f, 1f).apply { interpolator = OvershootInterpolator(1.4f) }, 40)
        add(ObjectAnimator.ofFloat(icon, View.SCALE_Y, 0.72f, 1f).apply { interpolator = OvershootInterpolator(1.4f) }, 40)
        add(ObjectAnimator.ofFloat(icon, View.ROTATION, -8f, 0f), 40)
        add(ObjectAnimator.ofFloat(title, View.ALPHA, 0f, 1f), 160)
        add(ObjectAnimator.ofFloat(title, View.TRANSLATION_Y, dp(activity, 18).toFloat(), 0f), 160)
        rings.forEachIndexed { i, ring ->
            add(ObjectAnimator.ofFloat(ring, View.ALPHA, 0f, 0.55f, 0f), 100 + i * 90L)
            add(ObjectAnimator.ofFloat(ring, View.SCALE_X, 0.78f, 1.12f), 100 + i * 90L)
            add(ObjectAnimator.ofFloat(ring, View.SCALE_Y, 0.78f, 1.12f), 100 + i * 90L)
        }
        val set = AnimatorSet().apply {
            playTogether(animations)
            duration = 850
        }
        var cancelled = false
        val handle = object : Handle {
            override fun cancel() {
                if (cancelled) return
                cancelled = true
                set.cancel()
                content.removeView(overlay)
            }
        }
        set.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (cancelled) return
                content.removeView(overlay)
                if (!activity.isFinishing && !activity.isDestroyed) onComplete()
            }
        })
        // Hardware layers only for this brief transition; released when the overlay leaves the hierarchy.
        icon.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        rings.forEach { it.setLayerType(View.LAYER_TYPE_HARDWARE, null) }
        set.start()
        return handle
    }

    private fun dp(activity: Activity, value: Int): Int = (value * activity.resources.displayMetrics.density + 0.5f).toInt()
}
