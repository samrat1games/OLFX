package org.olfx.launcher

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal editor for a compiled (binary XML) AndroidManifest.xml.
 *
 * Adds a `<queries>` block so that a game can see the OpenXR runtime broker (OLFX), the runtimes and the
 * OpenXR service intents. Without it Android 11+ package visibility hides them and the game's OpenXR loader
 * fails with "Failed to load runtime" (black screen / flat 2D picture).
 *
 * Only strings are appended to the string pool (existing indices never change) and new nodes are inserted
 * right before the closing `</manifest>`; every attribute used is `android:name`, which every manifest has.
 */
object ManifestEditor {
    private const val RES_XML = 0x0003
    private const val RES_STRING_POOL = 0x0001
    private const val RES_XML_RESOURCE_MAP = 0x0180
    private const val START_NS = 0x0100
    private const val START_EL = 0x0102
    private const val END_EL = 0x0103
    private const val ATTR_NAME_RES_ID = 0x01010003
    private const val UTF8_FLAG = 0x100

    class ManifestException(msg: String) : Exception(msg)

    fun addQueries(manifest: ByteArray, packages: List<String>, actions: List<String>): ByteArray {
        val src = ByteBuffer.wrap(manifest).order(ByteOrder.LITTLE_ENDIAN)
        if (src.getShort(0).toInt() != RES_XML) throw ManifestException("AndroidManifest.xml is not binary XML")

        // ---- string pool ----
        val poolStart = 8
        if (src.getShort(poolStart).toInt() != RES_STRING_POOL) throw ManifestException("string pool expected")
        val poolSize = src.getInt(poolStart + 4)
        val stringCount = src.getInt(poolStart + 8)
        val styleCount = src.getInt(poolStart + 12)
        val flags = src.getInt(poolStart + 16)
        val stringsStart = src.getInt(poolStart + 20)
        val stylesStart = src.getInt(poolStart + 24)
        val utf8 = flags and UTF8_FLAG != 0
        val offsetsAt = poolStart + 28

        val blobs = ArrayList<ByteArray>(stringCount)
        val values = ArrayList<String>(stringCount)
        for (i in 0 until stringCount) {
            val off = poolStart + stringsStart + src.getInt(offsetsAt + 4 * i)
            val (blob, str) = readString(manifest, off, utf8)
            blobs.add(blob); values.add(str)
        }
        val styleOffsets = IntArray(styleCount) { src.getInt(offsetsAt + 4 * stringCount + 4 * it) }
        val styleData: ByteArray = if (styleCount > 0) {
            manifest.copyOfRange(poolStart + stylesStart, poolStart + poolSize)
        } else ByteArray(0)

        // ---- resource map: which string index is android:name ----
        var cur = poolStart + poolSize
        var nameIdx = -1
        if (src.getShort(cur).toInt() == RES_XML_RESOURCE_MAP) {
            val sz = src.getInt(cur + 4)
            val n = (sz - 8) / 4
            for (i in 0 until n) if (src.getInt(cur + 8 + 4 * i) == ATTR_NAME_RES_ID) { nameIdx = i; break }
            cur += sz
        }
        if (nameIdx < 0) throw ManifestException("android:name not in resource map")
        val bodyStart = cur // first XML node

        // ---- walk nodes: android namespace uri index + insertion point before the closing </manifest> ----
        var nsUri = -1
        var depth = 0
        var insertAt = -1
        var pos = bodyStart
        while (pos < manifest.size) {
            val type = src.getShort(pos).toInt() and 0xFFFF
            val size = src.getInt(pos + 4)
            if (type == START_NS && nsUri < 0) nsUri = src.getInt(pos + 16 + 4)
            if (type == START_EL) depth++
            if (type == END_EL) {
                depth--
                if (depth == 0) { insertAt = pos; break }
            }
            pos += size
        }
        if (insertAt < 0 || nsUri < 0) throw ManifestException("manifest structure not recognised")

        fun idx(s: String): Int {
            val i = values.indexOf(s)
            if (i >= 0) return i
            values.add(s); blobs.add(encode(s, utf8)); return values.size - 1
        }

        // ---- new nodes ----
        val nodes = ByteArrayOutputStream()
        fun startEl(name: String, attrName: String?) {
            val b = ByteBuffer.allocate(16 + 20 + (if (attrName != null) 20 else 0)).order(ByteOrder.LITTLE_ENDIAN)
            b.putShort(START_EL.toShort()); b.putShort(16)
            b.putInt(b.capacity()); b.putInt(0); b.putInt(-1)
            b.putInt(-1); b.putInt(idx(name))
            b.putShort(20); b.putShort(20); b.putShort(if (attrName != null) 1 else 0)
            b.putShort(0); b.putShort(0); b.putShort(0)
            if (attrName != null) {
                val v = idx(attrName)
                b.putInt(nsUri); b.putInt(nameIdx); b.putInt(v)
                b.putShort(8); b.put(0); b.put(0x03); b.putInt(v)
            }
            nodes.write(b.array())
        }
        fun endEl(name: String) {
            val b = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            b.putShort(END_EL.toShort()); b.putShort(16); b.putInt(24); b.putInt(0); b.putInt(-1)
            b.putInt(-1); b.putInt(idx(name))
            nodes.write(b.array())
        }
        startEl("queries", null)
        for (p in packages) { startEl("package", p); endEl("package") }
        for (a in actions) {
            startEl("intent", null); startEl("action", a); endEl("action"); endEl("intent")
        }
        endEl("queries")

        // ---- rebuild the string pool with the appended strings ----
        val data = ByteArrayOutputStream()
        val newOffsets = IntArray(blobs.size)
        for (i in blobs.indices) { newOffsets[i] = data.size(); data.write(blobs[i]) }
        while (data.size() % 4 != 0) data.write(0)
        val count = blobs.size
        val newStringsStart = 28 + 4 * count + 4 * styleCount
        val newStylesStart = if (styleCount > 0) newStringsStart + data.size() else 0
        val poolBytes = ByteArrayOutputStream()
        val h = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
        h.putShort(RES_STRING_POOL.toShort()); h.putShort(28)
        h.putInt(newStringsStart + data.size() + styleData.size)
        h.putInt(count); h.putInt(styleCount); h.putInt(flags); h.putInt(newStringsStart); h.putInt(newStylesStart)
        poolBytes.write(h.array())
        val off = ByteBuffer.allocate(4 * count + 4 * styleCount).order(ByteOrder.LITTLE_ENDIAN)
        newOffsets.forEach { off.putInt(it) }
        styleOffsets.forEach { off.putInt(it) }
        poolBytes.write(off.array())
        poolBytes.write(data.toByteArray())
        poolBytes.write(styleData)

        // ---- assemble: header | pool | (resmap + nodes before insertion) | new nodes | rest ----
        val out = ByteArrayOutputStream()
        out.write(manifest, 0, 8)
        out.write(poolBytes.toByteArray())
        out.write(manifest, poolStart + poolSize, insertAt - (poolStart + poolSize))
        out.write(nodes.toByteArray())
        out.write(manifest, insertAt, manifest.size - insertAt)
        val res = out.toByteArray()
        ByteBuffer.wrap(res).order(ByteOrder.LITTLE_ENDIAN).putInt(4, res.size)
        return res
    }

