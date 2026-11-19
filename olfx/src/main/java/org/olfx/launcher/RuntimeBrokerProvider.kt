// Copyright 2026, OLFX contributors
// SPDX-License-Identifier: BSL-1.0
package org.olfx.launcher

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

internal data class BrokerRequest(val abi: String, val functionsPackage: String?, val row: Long?)

/** Exact Khronos loader routes. Pure Kotlin so the contract can be tested on the JVM. */
internal object BrokerRoutes {
    const val AUTHORITY = "org.khronos.openxr.runtime_broker"

    fun parse(scheme: String?, authority: String?, segments: List<String>): BrokerRequest? {
        if (scheme != "content" || authority != AUTHORITY) return null
        if (segments.size < 6 || segments[0] != "openxr" || segments[1] != "1" ||
            segments[2] != "abi" || segments[3].isEmpty() || segments[4] != "runtimes"
        ) return null
        val abi = segments[3]
        return when {
            segments[5] == "active" && segments.size == 6 -> BrokerRequest(abi, null, null)
            segments[5] == "active" && segments.size == 7 ->
                segments[6].toLongOrNull()?.takeIf { it >= 0 }?.let { BrokerRequest(abi, null, it) }
            segments.size == 7 && segments[6] == "functions" && segments[5].isNotEmpty() ->
                BrokerRequest(abi, segments[5], null)
            segments.size == 8 && segments[6] == "functions" && segments[5].isNotEmpty() ->
                segments[7].toLongOrNull()?.takeIf { it >= 0 }?.let { BrokerRequest(abi, segments[5], it) }
            else -> null
        }
    }
}

/**
 * OLFX's built-in OpenXR runtime broker. Games query this provider; OLFX selects the integrated Monado
 * runtime or an installed vendor runtime such as OculusXR. No broker download/install step is exposed.
 */
class RuntimeBrokerProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? {
        val ctx = context ?: return null
        if (selection != null || selectionArgs != null || sortOrder != null) return null
        val request = BrokerRoutes.parse(uri.scheme, uri.authority, uri.pathSegments) ?: return null
        val caller = callingPackage
        if (caller != null && caller != ctx.packageName) {
            Prefs(ctx).currentGame = caller
        }
        val runtime = Runtimes.resolve(ctx, caller, request.abi) ?: return null

        if (request.functionsPackage != null) {
            if (request.functionsPackage != runtime.pkg || (request.row != null && request.row != 0L)) return null
            return MatrixCursor(projection ?: arrayOf("function_name", "symbol_name"))
        }
        if (request.row != null && request.row != 0L) {
            return MatrixCursor(projection ?: ACTIVE_COLUMNS)
        }
        val values = mapOf<String, Any>(
            "package_name" to runtime.pkg,
            "so_filename" to runtime.so,
            "native_lib_dir" to runtime.nativeLibDir,
            "base_path" to runtime.basePath,
            "has_functions" to 0,
        )
        val columns = projection ?: ACTIVE_COLUMNS
        return MatrixCursor(columns).apply {
            addRow(columns.map { values[it] ?: throw IllegalArgumentException("Unknown column: $it") }.toTypedArray())
        }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0

    private companion object {
        val ACTIVE_COLUMNS = arrayOf("package_name", "so_filename", "native_lib_dir", "base_path", "has_functions")
    }
}
