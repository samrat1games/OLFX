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
#include <math.h>
#include <stdint.h>
#include <string.h>

#define OLFX_JOINT_FLOATS 8 // px py pz qx qy qz qw radius
#define OLFX_HAND_FLOATS (XRT_HAND_JOINT_COUNT * OLFX_JOINT_FLOATS)
// Data older than this means the hand left the camera view.
#define OLFX_STALE_NS (350 * 1000 * 1000)

// Camera exposure + model inference + hand-over: how old the joints already are when they arrive here.
#define OLFX_PIPELINE_LATENCY_NS (45 * 1000 * 1000)
// Never predict further than this.
#define OLFX_MAX_LOOKAHEAD_NS (100 * 1000 * 1000)
#define OLFX_MAX_SPEED 3.0f // m/s

struct olfx_hand_data
{
	float joints[OLFX_HAND_FLOATS];
	float prev_joints[OLFX_HAND_FLOATS];
	int64_t timestamp_ns;
	int64_t prev_timestamp_ns;
	bool active;
	bool has_prev;
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

	if (data.active) {
		// Predict forward: the joints describe the hand some milliseconds ago, the game wants it at display time.
		int64_t target = desired_timestamp_ns > (int64_t)os_monotonic_get_ns() ? desired_timestamp_ns
		                                                                       : (int64_t)os_monotonic_get_ns();
		int64_t look = target - data.timestamp_ns;
		if (look < 0) {
			look = 0;
		}
		if (look > OLFX_MAX_LOOKAHEAD_NS) {
			look = OLFX_MAX_LOOKAHEAD_NS;
		}
		int64_t span = data.timestamp_ns - data.prev_timestamp_ns;
		if (data.has_prev && span >= 8 * 1000 * 1000 && span <= 150 * 1000 * 1000) {
			float span_s = (float)span * 1e-9f;
			float look_s = (float)look * 1e-9f;
			// Gate by wrist speed so a resting hand does not amplify noise.
			float wx = (data.joints[1 * OLFX_JOINT_FLOATS + 0] - data.prev_joints[1 * OLFX_JOINT_FLOATS + 0]) / span_s;
			float wy = (data.joints[1 * OLFX_JOINT_FLOATS + 1] - data.prev_joints[1 * OLFX_JOINT_FLOATS + 1]) / span_s;
			float wz = (data.joints[1 * OLFX_JOINT_FLOATS + 2] - data.prev_joints[1 * OLFX_JOINT_FLOATS + 2]) / span_s;
			float speed = sqrtf(wx * wx + wy * wy + wz * wz);
			float gate = (speed - 0.05f) / 0.10f;
			gate = gate < 0.f ? 0.f : (gate > 1.f ? 1.f : gate);
			if (gate > 0.f) {
				for (int i = 0; i < XRT_HAND_JOINT_COUNT; i++) {
					for (int c = 0; c < 3; c++) {
						int o = i * OLFX_JOINT_FLOATS + c;
						float v = (data.joints[o] - data.prev_joints[o]) / span_s;
						v = v > OLFX_MAX_SPEED ? OLFX_MAX_SPEED : (v < -OLFX_MAX_SPEED ? -OLFX_MAX_SPEED : v);
						data.joints[o] += v * look_s * gate * 0.9f;
					}
				}
			}
		}
	}

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
	tmp.timestamp_ns = (int64_t)os_monotonic_get_ns() - OLFX_PIPELINE_LATENCY_NS;

	os_mutex_lock(&d->lock);
	struct olfx_hand_data *old = &d->hand[hand];
	if (tmp.active && old->active && tmp.timestamp_ns > old->timestamp_ns) {
		memcpy(tmp.prev_joints, old->joints, sizeof(tmp.prev_joints));
		tmp.prev_timestamp_ns = old->timestamp_ns;
		tmp.has_prev = true;
	}
	d->hand[hand] = tmp;
	os_mutex_unlock(&d->lock);
}

/*
 * YUV_420_888 -> RGBA_8888 for the hand tracking input (Java was ~25 ms per frame, this is a few ms).
 * All buffers are direct ByteBuffers.
 */
JNIEXPORT void JNICALL
Java_org_freedesktop_monado_ipc_OlfxHands_nativeYuvToRgba(JNIEnv *env,
                                                           jclass clazz,
                                                           jobject y_buf,
                                                           jobject u_buf,
                                                           jobject v_buf,
                                                           jint y_row,
                                                           jint uv_row,
                                                           jint uv_pix,
                                                           jint w,
                                                           jint h,
                                                           jobject out_buf)
{
	const uint8_t *yp = (const uint8_t *)(*env)->GetDirectBufferAddress(env, y_buf);
	const uint8_t *up = (const uint8_t *)(*env)->GetDirectBufferAddress(env, u_buf);
	const uint8_t *vp = (const uint8_t *)(*env)->GetDirectBufferAddress(env, v_buf);
	uint8_t *dst = (uint8_t *)(*env)->GetDirectBufferAddress(env, out_buf);
	if (yp == NULL || up == NULL || vp == NULL || dst == NULL) {
		return;
	}

	for (int y = 0; y < h; y++) {
		const uint8_t *yr = yp + (size_t)y * (size_t)y_row;
		const uint8_t *ur = up + (size_t)(y >> 1) * (size_t)uv_row;
		const uint8_t *vr = vp + (size_t)(y >> 1) * (size_t)uv_row;
		uint8_t *o = dst + (size_t)y * (size_t)w * 4;
		for (int x = 0; x < w; x++) {
			int Y = yr[x];
			int U = (int)ur[(x >> 1) * uv_pix] - 128;
			int V = (int)vr[(x >> 1) * uv_pix] - 128;
			int r = Y + ((91881 * V) >> 16);
			int g = Y - ((22554 * U + 46802 * V) >> 16);
			int b = Y + ((116130 * U) >> 16);
			o[0] = (uint8_t)(r < 0 ? 0 : (r > 255 ? 255 : r));
			o[1] = (uint8_t)(g < 0 ? 0 : (g > 255 ? 255 : g));
			o[2] = (uint8_t)(b < 0 ? 0 : (b > 255 ? 255 : b));
			o[3] = 255;
			o += 4;
		}
	}
}
