// Copyright 2026, OLFX contributors
// SPDX-License-Identifier: BSL-1.0
/*!
 * @file
 * @brief  OLFX camera hand tracking device.
 *
 * Java side (org.freedesktop.monado.ipc.OlfxHands) runs the camera and the hand landmark model and calls
 * nativeSetHand() with 26 OpenXR joints per hand, expressed in head space (x right, y up, -z forward, metres).
 * This device returns them through xrt_device::get_hand_tracking, transformed by the current head pose.
 *
 * @ingroup drv_android
 */

#include "olfx_hands.h"

#include "math/m_api.h"
#include "os/os_threading.h"
#include "os/os_time.h"
#include "util/u_debug.h"
#include "util/u_device.h"
#include "util/u_logging.h"
#include "util/u_misc.h"

#include <jni.h>
#include <string.h>

#define OLFX_JOINT_FLOATS 8 // px py pz qx qy qz qw radius
#define OLFX_HAND_FLOATS (XRT_HAND_JOINT_COUNT * OLFX_JOINT_FLOATS)
// Data older than this means the hand left the camera view.
#define OLFX_STALE_NS (250 * 1000 * 1000)

struct olfx_hand_data
{
	float joints[OLFX_HAND_FLOATS];
	int64_t timestamp_ns;
	bool active;
};

struct olfx_hands_device
{
	struct xrt_device base;
	struct xrt_device *head;
	struct os_mutex lock;
	struct olfx_hand_data hand[2]; // 0 = left, 1 = right
};

// The JNI entry point has no handle to the device, so keep one global (there is only one per process).
static struct olfx_hands_device *g_hands = NULL;

static inline struct olfx_hands_device *
olfx_hands(struct xrt_device *xdev)
{
	return (struct olfx_hands_device *)xdev;
}

static void
olfx_hands_destroy(struct xrt_device *xdev)
{
	struct olfx_hands_device *d = olfx_hands(xdev);
	if (g_hands == d) {
		g_hands = NULL;
	}
	os_mutex_destroy(&d->lock);
	u_device_free(xdev);
}

static xrt_result_t
olfx_hands_get_tracked_pose(struct xrt_device *xdev,
                            enum xrt_input_name name,
                            int64_t at_timestamp_ns,
                            struct xrt_space_relation *out_relation)
{
	// Only hand-tracking inputs exist, there is no tracked pose.
	U_ZERO(out_relation);
	return XRT_SUCCESS;
}

static xrt_result_t
olfx_hands_get_hand_tracking(struct xrt_device *xdev,
                             enum xrt_input_name name,
                             int64_t desired_timestamp_ns,
                             struct xrt_hand_joint_set *out_value,
                             int64_t *out_timestamp_ns)
{
	struct olfx_hands_device *d = olfx_hands(xdev);

	int idx;
	if (name == XRT_INPUT_HT_UNOBSTRUCTED_LEFT) {
		idx = 0;
	} else if (name == XRT_INPUT_HT_UNOBSTRUCTED_RIGHT) {
		idx = 1;
	} else {
		return XRT_ERROR_INPUT_UNSUPPORTED;
	}

	struct olfx_hand_data data;
	os_mutex_lock(&d->lock);
	data = d->hand[idx];
	os_mutex_unlock(&d->lock);

	int64_t now = (int64_t)os_monotonic_get_ns();
	U_ZERO(out_value);
	*out_timestamp_ns = desired_timestamp_ns;

	if (!data.active || (now - data.timestamp_ns) > OLFX_STALE_NS) {
		out_value->is_active = false;
		return XRT_SUCCESS;
	}

	// Head pose in the shared tracking space (the camera is fixed to the screen, i.e. to the head).
	struct xrt_pose head = XRT_POSE_IDENTITY;
	if (d->head != NULL) {
		struct xrt_space_relation rel;
		U_ZERO(&rel);
		xrt_result_t xret = xrt_device_get_tracked_pose(d->head, XRT_INPUT_GENERIC_HEAD_POSE, now, &rel);
		if (xret == XRT_SUCCESS) {
			if (rel.relation_flags & XRT_SPACE_RELATION_ORIENTATION_VALID_BIT) {
				head.orientation = rel.pose.orientation;
			}
			if (rel.relation_flags & XRT_SPACE_RELATION_POSITION_VALID_BIT) {
				head.position = rel.pose.position;
			}
		}
	}

	const enum xrt_space_relation_flags flags = (enum xrt_space_relation_flags)(
	    XRT_SPACE_RELATION_ORIENTATION_VALID_BIT | XRT_SPACE_RELATION_POSITION_VALID_BIT |
	    XRT_SPACE_RELATION_ORIENTATION_TRACKED_BIT | XRT_SPACE_RELATION_POSITION_TRACKED_BIT);

	for (int i = 0; i < XRT_HAND_JOINT_COUNT; i++) {
		const float *j = &data.joints[i * OLFX_JOINT_FLOATS];
		struct xrt_pose local = {
		    .orientation = {.x = j[3], .y = j[4], .z = j[5], .w = j[6]},
		    .position = {.x = j[0], .y = j[1], .z = j[2]},
		};
		math_quat_normalize(&local.orientation);

		struct xrt_pose world;
		math_pose_transform(&head, &local, &world);

		out_value->values.hand_joint_set_default[i].relation.pose = world;
		out_value->values.hand_joint_set_default[i].relation.relation_flags = flags;
		out_value->values.hand_joint_set_default[i].radius = j[7];
	}

	// The hand pose is the wrist.
	out_value->hand_pose = out_value->values.hand_joint_set_default[XRT_HAND_JOINT_WRIST].relation;
	out_value->is_active = true;
	return XRT_SUCCESS;
}