    private fun readString(b: ByteArray, at: Int, utf8: Boolean): Pair<ByteArray, String> {
        var p = at
        if (utf8) {
            var chars = b[p++].toInt() and 0xFF
            if (chars and 0x80 != 0) chars = ((chars and 0x7F) shl 8) or (b[p++].toInt() and 0xFF)
            var bytes = b[p++].toInt() and 0xFF
            if (bytes and 0x80 != 0) bytes = ((bytes and 0x7F) shl 8) or (b[p++].toInt() and 0xFF)
            val end = p + bytes + 1
            return b.copyOfRange(at, end) to String(b, p, bytes, Charsets.UTF_8)
        }
        var len = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)
        p += 2
        if (len and 0x8000 != 0) {
            len = ((len and 0x7FFF) shl 16) or (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)
            p += 2
        }
        val end = p + len * 2 + 2
        return b.copyOfRange(at, end) to String(b, p, len * 2, Charsets.UTF_16LE)
    }

    private fun encode(s: String, utf8: Boolean): ByteArray {
        val o = ByteArrayOutputStream()
        if (utf8) {
            val bytes = s.toByteArray(Charsets.UTF_8)
            fun len(n: Int) { if (n > 0x7F) { o.write((n shr 8) or 0x80); o.write(n and 0xFF) } else o.write(n) }
            len(s.length); len(bytes.size); o.write(bytes); o.write(0)
        } else {
            val n = s.length
            if (n > 0x7FFF) { o.write(((n shr 16) or 0x8000) and 0xFF); o.write((((n shr 16) or 0x8000) shr 8) and 0xFF) }
            o.write(n and 0xFF); o.write((n shr 8) and 0xFF)
            o.write(s.toByteArray(Charsets.UTF_16LE)); o.write(0); o.write(0)
        }
        return o.toByteArray()
    }
}
