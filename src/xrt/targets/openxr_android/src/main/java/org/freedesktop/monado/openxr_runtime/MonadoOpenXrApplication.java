// Copyright 2020, Collabora, Ltd.
// SPDX-License-Identifier: BSL-1.0
/*!
 * @file
 * @brief  Simple Application subclass.
 * @author Rylie Pavlik <rylie.pavlik@collabora.com>
 */
package org.freedesktop.monado.openxr_runtime;

import android.app.Application;
import dagger.hilt.android.HiltAndroidApp;

/** Subclass required for Hilt usage. */
@HiltAndroidApp
public class MonadoOpenXrApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // OLFX (Material You): colours from the wallpaper where the system supports it.
        com.google.android.material.color.DynamicColors.applyToActivitiesIfAvailable(this);
    }
}