struct xrt_device *
olfx_hands_device_create(struct xrt_device *head)
{
	struct olfx_hands_device *d = U_DEVICE_ALLOCATE(struct olfx_hands_device, U_DEVICE_ALLOC_TRACKING_NONE, 2, 0);

	d->base.name = XRT_DEVICE_HAND_TRACKER;
	d->base.device_type = XRT_DEVICE_TYPE_HAND_TRACKER;
	d->base.supported.hand_tracking = true;
	d->base.inputs[0].name = XRT_INPUT_HT_UNOBSTRUCTED_LEFT;
	d->base.inputs[1].name = XRT_INPUT_HT_UNOBSTRUCTED_RIGHT;
	// Sets update_inputs (no-op), get_view_poses etc. to safe defaults, then override what we implement.
	u_device_populate_function_pointers(&d->base, olfx_hands_get_tracked_pose, olfx_hands_destroy);
	d->base.get_hand_tracking = olfx_hands_get_hand_tracking;
	snprintf(d->base.str, XRT_DEVICE_NAME_LEN, "OLFX Camera Hands");
	snprintf(d->base.serial, XRT_DEVICE_NAME_LEN, "OLFX Camera Hands");

	d->head = head;
	// Same space as the head, so the transformed joints line up with the headset pose.
	if (head != NULL && head->tracking_origin != NULL) {
		d->base.tracking_origin = head->tracking_origin;
	}

	if (os_mutex_init(&d->lock) != 0) {
		U_LOG_E("OLFX hands: failed to init mutex");
		u_device_free(&d->base);
		return NULL;
	}

	g_hands = d;
	U_LOG_I("OLFX hands: device created");
	return &d->base;
}

/*
 * Called by the Java camera pipeline.
 * hand: 0 = left, 1 = right. joints: 26 * 8 floats. active=false clears the hand.
 */
JNIEXPORT void JNICALL
Java_org_freedesktop_monado_ipc_OlfxHands_nativeSetHand(
    JNIEnv *env, jclass clazz, jint hand, jfloatArray joints, jboolean active)
{
	struct olfx_hands_device *d = g_hands;
	if (d == NULL || hand < 0 || hand > 1) {
		return;
	}

	struct olfx_hand_data tmp;
	memset(&tmp, 0, sizeof(tmp));
	tmp.active = active == JNI_TRUE;
	if (tmp.active) {
		if (joints == NULL || (*env)->GetArrayLength(env, joints) < OLFX_HAND_FLOATS) {
			return;
		}
		(*env)->GetFloatArrayRegion(env, joints, 0, OLFX_HAND_FLOATS, tmp.joints);
	}
	tmp.timestamp_ns = (int64_t)os_monotonic_get_ns();

	os_mutex_lock(&d->lock);
	d->hand[hand] = tmp;
	os_mutex_unlock(&d->lock);
}
