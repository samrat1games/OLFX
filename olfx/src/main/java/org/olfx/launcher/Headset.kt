// Copyright 2026, OLFX contributors
// SPDX-License-Identifier: BSL-1.0
package org.olfx.launcher

import android.os.Build

/** Standalone headsets OLFX can run on (as opposed to a phone in a viewer). */
enum class HeadsetKind(val label: String, val sixDof: Boolean) {
    NONE("Phone", false),
    OCULUS_GO("Oculus Go", false),
    PICO_G2("Pico G2", false),
    PICO_NEO3("Pico Neo 3", true),
    PICO_4("Pico 4", true),
}

object Headset {
    /** Case-insensitive match on Build.MANUFACTURER / MODEL / DEVICE (mirrors olfx_device_profile.c). */
    fun identify(manufacturer: String?, model: String?, device: String?): HeadsetKind {
        fun has(s: String?, n: String) = s?.contains(n, ignoreCase = true) == true
        if ((has(manufacturer, "oculus") || has(manufacturer, "facebook")) && (has(model, "pacific") || has(device, "pacific"))) {
            return HeadsetKind.OCULUS_GO
        }
        if (has(model, "oculus go")) return HeadsetKind.OCULUS_GO
        if (has(manufacturer, "pico") || has(manufacturer, "bytedance") || has(model, "pico") || has(device, "pico")) {
            if (has(model, "neo 3") || has(model, "neo3") || has(device, "neo3") || has(device, "phoenix")) return HeadsetKind.PICO_NEO3
            if (has(model, "pico 4") || has(model, "pico4") || has(device, "pico4") || has(device, "sparrow") ||
                has(model, "a8110") || has(model, "a8150")) return HeadsetKind.PICO_4
            if (has(model, "g2") || has(device, "g2") || has(model, "g24k") || has(device, "g24k")) return HeadsetKind.PICO_G2
        }
        return HeadsetKind.NONE
    }

    val current: HeadsetKind by lazy { identify(Build.MANUFACTURER, Build.MODEL, Build.DEVICE) }
    val isHeadset: Boolean get() = current != HeadsetKind.NONE
}
