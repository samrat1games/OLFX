// Copyright 2026, OLFX contributors
// SPDX-License-Identifier: BSL-1.0
package org.olfx.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.net.Uri

enum class Kind { MONADO, OCULUS, PICO, OTHER }

data class RuntimeInfo(
    val pkg: String,
    val label: String,
    val so: String,
    val basePath: String,
    val kind: Kind,
    val nativeLibDir: String = basePath,
)

object RuntimeChoice {
    const val AUTO = "auto"
    const val MONADO = "monado"
    const val OCULUSXR = "oculusxr"
    /** Experimental OVRPlugin(VrApi/OculusXR-era) -> OpenXR compatibility, backed by Monado camera hands. */
    const val OPENXR_COMPAT = "openxr_compat"

    fun normalize(value: String?): String = when (value) {
        null, "", AUTO -> AUTO
        MONADO -> MONADO
        OCULUSXR -> OCULUSXR
        OPENXR_COMPAT -> OPENXR_COMPAT
        else -> value ?: AUTO // Legacy preferences stored an explicit runtime package name.
    }
}

/** Runtime selection independent of PackageManager, covered by JVM tests. */
internal object RuntimePolicy {
    fun select(
        runtimes: List<RuntimeInfo>,
        ownPackage: String,
        quest: Boolean,
        pico: Boolean,
        appChoice: String?,
        defaultChoice: String?,
        cameraHands: Boolean = false,
        headset: Boolean = false,
    ): RuntimeInfo? {
        if (runtimes.isEmpty()) return null
        fun byKind(kind: Kind) = runtimes.firstOrNull { it.kind == kind }
        fun resolveChoice(choice: String): RuntimeInfo? = when (RuntimeChoice.normalize(choice)) {
            RuntimeChoice.MONADO -> runtimes.firstOrNull { it.pkg == ownPackage } ?: byKind(Kind.MONADO)
            RuntimeChoice.OCULUSXR -> byKind(Kind.OCULUS)
            RuntimeChoice.OPENXR_COMPAT -> runtimes.firstOrNull { it.pkg == ownPackage } ?: byKind(Kind.MONADO)
            RuntimeChoice.AUTO -> null
            else -> runtimes.firstOrNull { it.pkg == choice }
        }

        val app = RuntimeChoice.normalize(appChoice)
        if (app != RuntimeChoice.AUTO) return resolveChoice(app)

        val default = RuntimeChoice.normalize(defaultChoice)
        if (default != RuntimeChoice.AUTO) return resolveChoice(default)

        // Quest titles get OculusXR automatically when the Meta runtime is actually present.
        // Exception: when OLFX camera hands are enabled, Auto must not silently bypass Monado;
        // the proprietary Meta path cannot receive OLFX camera joints. Explicit OculusXR still wins.
        if (quest) {
            if (cameraHands) (runtimes.firstOrNull { it.pkg == ownPackage } ?: byKind(Kind.MONADO))?.let { return it }
            byKind(Kind.OCULUS)?.let { return it }
        }
        if (pico) byKind(Kind.PICO)?.let { return it }
        // On a standalone headset the system's own OpenXR runtime owns the display and tracking;
        // the built-in Monado runtime (phone display + phone sensors) is only the last resort.
        if (headset) {
            (byKind(Kind.PICO) ?: byKind(Kind.OCULUS) ?: runtimes.firstOrNull { it.kind == Kind.OTHER })?.let { return it }
        }

        return runtimes.firstOrNull { it.pkg == ownPackage } ?: byKind(Kind.MONADO) ?: runtimes.first()
    }
}

@Suppress("DEPRECATION")
object Runtimes {
    private const val ACTION = "org.khronos.openxr.OpenXRRuntimeService"
    private const val META_SO = "org.khronos.openxr.OpenXRRuntime.SoFilename"
    private const val META_MAJOR = "org.khronos.openxr.OpenXRRuntime.MajorVersion"
    private const val SYSTEM_BROKER = "org.khronos.openxr.system_runtime_broker"
    private const val CACHE_TTL_MS = 3_000L
    private val ACTIVE_COLUMNS = arrayOf("package_name", "so_filename", "native_lib_dir", "base_path")

    private data class Snapshot(val abi: String, val atMs: Long, val runtimes: List<RuntimeInfo>)
    @Volatile private var snapshot: Snapshot? = null

    /** Drop discovery cache after package install/remove/update. */
    fun invalidate() {
        snapshot = null
    }

