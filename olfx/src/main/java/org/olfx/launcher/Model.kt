package org.olfx.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
            .filter { it != ctx.packageName && !it.startsWith("org.freedesktop.monado") && !it.startsWith("com.android.chrome") && !it.startsWith("com.google.") }
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

    /**
     * Intent for the "Play" button. Only returns an activity that OLFX is actually allowed to start:
     * exported, enabled, and not protected by a permission the launcher does not hold. Picking the first
     * VR activity blindly is what produced Android's "Permission Denial: starting Intent" on some Quest
     * titles whose VR entry point is internal or signature-protected.
     */
    fun playIntent(ctx: Context, e: AppEntry): Intent? {
        val pm = ctx.packageManager
        val categories = buildList {
            if (e.quest) add(VR_CATEGORY)
            if (e.pico) add(YVR_CATEGORY)
            add(XR_CATEGORY)
            add(Intent.CATEGORY_LAUNCHER)
            add(Intent.CATEGORY_LEANBACK_LAUNCHER)
        }.distinct()
        for (category in categories) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category).setPackage(e.pkg)
            val match = pm.queryIntentActivities(query, 0).firstOrNull { canStart(ctx, it.activityInfo) }
                ?: continue
            return Intent(query).setComponent(ComponentName(e.pkg, match.activityInfo.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        // Some games only expose a launcher alias; validate it too instead of handing startActivity a
        // component that will throw SecurityException.
        val launch = pm.getLaunchIntentForPackage(e.pkg) ?: return null
        val resolved = pm.resolveActivity(launch, 0)?.activityInfo ?: return null
        if (!canStart(ctx, resolved)) return null
        return launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }

    private fun canStart(ctx: Context, ai: android.content.pm.ActivityInfo): Boolean {
        val app = ai.applicationInfo ?: return false
        if (!ai.exported || !ai.enabled || !app.enabled) return false
        val permission = ai.permission ?: return true
        return ctx.packageManager.checkPermission(permission, ctx.packageName) == PackageManager.PERMISSION_GRANTED
    }
}

class Prefs(ctx: Context) {
    private val appContext = ctx.applicationContext ?: ctx
    private val sp = appContext.getSharedPreferences("olfx", Context.MODE_PRIVATE)
    private fun notifyHands() {
        try {
            appContext.contentResolver.notifyChange(Uri.parse("content://${appContext.packageName}.settings/olfx/hands"), null)
        } catch (_: Exception) {
        }
    }
    private fun notifyClick() {
        try {
            appContext.contentResolver.notifyChange(Uri.parse("content://${appContext.packageName}.settings/olfx/click"), null)
        } catch (_: Exception) {
        }
    }

    /** Explicit OLFX language; "system" follows the Android language list. */
    var language: String
        get() = AppLocale.normalize(sp.getString("language", AppLocale.SYSTEM))
        set(value) = sp.edit().putString("language", AppLocale.normalize(value)).apply()

    /** Undistorted left/right views, for displays used without headset lenses. */
    var sbsEnabled: Boolean
        get() = sp.getBoolean("sbs_enabled", false)
        set(value) = sp.edit().putBoolean("sbs_enabled", value).apply()

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

    /** Patch profile per game: "none", "opt" (optimisation) or "immersive". */
    fun profile(pkg: String): String = sp.getString("profile_$pkg", "none") ?: "none"
    fun setProfile(pkg: String, v: String) = sp.edit().putString("profile_$pkg", v).apply()

    /** Last game launched through OLFX; used by the runtime to draw that game's tiny SBS badge. */
    var currentGame: String
        get() = sp.getString("current_game", "") ?: ""
        set(value) = sp.edit().putString("current_game", value).apply()

    /** Global render scale. A single value avoids leaking the previous game's profile into the next session. */
    var currentScale: Int
        get() = sp.getInt("current_scale", 80).coerceIn(50, 150)
        set(value) = sp.edit().putInt("current_scale", value.coerceIn(50, 150)).apply()

    /** Camera hand tracking on/off (read by the Monado service through the broker provider). */
    var handEnabled: Boolean
        get() = sp.getBoolean("hand_enabled", true)
        set(v) {
            sp.edit().putBoolean("hand_enabled", v).apply()
            notifyHands()
        }

    /** Swap left/right hands (if the model's handedness comes out mirrored on some camera). */
    var handSwap: Boolean
        get() = sp.getBoolean("hand_swap", false)
        set(v) {
            sp.edit().putBoolean("hand_swap", v).apply()
            notifyHands()
        }

