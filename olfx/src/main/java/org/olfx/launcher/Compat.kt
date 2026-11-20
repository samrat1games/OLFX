// Copyright 2026, OLFX contributors
// SPDX-License-Identifier: BSL-1.0
package org.olfx.launcher

import android.content.Context
import android.os.Build

/** canRequestPackageInstalls() is API 26; on Android 7 (Oculus Go) the global "unknown sources" setting applies. */
fun Context.canInstallPackages(): Boolean =
    if (Build.VERSION.SDK_INT >= 26) packageManager.canRequestPackageInstalls() else true
