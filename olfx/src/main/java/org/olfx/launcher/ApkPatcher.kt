package org.olfx.launcher

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.android.apksig.ApkSigner
import org.freedesktop.monado.openxr_runtime.R
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * On-device APK patcher for Quest/Pico VR games (Unity, Unreal, native OpenXR).
 *
 * Copies the installed APK, optionally rewrites Unity `boot.config` with performance / memory options,
 * drops unused ABIs, re-aligns native libraries (16 KB) and signs the result (v1+v2) with a key kept in
 * the Android Keystore. The result can only be installed after the original is uninstalled (the signature
 * changes), see [DetailActivity].
 */
object ApkPatcher {
    private const val BOOT_CONFIG = "assets/bin/Data/boot.config"
    private const val KEY_ALIAS = "olfx-patch-key"

    class PatchException(msg: String) : Exception(msg)

    private val QUERY_PACKAGES = listOf(
        "org.olfx.launcher",
        "com.oculus.systemdriver",
    )
    private val QUERY_ACTIONS = listOf(
        "org.khronos.openxr.OpenXRRuntimeService",
        "org.khronos.openxr.OpenXRApiLayerService",
    )

    /** boot.config overrides per mode. All of these are options the Unity player reads from boot.config. */
    private fun overrides(mode: String): LinkedHashMap<String, String> {
        val m = LinkedHashMap<String, String>()
        // Device-aware worker count: forcing a fixed value can oversubscribe little cores or leave
        // big cores idle. Unity ignores unknown keys, but a sane value avoids pathological scheduling.
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        when (mode) {
            Profiles.OPT -> {
                m["job-worker-count"] = when { cores <= 4 -> 1; cores <= 6 -> 2; else -> 3 }.toString()
                m["gc-max-time-slice"] = "2"
                // smaller allocator blocks: less reserved memory on a RAM-starved device
                m["memorysetup-main-allocator-block-size"] = "8388608"
                m["memorysetup-thread-allocator-block-size"] = "4194304"
                m["memorysetup-gfx-main-allocator-block-size"] = "8388608"
                m["memorysetup-gfx-thread-allocator-block-size"] = "4194304"
                m["memorysetup-cache-allocator-block-size"] = "2097152"
                m["memorysetup-typetree-allocator-block-size"] = "1048576"
                m["memorysetup-profiler-allocator-block-size"] = "1048576"
                m["memorysetup-bucket-allocator-block-size"] = "2097152"
                m["memorysetup-temp-allocator-size-main"] = "2097152"
                m["memorysetup-temp-allocator-size-job-worker"] = "131072"
                m["memorysetup-temp-allocator-size-background-worker"] = "32768"
            }
            else -> {
                m["job-worker-count"] = (cores - 1).coerceIn(1, 5).toString()
                m["gc-max-time-slice"] = "3"
                m["memorysetup-main-allocator-block-size"] = "16777216"
                m["memorysetup-gfx-main-allocator-block-size"] = "16777216"
            }
        }
        m["olfx-patched"] = mode
        return m
    }

    fun patchBootConfig(data: ByteArray, mode: String): ByteArray {
        val ov = overrides(mode)
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (line in String(data, Charsets.UTF_8).lines()) {
            if (line.isBlank()) continue
            val key = line.substringBefore('=').trim()
            if (key in ov) {
                out.add("$key=${ov[key]}")
                seen.add(key)
            } else {
                out.add(line)
            }
        }
        for ((k, v) in ov) if (k !in seen) out.add("$k=$v")
        return (out.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8)
    }

    /** Output directory for patched APKs (visible to the user in Android/data/org.olfx.launcher/files/patched). */
    fun outFile(ctx: Context, pkg: String): File {
        val d = ctx.getExternalFilesDir("patched") ?: File(ctx.filesDir, "patched")
        d.mkdirs()
        return File(d, "$pkg.olfx.apk")
    }

