package org.olfx.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

enum class Kind(val title: String) { MONADO("Monado"), OCULUS("OculusXR (Meta Quest)"), PICO("Pico"), OTHER("Other") }

data class RuntimeInfo(val pkg: String, val label: String, val so: String, val basePath: String, val kind: Kind)

@Suppress("DEPRECATION")
object Runtimes {
    private const val ACTION = "org.khronos.openxr.OpenXRRuntimeService"
    private const val META_SO = "org.khronos.openxr.OpenXRRuntime.SoFilename"
    private const val META_MAJOR = "org.khronos.openxr.OpenXRRuntime.MajorVersion"

    /** Every OpenXR runtime installed on the device (anything exposing the OpenXR runtime service). */
    fun list(ctx: Context): List<RuntimeInfo> {
        val pm = ctx.packageManager
        return pm.queryIntentServices(Intent(ACTION), PackageManager.GET_META_DATA)
            .mapNotNull { ri ->
                val si = ri.serviceInfo ?: return@mapNotNull null
                if (si.packageName == ctx.packageName) return@mapNotNull null
                val md = si.metaData ?: return@mapNotNull null
                val so = md.getString(META_SO) ?: return@mapNotNull null
                if (md.getInt(META_MAJOR, 1) != 1) return@mapNotNull null
                val base = si.applicationInfo.nativeLibraryDir ?: return@mapNotNull null
                val p = si.packageName
                val kind = when {
                    p.startsWith("org.freedesktop.monado") -> Kind.MONADO
                    p.startsWith("com.oculus.") || p.startsWith("com.meta.") -> Kind.OCULUS
                    p.startsWith("com.pico") || p.startsWith("com.picovr") || p.startsWith("com.pvr") -> Kind.PICO
                    else -> Kind.OTHER
                }
                RuntimeInfo(p, si.applicationInfo.loadLabel(pm).toString(), so, base, kind)
            }
            .distinctBy { it.pkg }
    }

    /** Pick the runtime for [callingPkg]: per-app pin > auto-by-platform > global default > Monado > first. */
    fun resolve(ctx: Context, callingPkg: String?): RuntimeInfo? {
        val all = list(ctx)
        if (all.isEmpty()) return null
        val prefs = Prefs(ctx)
        fun byPkg(p: String) = all.firstOrNull { it.pkg == p }

        val pinned = callingPkg?.let { prefs.appRuntime(it) } ?: "auto"
        if (pinned != "auto") byPkg(pinned)?.let { return it }

        if (callingPkg != null) {
            val e = AppScanner.entry(ctx, callingPkg, false)
            val wanted = when {
                e?.quest == true -> Kind.OCULUS
                e?.pico == true -> Kind.PICO
                else -> null
            }
            if (wanted != null) all.firstOrNull { it.kind == wanted }?.let { return it }
        }
        val def = prefs.defaultRuntime
        if (def != "auto") byPkg(def)?.let { return it }
        return all.firstOrNull { it.kind == Kind.MONADO } ?: all.first()
    }
}
