// Copyright 2020, Collabora, Ltd.
// SPDX-License-Identifier: BSL-1.0
/*!
 * @file
 * @brief  Interface to bootstrap the Monado IPC connection.
 * @author Rylie Pavlik <rylie.pavlik@collabora.com>
 * @ingroup ipc_android
 */

package org.freedesktop.monado.ipc;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.Surface;

interface IMonado {
    /*!
     * Pass one side of the socket pair to the service to set up the IPC.
     */
    void connect(in ParcelFileDescriptor parcelFileDescriptor);

    /*!
     * Provide the surface we inject into the activity, back to the service.
     */
    void passAppSurface(in Surface surface);

    /*!
     * Asking service whether it has the capbility to draw over other apps or not.
     */
    boolean canDrawOverOtherApps();

    /*!
     * OLFX display overlay config for clients that host the Monado surface in their own process.
     * Appended to preserve existing AIDL transaction ids used by the native IPC wrapper.
     */
    Bundle getOlfxDisplayConfig();
}