    /** Returns the signed, patched APK. Throws [PatchException] with a user-readable message. */
    fun patch(ctx: Context, pkg: String, mode: String, progress: (String) -> Unit): File {
        val pm = ctx.packageManager
        val ai = try {
            pm.getApplicationInfo(pkg, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            throw PatchException(ctx.getString(R.string.game_not_installed))
        }
        if (!ai.splitSourceDirs.isNullOrEmpty()) throw PatchException(ctx.getString(R.string.split_apk_unsupported))
        return patchFile(ctx, File(ai.sourceDir), pkg, ai.minSdkVersion, mode, progress)
    }

    /** Patches an arbitrary APK file (the original is never touched; the result goes to [outFile]). */
    fun patchFile(ctx: Context, src: File, pkg: String, minSdkVersion: Int, mode: String, progress: (String) -> Unit): File {
        val minSdk = minSdkVersion.coerceAtLeast(21)
        val out = outFile(ctx, pkg)
        val unsigned = File(out.parentFile, "$pkg.unsigned.apk")
        // Preflight free space before touching the installed app (frameloader-style bounded install).
        val free = android.os.StatFs(out.parentFile!!.absolutePath).availableBytes
        val need = src.length() * 3 + 256L * 1024 * 1024
        if (free < need) throw PatchException(ctx.getString(R.string.patch_no_space))

        progress(ctx.getString(R.string.patching_apk))
        val khronos = try { ctx.assets.open("khronos/libopenxr_loader_arm64.so").use { it.readBytes() } } catch (e: Exception) { null }
        // Respect the per-game runtime choice. Preserving Meta's loader just because OculusXR is installed
        // would keep a Monado-selected Quest title on the proprietary path where OLFX camera hands cannot
        // be injected. Only keep the Meta loader/plugin when this game actually resolves to OculusXR.
        val compat = RuntimeChoice.normalize(Prefs(ctx).appRuntime(pkg)) == RuntimeChoice.OPENXR_COMPAT
        val preferOculus = !compat && Runtimes.resolve(ctx, pkg)?.kind == Kind.OCULUS
        val shim = if (compat) {
            val f = File(ctx.applicationInfo.nativeLibraryDir ?: "", "libOVRPlugin.so")
            if (!f.isFile) throw PatchException(ctx.getString(R.string.openxr_compat_missing))
            try { f.readBytes() } catch (e: Exception) { throw PatchException(ctx.getString(R.string.openxr_compat_missing)) }
        } else null
        val hasMetaLoader = try {
            ZipFile(src).use { z ->
                z.getEntry("lib/arm64-v8a/libopenxr_loader.so")?.let {
                    String(z.getInputStream(it).readBytes(), Charsets.ISO_8859_1).contains("com.oculus.systemdriver")
                } ?: false
            }
        } catch (e: Exception) { false }
        val loaderMode = when {
            compat -> ApkPatch.LOADER_KHRONOS
            preferOculus -> ApkPatch.LOADER_META
            AppScanner.entry(ctx, pkg, false)?.quest == true || hasMetaLoader -> ApkPatch.LOADER_KHRONOS
            else -> ApkPatch.LOADER_STANDARD
        }
        // Compatibility mode provides local initialization and entitlement responses; it does not verify ownership.
        // The original loader is retained as *_real.so for the generated non-platform exports.
        val platformGuard = if (compat) {
            try { ctx.assets.open("ovr/libovrplatformloader_guard_arm64.so").use { it.readBytes() } } catch (e: Exception) { null }
        } else null
        // Gear VR / Quest games on VrApi: their libvrapi.so only finds Oculus' system driver; the adapter draws through OpenXR instead
        val vrapiShim = if (preferOculus) null else try { ctx.assets.open("vrapi/libvrapi_shim_arm64.so").use { it.readBytes() } } catch (e: Exception) { null }
        try {
            rewrite(src, unsigned, mode, khronos, preferOculus, compat, shim, loaderMode, ctx, vrapiShim, platformGuard)
        } catch (e: PatchException) {
            unsigned.delete(); throw e
        }

        progress(ctx.getString(R.string.signing_apk))
        try {
            sign(unsigned, out, minSdk)
        } finally {
            unsigned.delete()
        }
        return out
    }

    private const val PLATFORM_LOADER = "lib/arm64-v8a/libovrplatformloader.so"
    private const val PLATFORM_LOADER_REAL = "lib/arm64-v8a/libovrplatformloader_real.so"

    private class Counting(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
    }

    private fun rewrite(src: File, dst: File, mode: String, khronosLoader: ByteArray?, preferOculus: Boolean,
                        compat: Boolean, shim: ByteArray?, loaderMode: String, ctx: Context, vrapiShim: ByteArray? = null,
                        platformGuard: ByteArray? = null) {
        var patched = false
        ZipFile(src).use { zin ->
            val names = zin.entries().asSequence().map { it.name }.toSet()
            val hasArm64 = names.any { it.startsWith("lib/arm64-v8a/") }
            val hookConfig = zin.getEntry("lib/arm64-v8a/libfrda.config.so")?.let {
                String(zin.getInputStream(it).readBytes(), Charsets.UTF_8)
            }.orEmpty()
            val optionalHook = if (compat && hookConfig.contains("libscript.so") && hookConfig.contains("patch_ovrplatformloader")) {
                ctx.assets.open("ovr/liboptional_hook_arm64.so").use { it.readBytes() }
            } else null

            var wroteLoader = false
            var wrotePlugin = false
            Counting(dst.outputStream().buffered(1 shl 16)).use { counting ->
                ZipOutputStream(counting).use { zout ->
                    for (e in zin.entries()) {
                        val n = e.name
                        // old signature is invalid after the change
                        if (n.startsWith("META-INF/") && (n.endsWith(".SF") || n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC") || n == "META-INF/MANIFEST.MF")) continue
                        if (n == "assets/olfx-patch") continue // rewritten below
                        // drop unused ABIs on arm64 devices (smaller install, less to map)
                        if (hasArm64 && (n.startsWith("lib/armeabi") || n.startsWith("lib/x86"))) continue

                        val hasReal = "lib/arm64-v8a/libOVRPlugin_real.so" in names
                        // Experimental compatibility replaces OVRPlugin with the OpenXR-backed shim.
                        // Otherwise keep/restore Meta's original plugin (OculusXR passthrough).
                        if (compat && n == "lib/arm64-v8a/libOVRPlugin_real.so") continue
                        if (!compat && n == "lib/arm64-v8a/libOVRPlugin.so" && hasReal) continue
                        val outName = if (!compat && n == "lib/arm64-v8a/libOVRPlugin_real.so") "lib/arm64-v8a/libOVRPlugin.so" else n
                        // Platform guard (see platformGuard): the original loader moves to *_real.so, the guard takes its name.
                        val hasLoaderReal = PLATFORM_LOADER_REAL in names
                        if (!compat && n == PLATFORM_LOADER && hasLoaderReal) continue // drop a previously installed guard
                        val loaderOutName = if (!compat && n == PLATFORM_LOADER_REAL) PLATFORM_LOADER else outName
                        // Writes one entry; keeps STORED/aligned layout of the source entry (native libs must stay mmap-able).
                        fun emit(entryName: String, data: ByteArray?) {
                            // resources.arsc must be stored uncompressed and 4-byte aligned for apps targeting Android 11+
                            val stored = e.method == ZipEntry.STORED || n == "resources.arsc"
                            val ne = ZipEntry(entryName)
                            ne.time = e.time
                            if (stored) {
                                ne.method = ZipEntry.STORED
                                if (data != null) {
                                    val crc = CRC32().also { it.update(data) }
                                    ne.size = data.size.toLong(); ne.compressedSize = data.size.toLong(); ne.crc = crc.value
                                } else {
                                    ne.size = e.size; ne.compressedSize = e.size; ne.crc = e.crc
                                }
                                val align = if (n.endsWith(".so")) 16384 else 4
                                ne.extra = alignExtra(counting.count, entryName, align)
                            } else {
                                ne.method = ZipEntry.DEFLATED
                            }
                            zout.putNextEntry(ne)
                            if (data != null) zout.write(data) else zin.getInputStream(e).use { it.copyTo(zout, 1 shl 16) }
                            zout.closeEntry()
                        }
                        if (platformGuard != null && n == PLATFORM_LOADER && !hasLoaderReal) emit(PLATFORM_LOADER_REAL, null)
                        if (optionalHook != null && n == "lib/arm64-v8a/libfrda.so" &&
                            "assets/olfx-backup/libfrda.so" !in names) emit("assets/olfx-backup/libfrda.so", null)
                        val isBoot = n == BOOT_CONFIG
                        var data: ByteArray? = null
                        if (isBoot) {
                            data = patchBootConfig(zin.getInputStream(e).readBytes(), mode)
                            patched = true
                        } else if (compat && Regex("classes[0-9]*\\.dex").matches(n)) {
                            data = DexCompat.patch(zin.getInputStream(e).readBytes())
                        } else if (n == "lib/arm64-v8a/libfrda.so" && optionalHook != null) {
                            data = optionalHook
                        } else if (n == PLATFORM_LOADER && platformGuard != null) {
                            data = platformGuard
                        } else if (n == "lib/arm64-v8a/libvrapi.so" && vrapiShim != null) {
                            data = vrapiShim
                        } else if (n == "lib/arm64-v8a/libOVRPlugin.so" && compat) {
                            wrotePlugin = true
                            data = shim
                        } else if (n == "lib/arm64-v8a/libopenxr_loader.so" && khronosLoader != null) {
                            wroteLoader = true
                            val orig = zin.getInputStream(e).readBytes()
                            // Compat always needs the Khronos loader. Normal Monado routing replaces Meta's
                            // Quest-only loader; explicit OculusXR keeps Meta's loader intact.
                            if (compat || (!preferOculus && String(orig, Charsets.ISO_8859_1).contains("com.oculus.systemdriver"))) data = khronosLoader
                        } else if (n == "AndroidManifest.xml") {
                            // let the game's OpenXR loader see OLFX (runtime broker) and the runtimes
                            data = try {
                                ManifestEditor.addQueries(zin.getInputStream(e).readBytes(), QUERY_PACKAGES, QUERY_ACTIONS)
                            } catch (x: ManifestEditor.ManifestException) {
                                throw PatchException(ctx.getString(R.string.manifest_error, x.message ?: ""))
                            }
                        }
                        emit(loaderOutName, data)
                    }
                    if (compat && !wrotePlugin && shim != null) {
                        val ne = ZipEntry("lib/arm64-v8a/libOVRPlugin.so")
                        ne.method = ZipEntry.DEFLATED
                        zout.putNextEntry(ne)
                        zout.write(shim)
                        zout.closeEntry()
                    }
                    // The OpenXR-backed OVRPlugin shim needs a Khronos loader even in games that never shipped one.
                    if (compat && !wroteLoader && khronosLoader != null) {
                        val ne = ZipEntry("lib/arm64-v8a/libopenxr_loader.so")
                        ne.method = ZipEntry.DEFLATED
                        zout.putNextEntry(ne)
                        zout.write(khronosLoader)
                        zout.closeEntry()
                    }
                    // The VrApi adapter needs the OpenXR loader next to it (VrApi-only games ship none)
                    if (vrapiShim != null && "lib/arm64-v8a/libvrapi.so" in names && "lib/arm64-v8a/libopenxr_loader.so" !in names && !wroteLoader && khronosLoader != null) {
                        val ne = ZipEntry("lib/arm64-v8a/libopenxr_loader.so")
                        ne.method = ZipEntry.DEFLATED
                        zout.putNextEntry(ne)
                        zout.write(khronosLoader)
                        zout.closeEntry()
                    }
                    // Patch marker records loader routing so a later runtime change can re-optimize safely.
                    zout.putNextEntry(ZipEntry("assets/olfx-patch"))
                    zout.write(ApkPatch.runtimeMarker(mode, loaderMode, compat).toByteArray())
                    zout.closeEntry()
                }
            }
        }
    }

    /** Extra field that pads a STORED entry so its data starts at a multiple of [align]. */
    private fun alignExtra(pos: Long, name: String, align: Int): ByteArray? {
        val base = pos + 30 + name.toByteArray(Charsets.UTF_8).size
        var pad = ((align - (base % align)) % align).toInt()
        if (pad == 0) return null
        if (pad < 4) pad += align
        val b = ByteArrayOutputStream(pad)
        // zipalign's own extra id 0xD935: id(2) size(2) alignment(2) zeros
        b.write(0x35); b.write(0xD9)
        val size = pad - 4
        b.write(size and 0xFF); b.write(size shr 8 and 0xFF)
        b.write(ByteArray(size))
        return b.toByteArray()
    }

    private fun signingMaterial(): Pair<PrivateKey, X509Certificate> {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(KEY_ALIAS)) {
            val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
            kpg.initialize(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setKeySize(2048)
                    .build()
            )
            kpg.generateKeyPair()
        }
        val key = ks.getKey(KEY_ALIAS, null) as PrivateKey
        val cert = ks.getCertificate(KEY_ALIAS) as X509Certificate
        return key to cert
    }

    private fun sign(inApk: File, outApk: File, minSdk: Int) {
        val (key, cert) = signingMaterial()
        val cfg = ApkSigner.SignerConfig.Builder("olfx", key, listOf(cert)).build()
        outApk.delete()
        ApkSigner.Builder(listOf(cfg))
            .setInputApk(inApk)
            .setOutputApk(outApk)
            .setMinSdkVersion(minSdk)
            .setV1SigningEnabled(minSdk < 24)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(false)
            .setAlignmentPreserved(true)
            .setLibraryPageAlignmentBytes(16384)
            .build()
            .sign()
    }
}
