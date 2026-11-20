// Copyright 2026, OLFX contributors
// SPDX-License-Identifier: BSL-1.0
package org.olfx.launcher

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/** Private settings routes; OpenXR loader routes are handled by RuntimeBrokerProvider. */
internal enum class SettingsRoute { PROFILE, HANDS, DISPLAY, GAME, CLICK }

/** Pure parser: also covered by JVM tests without an Android device. */
internal object SettingsRoutes {
    fun parse(scheme: String?, authority: String?, expectedAuthority: String, segments: List<String>): SettingsRoute? {
        if (scheme != "content" || authority != expectedAuthority) return null
        return when (segments) {
            listOf("olfx", "profile", "current") -> SettingsRoute.PROFILE
            listOf("olfx", "hands") -> SettingsRoute.HANDS
            listOf("olfx", "display") -> SettingsRoute.DISPLAY
            listOf("olfx", "game", "current") -> SettingsRoute.GAME
            listOf("olfx", "click") -> SettingsRoute.CLICK
            else -> null
        }
    }
}

/** Private IPC between the launcher and our own Monado process. Never exported to games. */
class SettingsProvider : ContentProvider() {
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
        val route = SettingsRoutes.parse(uri.scheme, uri.authority, "${ctx.packageName}.settings", uri.pathSegments)
            ?: return null
        val prefs = Prefs(ctx)
        return when (route) {
            SettingsRoute.PROFILE -> cursor(projection, arrayOf("scale"), mapOf("scale" to prefs.currentScale))
            SettingsRoute.HANDS -> cursor(
                projection, arrayOf("enabled", "swap", "onnx"),
                mapOf(
                    "enabled" to if (prefs.handEnabled) 1 else 0,
                    "swap" to if (prefs.handSwap) 1 else 0,
                    "onnx" to if (prefs.handOnnx) 1 else 0,
                ),
            )
            SettingsRoute.DISPLAY -> cursor(projection, arrayOf("sbs"), mapOf("sbs" to if (prefs.sbsEnabled) 1 else 0))
            SettingsRoute.GAME -> cursor(projection, arrayOf("package"), mapOf("package" to prefs.currentGame))
            SettingsRoute.CLICK -> cursor(projection, arrayOf("mode"), mapOf("mode" to prefs.clickMode))
        }
    }

    private fun cursor(projection: Array<String>?, defaults: Array<String>, values: Map<String, Any>): Cursor {
        val columns = projection ?: defaults
        val row = columns.map { values[it] ?: throw IllegalArgumentException("Unknown column: $it") }
        return MatrixCursor(columns).apply { addRow(row.toTypedArray()) }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0
}
