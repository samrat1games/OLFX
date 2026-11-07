// Copyright 2026, OLFX contributors
// SPDX-License-Identifier: BSL-1.0
/*!
 * @file
 * @brief  OLFX camera hand tracking device: joints are produced in Java (camera + MediaPipe) and pushed here.
 * @ingroup drv_android
 */

#pragma once

#include "xrt/xrt_device.h"

#ifdef __cplusplus
extern "C" {
#endif

/*!
 * Create the hand tracking device (exposes XRT_INPUT_HT_UNOBSTRUCTED_LEFT/RIGHT).
 *
 * @param head The HMD device; hand joints are given in head space and are transformed by its pose.
 *
 * @ingroup drv_android
 */
struct xrt_device *
olfx_hands_device_create(struct xrt_device *head);

#ifdef __cplusplus
}
#endif
