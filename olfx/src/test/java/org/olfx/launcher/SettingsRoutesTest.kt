// Copyright 2026, OLFX contributors
// SPDX-License-Identifier: BSL-1.0
package org.olfx.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsRoutesTest {
    @Test fun privateRoutes() {
        assertEquals(SettingsRoute.DISPLAY, SettingsRoutes.parse("content", "pkg.settings", "pkg.settings", listOf("olfx", "display")))
        assertEquals(SettingsRoute.HANDS, SettingsRoutes.parse("content", "pkg.settings", "pkg.settings", listOf("olfx", "hands")))
        assertEquals(SettingsRoute.GAME, SettingsRoutes.parse("content", "pkg.settings", "pkg.settings", listOf("olfx", "game", "current")))
        assertEquals(SettingsRoute.CLICK, SettingsRoutes.parse("content", "pkg.settings", "pkg.settings", listOf("olfx", "click")))
        assertNull(SettingsRoutes.parse("content", "other", "pkg.settings", listOf("olfx", "display")))
    }

    @Test fun clickModeNormalizes() {
        assertEquals(ClickMode.SCREEN, ClickMode.normalize(null))
        assertEquals(ClickMode.SCREEN, ClickMode.normalize("screen"))
        assertEquals(ClickMode.PINCH, ClickMode.normalize("pinch"))
        assertEquals(ClickMode.SCREEN, ClickMode.normalize("bogus"))
    }

    @Test fun localeTags() {
        assertEquals("pt-BR", AppLocale.normalize("pt-BR"))
        assertEquals("ru", AppLocale.normalize("ru"))
        assertEquals("en", AppLocale.normalize("en"))
        assertEquals(AppLocale.SYSTEM, AppLocale.normalize("unknown"))
    }
}