    /**
     * Installed OpenXR runtimes. Discovery is cached and single-flight: the loader may query the broker
     * several times per game launch, and PackageManager/service queries are comparatively expensive.
     * In addition to the installable-broker service metadata, we read the standard Android *system*
     * broker (used by vendor runtimes such as Meta/OculusXR) when the platform exposes it.
     */
    fun list(ctx: Context, abi: String? = null): List<RuntimeInfo> {
        val effectiveAbi = abi ?: Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val now = SystemClock.elapsedRealtime()
        snapshot?.let { if (it.abi == effectiveAbi && now - it.atMs < CACHE_TTL_MS) return it.runtimes }
        return synchronized(this) {
            val again = snapshot
            if (again != null && again.abi == effectiveAbi && SystemClock.elapsedRealtime() - again.atMs < CACHE_TTL_MS) {
                return@synchronized again.runtimes
            }
            val found = LinkedHashMap<String, RuntimeInfo>()
            systemBrokerRuntime(ctx, effectiveAbi)?.let { found[it.pkg] = it }
            for (r in discoverServices(ctx)) found.putIfAbsent(r.pkg, r)
            val result = found.values.toList()
            snapshot = Snapshot(effectiveAbi, SystemClock.elapsedRealtime(), result)
            result
        }
    }

    private fun discoverServices(ctx: Context): List<RuntimeInfo> {
        val pm = ctx.packageManager
        return pm.queryIntentServices(Intent(ACTION), PackageManager.GET_META_DATA)
            .mapNotNull { ri ->
                val si = ri.serviceInfo ?: return@mapNotNull null
                if (!si.enabled || si.applicationInfo == null || !si.applicationInfo.enabled) return@mapNotNull null
                val md = si.metaData ?: return@mapNotNull null
                val so = md.getString(META_SO)?.takeIf { it.isNotEmpty() && !it.contains('/') } ?: return@mapNotNull null
                if (md.getInt(META_MAJOR, 1) != 1) return@mapNotNull null
                val base = si.applicationInfo.nativeLibraryDir ?: return@mapNotNull null
                runtimeInfo(ctx, si.packageName, si.applicationInfo.loadLabel(pm).toString(), so, base)
            }
            .distinctBy { it.pkg }
            .sortedBy { if (it.pkg == "com.oculus.systemdriver") 0 else 1 }
    }

    /** Standard Khronos system-broker active runtime row; null when absent/not permitted. */
    private fun systemBrokerRuntime(ctx: Context, abi: String): RuntimeInfo? {
        if (abi !in Build.SUPPORTED_ABIS) return null
        val uri = Uri.parse("content://$SYSTEM_BROKER/openxr/1/abi/$abi/runtimes/active/0")
        return try {
            ctx.contentResolver.query(uri, ACTIVE_COLUMNS, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                fun col(name: String): String? {
                    val i = c.getColumnIndex(name)
                    return if (i >= 0 && !c.isNull(i)) c.getString(i) else null
                }
                val pkg = col("package_name") ?: return@use null
                val so = col("so_filename")?.takeIf { it.isNotEmpty() && !it.contains('/') } ?: return@use null
                val base = col("base_path") ?: col("native_lib_dir") ?: return@use null
                val nativeLibDir = col("native_lib_dir") ?: base
                val label = try {
                    ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
                } catch (_: Exception) { pkg }
                runtimeInfo(ctx, pkg, label, so, base, nativeLibDir)
            }
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun runtimeInfo(
        ctx: Context,
        pkg: String,
        label: String,
        so: String,
        base: String,
        nativeLibDir: String = base,
    ): RuntimeInfo {
        val kind = when {
            pkg == ctx.packageName || pkg.startsWith("org.freedesktop.monado") -> Kind.MONADO
            pkg == "com.oculus.systemdriver" || pkg.startsWith("com.oculus.") || pkg.startsWith("com.meta.") -> Kind.OCULUS
            pkg.startsWith("com.pico") || pkg.startsWith("com.picovr") || pkg.startsWith("com.pvr") -> Kind.PICO
            else -> Kind.OTHER
        }
        return RuntimeInfo(pkg, label, so, base, kind, nativeLibDir)
    }

    fun oculus(ctx: Context): RuntimeInfo? = list(ctx).firstOrNull { it.kind == Kind.OCULUS }

    fun resolve(ctx: Context, callingPkg: String?, abi: String? = null): RuntimeInfo? {
        if (abi != null && abi !in Build.SUPPORTED_ABIS) return null
        val entry = callingPkg?.let { AppScanner.entry(ctx, it, false) }
        return RuntimePolicy.select(
            list(ctx, abi),
            ctx.packageName,
            entry?.quest == true,
            entry?.pico == true,
            callingPkg?.let { prefsApp(ctx, it) },
            Prefs(ctx).defaultRuntime,
            Prefs(ctx).handEnabled,
            Headset.isHeadset,
        )
    }

    private fun prefsApp(ctx: Context, pkg: String): String = Prefs(ctx).appRuntime(pkg)
}
