package org.olfx.launcher

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.android.apksig.ApkSigner
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
 * Copies the installed APK, rewrites `assets/bin/Data/boot.config` with performance / memory options,
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
        // NOT com.oculus.systemdriver: Meta's loader would then prefer that package over the OpenXR broker
    )
    private val QUERY_ACTIONS = listOf(
        "org.khronos.openxr.OpenXRRuntimeService",
        "org.khronos.openxr.OpenXRApiLayerService",
    )

    /** boot.config overrides per mode. All of these are options the Unity player reads from boot.config. */
    private fun overrides(mode: String): LinkedHashMap<String, String> {
        val m = LinkedHashMap<String, String>()
        when (mode) {
            Profiles.OPT -> {
                m["job-worker-count"] = "3"
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
                m["job-worker-count"] = "5"
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
            throw PatchException("Игра не установлена")
        }
        if (!ai.splitSourceDirs.isNullOrEmpty()) throw PatchException("Игра из нескольких APK (split) — пока не поддерживается")
        return patchFile(ctx, File(ai.sourceDir), pkg, ai.minSdkVersion, mode, progress)
    }

    /** Patches an arbitrary APK file (the original is never touched; the result goes to [outFile]). */
    fun patchFile(ctx: Context, src: File, pkg: String, minSdkVersion: Int, mode: String, progress: (String) -> Unit): File {
        val minSdk = minSdkVersion.coerceAtLeast(21)
        val out = outFile(ctx, pkg)
        val unsigned = File(out.parentFile, "$pkg.unsigned.apk")

        progress("Правка APK…")
        val khronos = try { ctx.assets.open("khronos/libopenxr_loader_arm64.so").use { it.readBytes() } } catch (e: Exception) { null }
        try {
            rewrite(src, unsigned, mode, khronos)
        } catch (e: PatchException) {
            unsigned.delete(); throw e
        }

        progress("Подпись…")
        try {
            sign(unsigned, out, minSdk)
        } finally {
            unsigned.delete()
        }
        return out
    }

    private class Counting(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
    }

    private fun rewrite(src: File, dst: File, mode: String, khronosLoader: ByteArray?) {
        var patched = false
        ZipFile(src).use { zin ->
            val names = zin.entries().asSequence().map { it.name }.toSet()
            val hasArm64 = names.any { it.startsWith("lib/arm64-v8a/") }
            Counting(dst.outputStream().buffered(1 shl 16)).use { counting ->
                ZipOutputStream(counting).use { zout ->
                    for (e in zin.entries()) {
                        val n = e.name
                        // old signature is invalid after the change
                        if (n.startsWith("META-INF/") && (n.endsWith(".SF") || n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC") || n == "META-INF/MANIFEST.MF")) continue
                        if (n == "assets/olfx-patch") continue // rewritten below
                        // drop unused ABIs on arm64 devices (smaller install, less to map)
                        if (hasArm64 && (n.startsWith("lib/armeabi") || n.startsWith("lib/x86"))) continue

                        val isBoot = n == BOOT_CONFIG
                        var data: ByteArray? = null
                        if (isBoot) {
                            data = patchBootConfig(zin.getInputStream(e).readBytes(), mode)
                            patched = true
                        } else if (n == "lib/arm64-v8a/libopenxr_loader.so" && khronosLoader != null) {
                            // Meta's loader only knows com.oculus.systemdriver (Quest). Swap in the standard Khronos
                            // loader, which finds the runtime through the broker (OLFX -> Monado).
                            val orig = zin.getInputStream(e).readBytes()
                            if (String(orig, Charsets.ISO_8859_1).contains("com.oculus.systemdriver")) data = khronosLoader
                        } else if (n == "AndroidManifest.xml") {
                            // let the game's OpenXR loader see OLFX (runtime broker) and the runtimes
                            data = try {
                                ManifestEditor.addQueries(zin.getInputStream(e).readBytes(), QUERY_PACKAGES, QUERY_ACTIONS)
                            } catch (x: ManifestEditor.ManifestException) {
                                throw PatchException("Манифест: ${x.message}")
                            }
                        }
                        // resources.arsc must be stored uncompressed and 4-byte aligned for apps targeting Android 11+
                        val stored = e.method == ZipEntry.STORED || n == "resources.arsc"
                        val ne = ZipEntry(n)
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
                            ne.extra = alignExtra(counting.count, n, align)
                        } else {
                            ne.method = ZipEntry.DEFLATED
                        }
                        zout.putNextEntry(ne)
                        if (data != null) {
                            zout.write(data)
                        } else {
                            zin.getInputStream(e).use { it.copyTo(zout, 1 shl 16) }
                        }
                        zout.closeEntry()
                    }
                    // Patch marker for every kind of game (Unity's boot.config only exists in Unity games).
                    zout.putNextEntry(ZipEntry("assets/olfx-patch"))
                    zout.write(mode.toByteArray())
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
