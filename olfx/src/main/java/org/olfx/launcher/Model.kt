package org.olfx.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import java.io.File

const val VR_CATEGORY = "com.oculus.intent.category.VR"

data class AppEntry(
    val pkg: String,
    val label: String,
    val icon: Drawable,
    val quest: Boolean,
    val pico: Boolean,
    val openXr: Boolean,
)

@Suppress("DEPRECATION")
object AppScanner {
    fun scan(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val vr = Intent(Intent.ACTION_MAIN).addCategory(VR_CATEGORY)
        val vrPkgs = pm.queryIntentActivities(vr, 0).map { it.activityInfo.packageName }.toSet()
        return (pm.queryIntentActivities(launcher, 0) + pm.queryIntentActivities(vr, 0))
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != ctx.packageName }
            .mapNotNull { entry(ctx, it, it in vrPkgs) }
            .sortedBy { it.label.lowercase() }
    }

    fun entry(ctx: Context, pkg: String, hasVrCategory: Boolean): AppEntry? =
        try {
            val pm = ctx.packageManager
            val pi = pm.getPackageInfo(pkg, PackageManager.GET_META_DATA or PackageManager.GET_CONFIGURATIONS)
            val ai = pi.applicationInfo ?: return null
            val keys = ai.metaData?.keySet() ?: emptySet<String>()
            val features = pi.reqFeatures?.mapNotNull { it.name } ?: emptyList()
            val quest =
                hasVrCategory ||
                    pkg.startsWith("com.oculus.") || pkg.startsWith("com.meta.") ||
                    keys.any { it.startsWith("com.oculus.") } ||
                    features.any { it.startsWith("oculus.") }
            val pico =
                pkg.startsWith("com.pico") || pkg.startsWith("com.picovr") || pkg.startsWith("com.pvr") ||
                    keys.any { it.startsWith("pvr.") || it.startsWith("pico.") || it.startsWith("com.pvr.") }
            val openXr =
                File(ai.nativeLibraryDir ?: "", "libopenxr_loader.so").exists() ||
                    features.any { it.contains("openxr", true) || it == "android.hardware.vr.headtracking" }
            AppEntry(pkg, ai.loadLabel(pm).toString(), ai.loadIcon(pm), quest, pico, openXr)
        } catch (e: Exception) {
            null
        }

    /** Intent for the "Play" button. Prefers the VR-category activity for Meta Quest apps. */
    fun playIntent(ctx: Context, e: AppEntry): Intent? {
        val pm = ctx.packageManager
        if (e.quest) {
            val vr = Intent(Intent.ACTION_MAIN).addCategory(VR_CATEGORY).setPackage(e.pkg)
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(vr, 0).firstOrNull()?.let {
                return Intent(vr).setComponent(ComponentName(e.pkg, it.activityInfo.name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        return pm.getLaunchIntentForPackage(e.pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("olfx", Context.MODE_PRIVATE)

    fun isFav(pkg: String) = pkg in favs()
    fun favs(): Set<String> = sp.getStringSet("favs", emptySet()) ?: emptySet()
    fun toggleFav(pkg: String) {
        val s = favs().toMutableSet()
        if (!s.add(pkg)) s.remove(pkg)
        sp.edit().putStringSet("favs", s).apply()
    }

    /** Runtime package name, or "auto". */
    var defaultRuntime: String
        get() = sp.getString("rt_default", "auto") ?: "auto"
        set(v) = sp.edit().putString("rt_default", v).apply()

    fun appRuntime(pkg: String): String = sp.getString("rt_app_$pkg", "auto") ?: "auto"
    fun setAppRuntime(pkg: String, rt: String) = sp.edit().putString("rt_app_$pkg", rt).apply()
}
