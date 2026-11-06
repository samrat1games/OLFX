package org.olfx.launcher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast
import java.io.File

/** Installs a patched APK through the system PackageInstaller (the user confirms the system dialog). */
object Installer {
    const val ACTION = "org.olfx.launcher.INSTALL_RESULT"

    fun install(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            apk.inputStream().use { input ->
                s.openWrite("base.apk", 0, apk.length()).use { out ->
                    input.copyTo(out, 1 shl 16)
                    s.fsync(out)
                }
            }
            val intent = Intent(ACTION).setPackage(ctx.packageName)
            val pending = PendingIntent.getBroadcast(ctx, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            s.commit(pending.intentSender)
        }
    }
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        when (val status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (confirm != null) ctx.startActivity(confirm)
            }
            PackageInstaller.STATUS_SUCCESS ->
                Toast.makeText(ctx, "Патч установлен", Toast.LENGTH_LONG).show()
            else ->
                Toast.makeText(ctx, "Установка не удалась ($status): ${i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}", Toast.LENGTH_LONG).show()
        }
    }
}
