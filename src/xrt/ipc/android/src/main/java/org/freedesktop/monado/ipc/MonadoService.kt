// Copyright 2020, Collabora, Ltd.
// SPDX-License-Identifier: BSL-1.0
/*!
 * @file
 * @brief  Service implementation for exposing IMonado.
 * @author Rylie Pavlik <rylie.pavlik@collabora.com>
 * @ingroup ipc_android
 */
package org.freedesktop.monado.ipc

import android.Manifest
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.freedesktop.monado.auxiliary.IServiceNotification

/**
 * Implementation of a Service that provides the Monado AIDL interface.
 *
 * This is needed so that the APK can expose the binder service implemented in MonadoImpl.
 */
@AndroidEntryPoint
class MonadoService : Service(), Watchdog.ShutdownListener {
    private lateinit var binder: MonadoImpl

    private lateinit var watchdog: Watchdog

    @Inject lateinit var serviceNotification: IServiceNotification

    override fun onCreate() {
        super.onCreate()
        isRunning = true

        binder = MonadoImpl(this)
        watchdog =
            Watchdog(
                // If the surface comes from client, just stop the service when client disconnected
                // because the surface belongs to the client.
                if (binder.canDrawOverOtherApps()) BuildConfig.WATCHDOG_TIMEOUT_MILLISECONDS else 0,
                this,
            )
        watchdog.startMonitor()

        // start the service so it could be foregrounded
        val intent = Intent(this, javaClass)
        intent.action = BuildConfig.SERVICE_ACTION
        startService(intent)
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
        Log.d(TAG, "onDestroy")

        OlfxHands.stop()
        binder.shutdown()
        watchdog.stopMonitor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        // if this isn't a restart
        if (intent != null) {
            when (intent.action) {
                BuildConfig.SERVICE_ACTION -> handleStart()
                BuildConfig.SHUTDOWN_ACTION -> handleShutdown()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        Log.d(TAG, "onBind")
        watchdog.onClientConnected()
        return binder
    }

    private fun handleStart() {
        var flags = 0
        // From targeting S+, the PendingIntent needs one of FLAG_IMMUTABLE and FLAG_MUTABLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = PendingIntent.FLAG_IMMUTABLE
        }
        val pendingShutdownIntent =
            PendingIntent.getForegroundService(
                this,
                0,
                Intent(BuildConfig.SHUTDOWN_ACTION).setPackage(packageName),
                flags,
            )

        val notification = serviceNotification.buildNotification(this, pendingShutdownIntent)

        // Android 14+ validates foreground-service types against granted permissions. Starting the
        // old MANIFEST mask (connectedDevice|mediaPlayback|camera) without CAMERA permission threw a
        // SecurityException before hand tracking could start. Pick one valid type instead, and never
        // let a foregrounding failure prevent the runtime/hand-tracking start path.
        val hasCamera = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val type =
                    if (hasCamera) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                    else ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                startForeground(serviceNotification.getNotificationId(), notification, type)
            } else {
                startForeground(serviceNotification.getNotificationId(), notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed; continuing as a bound runtime service", e)
        }

        // OLFX: camera hand tracking (asks for permission if needed; no-op when disabled).
        OlfxHands.start(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "onUnbind")
        watchdog.onClientDisconnected()
        return true
    }

    override fun onRebind(intent: Intent?) {
        Log.d(TAG, "onRebind")
        watchdog.onClientConnected()
    }

    override fun onPrepareShutdown() {
        Log.d(TAG, "onPrepareShutdown")
    }

    override fun onShutdown() {
        Log.d(TAG, "onShutdown")
        handleShutdown()
    }

    private fun handleShutdown() {
        stopForeground(true)
        stopSelf()
    }

    companion object {
        private const val TAG = "MonadoService"

        /** Lets the in-app hand test resume runtime tracking when it releases the camera. */
        @Volatile var isRunning = false

        /**
         * Re-run the foreground-service start after CAMERA was granted from a foreground activity.
         * Android 14 validates the camera FGS type against the permission at startForeground() time;
         * a service that first started as connectedDevice must be promoted once the grant arrives.
         */
        @JvmStatic
        fun refreshForeground(context: android.content.Context) {
            if (!isRunning) return
            try {
                context.startService(
                    Intent(context, MonadoService::class.java).setAction(BuildConfig.SERVICE_ACTION)
                )
            } catch (e: Exception) {
                Log.w(TAG, "could not refresh foreground service after camera grant", e)
            }
        }
    }
}
