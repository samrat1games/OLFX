package org.olfx.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import java.io.File

const val VR_CATEGORY = "com.oculus.intent.category.VR"
const val YVR_CATEGORY = "com.yvr.intent.category.VR"
const val XR_CATEGORY = "org.khronos.openxr.intent.category.IMMERSIVE_HMD"
val XR_CATEGORIES = listOf(VR_CATEGORY, YVR_CATEGORY, XR_CATEGORY)

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
    /** Only OpenXR / Meta Quest / Pico apps; ordinary Android apps are not listed. */
    fun scan(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val intents = listOf(launcher) + XR_CATEGORIES.map { Intent(Intent.ACTION_MAIN).addCategory(it) }
        return intents.flatMap { pm.queryIntentActivities(it, 0) }
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != ctx.packageName }
            .mapNotNull { entry(ctx, it, false) }
            .filter { it.openXr || it.quest || it.pico }
            .sortedBy { it.label.lowercase() }
    }

    fun entry(ctx: Context, pkg: String, hasVrCategory: Boolean): AppEntry? =
        try {
            val pm = ctx.packageManager
            val pi = pm.getPackageInfo(pkg, PackageManager.GET_META_DATA or PackageManager.GET_CONFIGURATIONS)
            val ai = pi.applicationInfo ?: return null
            val keys = ai.metaData?.keySet() ?: emptySet<String>()
            val features = pi.reqFeatures?.mapNotNull { it.name } ?: emptyList()
            val cats = XR_CATEGORIES.filter { c ->
                pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(c).setPackage(pkg), 0).isNotEmpty()
            }.toSet()
            val quest =
                hasVrCategory || VR_CATEGORY in cats ||
                    pkg.startsWith("com.oculus.") || pkg.startsWith("com.meta.") ||
                    keys.any { it.startsWith("com.oculus.") } ||
                    features.any { it.startsWith("oculus.") }
            val pico =
                YVR_CATEGORY in cats ||
                    pkg.startsWith("com.pico") || pkg.startsWith("com.picovr") || pkg.startsWith("com.pvr") ||
                    keys.any { it.startsWith("pvr.") || it.startsWith("pico.") || it.startsWith("com.pvr.") }
            val openXr =
                XR_CATEGORY in cats || quest ||
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
