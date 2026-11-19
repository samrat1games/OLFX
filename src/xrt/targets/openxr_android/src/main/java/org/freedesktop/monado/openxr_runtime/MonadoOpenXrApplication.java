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
    protected void attachBaseContext(android.content.Context base) {
        // Services and application-context messages use the same language as the launcher.
        super.attachBaseContext(org.olfx.launcher.AppLocale.INSTANCE.wrap(base));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // Runtime discovery is cached; package changes must invalidate it (Frameloader-style preflight).
        try {
            android.content.IntentFilter filter = new android.content.IntentFilter();
            filter.addAction(android.content.Intent.ACTION_PACKAGE_ADDED);
            filter.addAction(android.content.Intent.ACTION_PACKAGE_REMOVED);
            filter.addAction(android.content.Intent.ACTION_PACKAGE_CHANGED);
            filter.addAction(android.content.Intent.ACTION_PACKAGE_REPLACED);
            filter.addDataScheme("package");
            registerReceiver(new android.content.BroadcastReceiver() {
                @Override public void onReceive(android.content.Context context, android.content.Intent intent) {
                    org.olfx.launcher.Runtimes.INSTANCE.invalidate();
                }
            }, filter);
        } catch (RuntimeException ignored) {
            // Discovery still expires on its short TTL if registration is unavailable.
        }
    }
}
