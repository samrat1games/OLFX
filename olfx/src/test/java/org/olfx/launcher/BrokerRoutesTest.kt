// Copyright 2026, OLFX contributors
// SPDX-License-Identifier: BSL-1.0
package org.olfx.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrokerRoutesTest {
    private fun parse(path: String) = BrokerRoutes.parse("content", BrokerRoutes.AUTHORITY, path.trim('/').split('/'))

    @Test fun activeDirectoryAndItem() {
        assertEquals(BrokerRequest("arm64-v8a", null, null), parse("/openxr/1/abi/arm64-v8a/runtimes/active"))
        assertEquals(BrokerRequest("arm64-v8a", null, 0), parse("/openxr/1/abi/arm64-v8a/runtimes/active/0"))
    }

    @Test fun functionsRoute() {
        assertEquals(BrokerRequest("arm64-v8a", "org.olfx.launcher", null), parse("/openxr/1/abi/arm64-v8a/runtimes/org.olfx.launcher/functions"))
    }

    @Test fun rejectsMalformedRoutes() {
        assertNull(parse("/openxr/2/abi/arm64-v8a/runtimes/active"))
        assertNull(parse("/openxr/1/abi/arm64-v8a/runtimes/active/-1"))
        assertNull(parse("/openxr/1/abi//runtimes/active"))
        assertNull(BrokerRoutes.parse("https", BrokerRoutes.AUTHORITY, listOf("openxr", "1", "abi", "arm64-v8a", "runtimes", "active")))
    }
}
