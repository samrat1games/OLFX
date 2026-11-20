// Copyright 2026, OLFX contributors
// SPDX-License-Identifier: BSL-1.0
package org.olfx.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class HeadsetTest {
    @Test fun identifiesHeadsets() {
        assertEquals(HeadsetKind.OCULUS_GO, Headset.identify("Oculus", "Pacific", "pacific"))
        assertEquals(HeadsetKind.PICO_G2, Headset.identify("Pico Interactive", "Pico G2 4K", "g24k"))
        assertEquals(HeadsetKind.PICO_NEO3, Headset.identify("Pico", "Pico Neo 3", "phoenix"))
        assertEquals(HeadsetKind.PICO_4, Headset.identify("PICO", "A8110", "sparrow"))
        assertEquals(HeadsetKind.NONE, Headset.identify("Oculus", "Quest 2", "hollywood"))
        assertEquals(HeadsetKind.NONE, Headset.identify("samsung", "SM-G991B", "o1s"))
        assertEquals(HeadsetKind.NONE, Headset.identify(null, null, null))
    }

    @Test fun headsetAutoPrefersSystemRuntimeOverBuiltInMonado() {
        val monado = RuntimeInfo("org.olfx.launcher", "OLFX", "libopenxr_monado.so", "/a", Kind.MONADO)
        val pico = RuntimeInfo("com.picovr.runtime", "Pico", "libopenxr_pico.so", "/b", Kind.PICO)
        assertEquals(pico, RuntimePolicy.select(listOf(monado, pico), monado.pkg, false, false, "auto", "auto", headset = true))
        assertEquals(monado, RuntimePolicy.select(listOf(monado, pico), monado.pkg, false, false, "auto", "auto", headset = false))
    }
}
