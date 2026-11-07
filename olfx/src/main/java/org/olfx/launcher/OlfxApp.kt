package org.olfx.launcher

import android.app.Application
import com.google.android.material.color.DynamicColors

class OlfxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Material You: take the colours from the wallpaper where the system supports it.
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
