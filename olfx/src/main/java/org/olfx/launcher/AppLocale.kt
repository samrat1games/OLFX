// Copyright 2026, OLFX contributors
// SPDX-License-Identifier: BSL-1.0
package org.olfx.launcher

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/** Applies the chosen language to activities, services and the application context. */
object AppLocale {
    const val SYSTEM = "system"
    val supported = setOf(SYSTEM, "pt-BR", "ru", "en")

    fun normalize(tag: String?): String = when (tag) {
        "pt-BR", "ru", "en" -> tag
        else -> SYSTEM
    }

    fun locale(tag: String): Locale? = when (normalize(tag)) {
        "pt-BR" -> Locale.forLanguageTag("pt-BR")
        "ru" -> Locale.forLanguageTag("ru")
        "en" -> Locale.forLanguageTag("en")
        else -> null
    }

    fun wrap(context: Context): Context {
        val locale = locale(Prefs(context).language) ?: return context
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLocales(LocaleList(locale))
        configuration.setLayoutDirection(locale)
        return context.createConfigurationContext(configuration)
    }
}
