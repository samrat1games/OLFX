// Copyright 2026, OLFX contributors
// SPDX-License-Identifier: BSL-1.0
package org.olfx.launcher

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/**
 * Starts/refreshes the OLFX runtime service while the launcher is still foreground.
 * Android 12+ can reject a camera foreground service that first starts after the game has taken
 * over; prewarming avoids a runtime that advertises XR_EXT_hand_tracking but has no camera frames.
 */
internal object HandRuntime {
    private const val SERVICE_CLASS = "org.freedesktop.monado.ipc.MonadoService"
    private const val SERVICE_ACTION = "org.freedesktop.monado.ipc.CONNECT"

    fun startIfEnabled(ctx: Context): Boolean {
        if (!Prefs(ctx).handEnabled) return true
        if (ctx.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return false
        return try {
            val intent = Intent().setComponent(ComponentName(ctx.packageName, SERVICE_CLASS)).setAction(SERVICE_ACTION)
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(intent) != null
            } else {
                ctx.startService(intent) != null
            }
        } catch (e: Exception) {
            Log.w("HandRuntime", "Could not prewarm camera hand tracking before game launch", e)
            false
        }
    }
}