    /** Opt-in ONNX Runtime (RTMDet + RTMPose, NPU via NNAPI) hand backend instead of MediaPipe. */
    var handOnnx: Boolean
        get() = sp.getBoolean("hand_onnx", false)
        set(v) {
            sp.edit().putBoolean("hand_onnx", v).apply()
            notifyHands()
        }

    /** "screen" (normal touch) or "pinch" (hand-tracking pinch injects a tap). */
    var clickMode: String
        get() = ClickMode.normalize(sp.getString("click_mode", ClickMode.SCREEN))
        set(v) {
            sp.edit().putString("click_mode", ClickMode.normalize(v)).apply()
            notifyClick()
        }

    fun appRuntime(pkg: String): String = sp.getString("rt_app_$pkg", "auto") ?: "auto"
    fun setAppRuntime(pkg: String, rt: String) = sp.edit().putString("rt_app_$pkg", rt).apply()
}

object ClickMode {
    const val SCREEN = "screen"
    const val PINCH = "pinch"
    fun normalize(v: String?): String = if (v == PINCH) PINCH else SCREEN
}

object Profiles {
    const val NONE = "none"
    const val OPT = "opt"
    const val IMMERSIVE = "immersive"

    /** Compositor scale in percent for each profile (unpatched games get the full default). */
    fun scale(profile: String) = when (profile) {
        OPT -> 50
        IMMERSIVE -> 85
        else -> 100
    }


}

/** Reads the `olfx-patched=<mode>` marker the OLFX APK patcher writes into a Unity game's boot.config. */
object ApkPatch {
    const val LOADER_KHRONOS = "khronos"
    const val LOADER_META = "meta"
    const val LOADER_STANDARD = "standard"

    fun runtimeMarker(mode: String, loader: String, compat: Boolean): String =
        "$mode\nolfx-loader=$loader\nolfx-compat=${if (compat) 1 else 0}"

    /**
     * True when the installed APK was already patched for the runtime that will be used. Deliberately forgiving: the patch itself replaces
     * the loader (and a re-scan of the patched app can classify it differently from the original), so "Khronos or standard" both count as
     * done. Only a real change of choice asks again: compat on/off, or Meta's own loader needed/not needed.
     */
    fun matchesRuntime(ctx: Context, pkg: String, marker: String): Boolean {
        val resolved = Runtimes.resolve(ctx, pkg)
        val compat = RuntimeChoice.normalize(Prefs(ctx).appRuntime(pkg)) == RuntimeChoice.OPENXR_COMPAT
        val wantCompat = if (compat) "1" else "0"
        val haveCompat = marker.lineSequence().firstOrNull { it.trim().startsWith("olfx-compat=") }?.substringAfter('=')?.trim()
        val loader = marker.lineSequence().firstOrNull { it.trim().startsWith("olfx-loader=") }?.substringAfter('=')?.trim()
        // a marker from before compat existed means compat was off
        val ok = (haveCompat ?: "0") == wantCompat && when {
            loader == null -> true
            compat -> loader == LOADER_KHRONOS
            resolved?.kind == Kind.OCULUS -> loader == LOADER_META
            else -> loader != LOADER_META
        }
        if (!ok) android.util.Log.i("OlfxPatch", "re-patch needed for $pkg: marker=[${marker.replace("\n", " | ")}] wantCompat=$wantCompat resolved=${resolved?.kind}")
        return ok
    }

    fun marker(ctx: Context, pkg: String): String? = try {
        val src = ctx.packageManager.getApplicationInfo(pkg, 0).sourceDir
        java.util.zip.ZipFile(src).use { z ->
            z.getEntry("assets/olfx-patch")?.let { m ->
                return z.getInputStream(m).bufferedReader().readText().trim().ifEmpty { null }
            }
            val e = z.getEntry("assets/bin/Data/boot.config") ?: return null
            z.getInputStream(e).bufferedReader().useLines { lines ->
                lines.firstOrNull { it.startsWith("olfx-patched=") }?.substringAfter('=')?.trim()
            }
        }
    } catch (e: Exception) {
        null
    }
}

/** Single-flight gate for APK optimization (frameloader-style bounded, serialized installer work). */
object OptimizationGate {
    private val current = java.util.concurrent.atomic.AtomicReference<String?>(null)
    fun tryAcquire(pkg: String): Boolean = current.compareAndSet(null, pkg)
    fun release(pkg: String) { current.compareAndSet(pkg, null) }
    fun isRunning(): Boolean = current.get() != null
}
