// SPDX-License-Identifier: BSL-1.0
package org.olfx.ctx;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.util.Log;

/**
 * Context handed to Meta's platform loader instead of the raw Activity.
 *
 * On a device without the Quest system packages, Context.createPackageContext("com.oculus.*")
 * throws NameNotFoundException, which the loader treats as fatal (abort). This wrapper answers for
 * those packages with the host application's own context, so the loader reaches its normal
 * "platform not available" error path and returns a result the game can handle.
 *
 * It does NOT report entitlement, ownership or any account state: it only keeps the package lookup
 * from throwing. Everything except com.oculus.* lookups is forwarded untouched.
 *
 * Loaded at runtime from assets/olfx/olfx_ctx.dex by the native platform guard
 * (InMemoryDexClassLoader), so the game's manifest and Application class stay unmodified.
 */
public final class OlfxContext extends ContextWrapper {
    private static final String TAG = "OLFX-CTX";
    private static final String OCULUS_PREFIX = "com.oculus.";

    private OlfxContext(Context base) {
        super(base);
    }

    /** Entry point for the native guard (looked up by name over JNI). */
    public static Context wrap(Context base) {
        return base instanceof OlfxContext ? base : new OlfxContext(base);
    }

    @Override
    public Context createPackageContext(String packageName, int flags)
            throws PackageManager.NameNotFoundException {
        try {
            return super.createPackageContext(packageName, flags);
        } catch (PackageManager.NameNotFoundException e) {
            if (packageName != null && packageName.startsWith(OCULUS_PREFIX)) {
                Log.i(TAG, "createPackageContext(" + packageName + ") -> host context (package not installed)");
                return getBaseContext();
            }
            throw e;
        }
    }

    @Override
    public Context getApplicationContext() {
        Context app = super.getApplicationContext();
        return app == null ? this : wrap(app);
    }
}
