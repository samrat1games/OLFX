package org.olfx.launcher

import java.security.MessageDigest
import java.util.zip.Adler32

/** Routes Unity's Oculus hardware gate into the explicitly selected OpenXR compatibility path. */
object DexCompat {
    fun patch(source: ByteArray): ByteArray {
        if (source.size < 112 || String(source, 0, 4, Charsets.US_ASCII) != "dex\n") return source
        fun u16(p: Int) = (source[p].toInt() and 255) or ((source[p + 1].toInt() and 255) shl 8)
        fun u32(p: Int) = u16(p) or (u16(p + 2) shl 16)
        fun uleb(cursor: IntArray): Int {
            var value = 0; var shift = 0
            repeat(5) {
                val b = source[cursor[0]++].toInt() and 255
                value = value or ((b and 127) shl shift)
                if (b and 128 == 0) return value
                shift += 7
            }
            error("Invalid DEX integer")
        }
        return try {
            val stringBase = u32(60); val typeBase = u32(68); val methodBase = u32(92)
            fun str(index: Int): String {
                val c = intArrayOf(u32(stringBase + index * 4)); uleb(c)
                var end = c[0]
                while (source[end].toInt() != 0) end++
                return String(source, c[0], end - c[0], Charsets.UTF_8)
            }
            var target = -1
            for (i in 0 until u32(88)) {
                val m = methodBase + i * 8
                if (str(u32(m + 4)) == "getIsOnOculusHardware" &&
                    str(u32(typeBase + u16(m) * 4)) == "Lcom/unity/oculus/OculusUnity;") {
                    target = i; break
                }
            }
            if (target < 0) return source
            val classBase = u32(100)
            for (i in 0 until u32(96)) {
                val data = u32(classBase + i * 32 + 24)
                if (data == 0) continue
                val c = intArrayOf(data)
                val staticFields = uleb(c); val instanceFields = uleb(c)
                val direct = uleb(c); val virtual = uleb(c)
                repeat(staticFields + instanceFields) { uleb(c); uleb(c) }
                for (count in intArrayOf(direct, virtual)) {
                    var method = 0
                    repeat(count) {
                        method += uleb(c); val flags = uleb(c); val code = uleb(c)
                        if (method == target && flags and 8 != 0 && code > 0 &&
                            u16(code) >= 1 && u16(code + 6) == 0 && u32(code + 12) >= 2) {
                            val out = source.copyOf()
                            val start = code + 16
                            java.util.Arrays.fill(out, start, start + u32(code + 12) * 2, 0.toByte())
                            out[start] = 0x12; out[start + 1] = 0x10 // const/4 v0, 1
                            out[start + 2] = 0x0f; out[start + 3] = 0 // return v0
                            val hash = MessageDigest.getInstance("SHA-1").digest(out.copyOfRange(32, out.size))
                            hash.copyInto(out, 12)
                            val checksum = Adler32().apply { update(out, 12, out.size - 12) }.value
                            for (b in 0..3) out[8 + b] = (checksum shr (b * 8)).toByte()
                            return out
                        }
                    }
                }
            }
            source
        } catch (_: Exception) { source }
    }
}
