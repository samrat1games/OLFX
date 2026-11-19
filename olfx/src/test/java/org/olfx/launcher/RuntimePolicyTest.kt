// Copyright 2026, OLFX contributors
// SPDX-License-Identifier: BSL-1.0
package org.olfx.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimePolicyTest {
    private val monado = RuntimeInfo("org.olfx.launcher", "OLFX", "libopenxr_monado.so", "/data/app/olfx/lib/arm64", Kind.MONADO)
    private val oculus = RuntimeInfo("com.oculus.systemdriver", "OculusXR", "libopenxr_oculus.so", "/data/app/meta/lib/arm64", Kind.OCULUS)

    @Test fun questAutoPrefersOculusXR() {
        assertEquals(oculus, RuntimePolicy.select(listOf(monado, oculus), monado.pkg, true, false, "auto", "auto"))
    }

    @Test fun explicitMonadoOverridesQuestAuto() {
        assertEquals(monado, RuntimePolicy.select(listOf(monado, oculus), monado.pkg, true, false, RuntimeChoice.MONADO, "auto"))
    }

    @Test fun missingExplicitOculusDoesNotSilentlyFallback() {
        assertNull(RuntimePolicy.select(listOf(monado), monado.pkg, true, false, RuntimeChoice.OCULUSXR, "auto"))
    }

    @Test fun autoFallsBackToBuiltInMonado() {
        assertEquals(monado, RuntimePolicy.select(listOf(monado), monado.pkg, true, false, "auto", "auto"))
    }

    @Test fun legacyPackagePinStillWorks() {
        assertEquals(oculus, RuntimePolicy.select(listOf(monado, oculus), monado.pkg, false, false, oculus.pkg, "auto"))
    }
    @Test fun questAutoWithCameraHandsUsesMonado() {
        assertEquals(monado, RuntimePolicy.select(listOf(monado, oculus), monado.pkg, true, false, "auto", "auto", cameraHands = true))
    }

    @Test fun questAutoWithoutCameraHandsKeepsOculus() {
        assertEquals(oculus, RuntimePolicy.select(listOf(monado, oculus), monado.pkg, true, false, "auto", "auto", cameraHands = false))
    }

    @Test fun explicitOculusOverridesCameraHands() {
        assertEquals(oculus, RuntimePolicy.select(listOf(monado, oculus), monado.pkg, true, false, RuntimeChoice.OCULUSXR, "auto", cameraHands = true))
    }

    @Test fun explicitDefaultOculusOverridesCameraHands() {
        assertEquals(oculus, RuntimePolicy.select(listOf(monado, oculus), monado.pkg, true, false, RuntimeChoice.AUTO, RuntimeChoice.OCULUSXR, cameraHands = true))
    }

    @Test fun openxrCompatUsesBuiltInMonado() {
        assertEquals(monado, RuntimePolicy.select(listOf(monado, oculus), monado.pkg, true, false, RuntimeChoice.OPENXR_COMPAT, "auto"))
    }
}
