package org.olfx.launcher

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * Implements the Khronos OpenXR runtime-broker protocol:
 *   content://org.khronos.openxr.runtime_broker/openxr/1/abi/<abi>/runtimes/active
 * columns: base_path, so_filename, has_functions
 */
class RuntimeBrokerProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?,
    ): Cursor? {
        val ctx = context ?: return null
        val seg = uri.pathSegments
        // Profile of the game being launched: read by the Monado service at start-up.
        if (seg.size == 3 && seg[0] == "olfx" && seg[1] == "profile" && seg[2] == "current") {
            val c = MatrixCursor(arrayOf("scale"))
            c.addRow(arrayOf<Any>(Prefs(ctx).currentScale))
            return c
        }
        // Hand tracking settings for the Monado service.
        if (seg.size == 2 && seg[0] == "olfx" && seg[1] == "hands") {
            val p = Prefs(ctx)
            val c = MatrixCursor(arrayOf("enabled", "swap"))
            c.addRow(arrayOf<Any>(if (p.handEnabled) 1 else 0, if (p.handSwap) 1 else 0))
            return c
        }
        if (seg.size < 2 || seg[0] != "openxr" || !uri.path.orEmpty().contains("runtimes/active")) return null

        // /functions sub-path: we expose no function remapping.
        if (seg.last() == "functions") {
            return MatrixCursor(arrayOf("function_name", "symbol_name"))
        }
        val cols = arrayOf("package_name", "so_filename", "native_lib_dir", "base_path", "has_functions")
        val c = MatrixCursor(cols)
        Runtimes.resolve(ctx, callingPackage)?.let { c.addRow(arrayOf<Any>(it.pkg, it.so, it.basePath, it.basePath, 0)) }
        return c
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0
}
