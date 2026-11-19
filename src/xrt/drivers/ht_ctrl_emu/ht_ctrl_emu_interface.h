// Copyright 2021, Collabora, Ltd.
// SPDX-License-Identifier: BSL-1.0
/*!
 * @file
 * @brief  Driver to emulate controllers from hand-tracking input
 * @author Moshi Turner <moshiturner@protonmail.com>>
 *
 * @ingroup drv_cemu
 */

#pragma once
#include "xrt/xrt_device.h"

#ifdef __cplusplus
extern "C" {
#endif

int
cemu_devices_create(struct xrt_device *head, struct xrt_device *hands, struct xrt_device **out_xdevs);

/*!
 * Create one XR_EXT_hand_interaction device per hand from a hand-tracking source.
 * Unlike cemu_devices_create(), the returned devices do NOT own @p hands; the caller keeps the original
 * hand tracker in the system device list. Used by OLFX so controller-only OpenXR titles can bind
 * trigger/squeeze/aim/grip through standard rebinding while XR_EXT_hand_tracking still receives joints.
 */
int
cemu_devices_create_ext_hand_interaction(struct xrt_device *head,
                                         struct xrt_device *hands,
                                         struct xrt_device **out_xdevs);

#ifdef __cplusplus
}
#endif
