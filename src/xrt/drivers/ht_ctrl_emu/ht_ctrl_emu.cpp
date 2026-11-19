// Copyright 2021, Collabora, Ltd.
// Copyright 2024-2025, NVIDIA CORPORATION.
// SPDX-License-Identifier: BSL-1.0
/*!
 * @file
 * @brief  Driver to emulate controllers from hand-tracking input
 * @author Moshi Turner <moshiturner@protonmail.com>
 * @author Nick Klingensmith <programmerpichu@gmail.com>
 *
 * @ingroup drv_cemu
 */

#include "xrt/xrt_defines.h"
#include "xrt/xrt_device.h"

#include "os/os_time.h"

#include "math/m_api.h"
#include "math/m_space.h"
#include "math/m_vec3.h"

#include "util/u_var.h"
#include "util/u_time.h"
#include "util/u_misc.h"
#include "util/u_debug.h"
#include "util/u_device.h"
#include "util/u_distortion_mesh.h"
#include "util/u_config_json.h"

#include "ht_ctrl_emu_interface.h"
#include "olfx_phone_controller.hpp"

#include <assert.h>
#include <stdio.h>
#include <cstdint>


static const float cm2m = 0.01f;

DEBUG_GET_ONCE_LOG_OPTION(cemu_log, "CEMU_LOG", U_LOGGING_TRACE)

#define CEMU_TRACE(d, ...) U_LOG_XDEV_IFL_T(&d->base, d->sys->log_level, __VA_ARGS__)
#define CEMU_DEBUG(d, ...) U_LOG_XDEV_IFL_D(&d->base, d->sys->log_level, __VA_ARGS__)
#define CEMU_INFO(d, ...) U_LOG_XDEV_IFL_I(&d->base, d->sys->log_level, __VA_ARGS__)
#define CEMU_WARN(d, ...) U_LOG_XDEV_IFL_W(&d->base, d->sys->log_level, __VA_ARGS__)
#define CEMU_ERROR(d, ...) U_LOG_XDEV_IFL_E(&d->base, d->sys->log_level, __VA_ARGS__)

enum cemu_input_index : std::uint8_t
{
	CEMU_INDEX_HAND_TRACKING,
	CEMU_INDEX_PINCH_BOOL,
	CEMU_INDEX_PINCH_FLOAT,
	CEMU_INDEX_GRIP,
	CEMU_INDEX_AIM,
	CEMU_NUM_INPUTS,

	// XR_EXT_hand_interaction profile (OLFX). Kept after the legacy inputs so existing callers keep
	// the same indices and allocation size.
	CEMU_INDEX_HAND_PINCH_VALUE = CEMU_NUM_INPUTS,
	CEMU_INDEX_HAND_PINCH_READY,
	CEMU_INDEX_HAND_AIM_ACTIVATE_VALUE,
	CEMU_INDEX_HAND_AIM_ACTIVATE_READY,
	CEMU_INDEX_HAND_GRASP_VALUE,
	CEMU_INDEX_HAND_GRASP_READY,
	CEMU_INDEX_HAND_GRIP_POSE,
	CEMU_INDEX_HAND_AIM_POSE,
	CEMU_INDEX_HAND_PINCH_POSE,
	CEMU_INDEX_HAND_POKE_POSE,
	CEMU_INDEX_GENERIC_PALM_POSE,
	CEMU_EXT_NUM_INPUTS,
};

static enum xrt_space_relation_flags valid_flags = (enum xrt_space_relation_flags)(
    XRT_SPACE_RELATION_ORIENTATION_VALID_BIT | XRT_SPACE_RELATION_ORIENTATION_TRACKED_BIT |
    XRT_SPACE_RELATION_POSITION_VALID_BIT | XRT_SPACE_RELATION_POSITION_TRACKED_BIT);

struct cemu_system
{
	// We don't own the head - never free this
	struct xrt_device *in_head;
	// We "own" the hand, and it gets replaced by the out_hands. So once they are both freed we need to free the
	// original hand tracker
	struct xrt_device *in_hand;

	struct cemu_device *out_hand[2];

	float grip_offset_from_palm;

	float waggle, curl, twist;

	enum u_logging_level log_level;

	//! OLFX: expose XR_EXT_hand_interaction and do not destroy the shared source hand tracker.
	bool ext_hand_interaction;
};

struct cemu_device
{
	struct xrt_device base;
	struct cemu_system *sys;

	int hand_index;
	enum xrt_input_name ht_input_name;

	struct xrt_tracking_origin tracking_origin;
};

xrt_quat
wct_to_quat(float waggle, float curl, float twist)
{
	xrt_vec3 waggle_axis = {0, 1, 0};
	xrt_quat just_waggle;
	math_quat_from_angle_vector(waggle, &waggle_axis, &just_waggle);

	xrt_vec3 curl_axis = {1, 0, 0};
	xrt_quat just_curl;
	math_quat_from_angle_vector(curl, &curl_axis, &just_curl);

	xrt_vec3 twist_axis = {0, 0, 1};
	xrt_quat just_twist;
	math_quat_from_angle_vector(twist, &twist_axis, &just_twist);

	xrt_quat out = just_waggle; // Unnecessary but much easier to look at.

	math_quat_rotate(&out, &just_curl, &out);
	math_quat_rotate(&out, &just_twist, &out);
	return out;
}

static inline bool
find_best_ht_input_names(const struct xrt_device *ht_device, enum xrt_input_name ht_input_names[2])
{
	assert(ht_device && ht_device->inputs);

	if (!ht_device->supported.hand_tracking)
		return false;

	constexpr const enum xrt_input_name XRT_NULL_INPUT_NAME = (enum xrt_input_name)0;

	ht_input_names[0] = ht_input_names[1] = XRT_NULL_INPUT_NAME;

	for (uint32_t i = 0; i < ht_device->input_count; ++i) {
		if (ht_device->inputs[i].name == XRT_INPUT_HT_UNOBSTRUCTED_LEFT) {
			ht_input_names[0] = XRT_INPUT_HT_UNOBSTRUCTED_LEFT;
		}

		if (ht_device->inputs[i].name == XRT_INPUT_HT_UNOBSTRUCTED_RIGHT) {
			ht_input_names[1] = XRT_INPUT_HT_UNOBSTRUCTED_RIGHT;
		}
	}

	for (uint32_t i = 0; i < ht_device->input_count; ++i) {
		if (ht_input_names[0] == XRT_NULL_INPUT_NAME &&
		    ht_device->inputs[i].name == XRT_INPUT_HT_CONFORMING_LEFT) {
			ht_input_names[0] = XRT_INPUT_HT_CONFORMING_LEFT;
		}

		if (ht_input_names[1] == XRT_NULL_INPUT_NAME &&
		    ht_device->inputs[i].name == XRT_INPUT_HT_CONFORMING_RIGHT) {
			ht_input_names[1] = XRT_INPUT_HT_CONFORMING_RIGHT;
		}
	}

	return ht_input_names[0] != XRT_NULL_INPUT_NAME && //
	       ht_input_names[1] != XRT_NULL_INPUT_NAME;
}

static inline struct cemu_device *
cemu_device(struct xrt_device *xdev)
{
	return (struct cemu_device *)xdev;
}


static void
cemu_device_destroy(struct xrt_device *xdev)
{
	struct cemu_device *dev = cemu_device(xdev);
	struct cemu_system *system = dev->sys;

	// Remove the variable tracking.
	u_device_free(&system->out_hand[dev->hand_index]->base);

	system->out_hand[dev->hand_index] = NULL;

	if ((system->out_hand[0] == NULL) && (system->out_hand[1] == NULL)) {
		if (!system->ext_hand_interaction) {
			xrt_device_destroy(&system->in_hand);
		}
		u_var_remove_root(system);
		free(system);
	}
}

static xrt_result_t
cemu_device_get_hand_tracking(struct xrt_device *xdev,
                              enum xrt_input_name name,
                              int64_t requested_timestamp_ns,
                              struct xrt_hand_joint_set *out_value,
                              int64_t *out_timestamp_ns)
{
	// Shadows normal hand tracking - does nothing differently

	struct cemu_device *dev = cemu_device(xdev);
	struct cemu_system *system = dev->sys;

	if (name != dev->ht_input_name) {
		U_LOG_XDEV_UNSUPPORTED_INPUT(&dev->base, system->log_level, name);
		return XRT_ERROR_INPUT_UNSUPPORTED;
	}

	return xrt_device_get_hand_tracking(system->in_hand, dev->ht_input_name, requested_timestamp_ns, out_value,
	                                    out_timestamp_ns);
}

static xrt_vec3
joint_position_global(xrt_hand_joint_set *joint_set, xrt_hand_joint joint)
{
	struct xrt_space_relation out_relation;
	struct xrt_relation_chain xrc = {};
	m_relation_chain_push_relation(&xrc, &joint_set->values.hand_joint_set_default[joint].relation);
	m_relation_chain_push_relation(&xrc, &joint_set->hand_pose);
	m_relation_chain_resolve(&xrc, &out_relation);
	return out_relation.pose.position;
}

static xrt_pose
joint_pose_global(xrt_hand_joint_set *joint_set, xrt_hand_joint joint)
{
	struct xrt_space_relation out_relation;
	struct xrt_relation_chain xrc = {};
	m_relation_chain_push_relation(&xrc, &joint_set->values.hand_joint_set_default[joint].relation);
	m_relation_chain_push_relation(&xrc, &joint_set->hand_pose);
	m_relation_chain_resolve(&xrc, &out_relation);
	return out_relation.pose;
}

static xrt_result_t
do_grip_pose(struct xrt_hand_joint_set *joint_set,
             struct xrt_space_relation *out_relation,
             float grip_offset_from_palm,
             bool is_right)
{

	xrt_pose offset_from_palm;
	math_pose_identity(&offset_from_palm);
	offset_from_palm.position.y = -grip_offset_from_palm;
	xrt_pose palm = joint_pose_global(joint_set, XRT_HAND_JOINT_PALM);

	// Position.
	struct xrt_relation_chain xrc = {};
	m_relation_chain_push_pose(&xrc, &offset_from_palm);
	m_relation_chain_push_pose(&xrc, &palm);
	m_relation_chain_resolve(&xrc, out_relation);


	// Orientation.
	xrt_vec3 indx_position = joint_position_global(joint_set, XRT_HAND_JOINT_INDEX_PROXIMAL);
	xrt_vec3 ring_position = joint_position_global(joint_set, XRT_HAND_JOINT_RING_PROXIMAL);
	struct xrt_vec3 plus_z = ring_position - indx_position;
	struct xrt_vec3 plus_x;
	struct xrt_vec3 to_rotate = {0.0f, is_right ? 1.0f : -1.0f, 0.0f};

	math_quat_rotate_vec3(&palm.orientation, &to_rotate, &plus_x);

	plus_x = m_vec3_orthonormalize(plus_z, plus_x);

	math_vec3_normalize(&plus_x);
	math_vec3_normalize(&plus_z);

	math_quat_from_plus_x_z(&plus_x, &plus_z, &out_relation->pose.orientation);

	out_relation->relation_flags = valid_flags;

	return XRT_SUCCESS;
}



static xrt_result_t
get_other_two(struct cemu_device *dev,
              int64_t head_timestamp_ns,
              int64_t hand_timestamp_ns,
              xrt_pose *out_head,
              xrt_hand_joint_set *out_secondary)
{
	struct cemu_system *sys = dev->sys;
	struct xrt_space_relation head_rel;
	xrt_result_t xret =
	    xrt_device_get_tracked_pose(sys->in_head, XRT_INPUT_GENERIC_HEAD_POSE, head_timestamp_ns, &head_rel);
	U_LOG_CHK_AND_RET(sys->log_level, xret, "xrt_device_get_tracked_pose");

	*out_head = head_rel.pose;
	int other;
	if (dev->hand_index == 0) {
		other = 1;
	} else {
		other = 0;
	}

	int64_t noop;
	return xrt_device_get_hand_tracking(sys->in_hand, sys->out_hand[other]->ht_input_name, hand_timestamp_ns,
	                                    out_secondary, &noop);
}

// Mostly stolen from
// https://github.com/maluoi/StereoKit/blob/048b689f71d080a67fde29838c0362a49b88b3d6/StereoKitC/systems/hand/hand_oxr_articulated.cpp#L149
static xrt_result_t
do_aim_pose(struct cemu_device *dev,
            struct xrt_hand_joint_set *joint_set_primary,
            int64_t head_timestamp_ns,
            int64_t hand_timestamp_ns,
            struct xrt_space_relation *out_relation)
{
	struct cemu_system *sys = dev->sys;

	struct xrt_vec3 vec3_up = {0, 1, 0};
	struct xrt_pose head;
	struct xrt_hand_joint_set joint_set_secondary;
#if 0
	// "Jakob way"
	xrt_result_t xret = get_other_two(dev, hand_timestamp_ns, hand_timestamp_ns, &head, &joint_set_secondary);
#else
	// "Moshi way"
	xrt_result_t xret = get_other_two(dev, head_timestamp_ns, hand_timestamp_ns, &head, &joint_set_secondary);
#endif
	U_LOG_CHK_AND_RET(sys->log_level, xret, "get_other_two");

	// Average shoulder width for women:37cm, men:41cm, center of shoulder
	// joint is around 4cm inwards
	const float avg_shoulder_width = ((39.0f / 2.0f) - 4.0f) * cm2m;
	const float head_length = 10 * cm2m;
	const float neck_length = 7 * cm2m;

	// Chest center is down to the base of the head, and then down the neck.
	xrt_vec3 down_the_base_of_head;
	xrt_vec3 base_head_direction = {0, -head_length, 0};

	math_quat_rotate_vec3(&head.orientation, &base_head_direction, &down_the_base_of_head);

	xrt_vec3 chest_center = head.position + down_the_base_of_head + xrt_vec3{0, -neck_length, 0};

	xrt_vec3 face_fwd;
	xrt_vec3 forwards = {0, 0, -1};

	math_quat_rotate_vec3(&head.orientation, &forwards, &face_fwd);

	face_fwd = m_vec3_mul_scalar(m_vec3_normalize(face_fwd), 2);
	face_fwd += m_vec3_mul_scalar(
	    m_vec3_normalize(joint_position_global(joint_set_primary, XRT_HAND_JOINT_WRIST) - chest_center), 1);
	if (joint_set_secondary.is_active) {
		face_fwd += m_vec3_mul_scalar(
		    m_vec3_normalize(joint_position_global(&joint_set_secondary, XRT_HAND_JOINT_WRIST) - chest_center),
		    1);
	}
	face_fwd.y = 0;
	m_vec3_normalize(face_fwd);

	xrt_vec3 face_right;
	math_vec3_cross(&face_fwd, &vec3_up, &face_right);
	math_vec3_normalize(&face_right);
	face_right *= avg_shoulder_width;

	xrt_vec3 shoulder = chest_center + face_right * (dev->hand_index == 1 ? 1.0f : -1.0f);

	xrt_vec3 ray_joint = joint_position_global(joint_set_primary, XRT_HAND_JOINT_INDEX_PROXIMAL);

	struct xrt_vec3 ray_direction = shoulder - ray_joint;

	struct xrt_vec3 up = {0, 1, 0};

	struct xrt_vec3 out_x_vector;

	// math_vec3_normalize(&tip_to_palm);
	math_vec3_normalize(&ray_direction);

	math_vec3_cross(&up, &ray_direction, &out_x_vector);

	out_relation->pose.position = ray_joint;

	math_quat_from_plus_x_z(&out_x_vector, &ray_direction, &out_relation->pose.orientation);

	out_relation->relation_flags = valid_flags;

	return xret;
}

// Pose for controller emulation
static xrt_result_t
cemu_device_get_tracked_pose(struct xrt_device *xdev,
                             enum xrt_input_name name,
                             int64_t at_timestamp_ns,
                             struct xrt_space_relation *out_relation)
{
	struct cemu_device *dev = cemu_device(xdev);
	struct cemu_system *sys = dev->sys;

	const bool legacy_pose =
	    name == XRT_INPUT_HAND_CTRL_EMU_AIM_POSE || name == XRT_INPUT_HAND_CTRL_EMU_GRIP_POSE;
	const bool ext_pose = sys->ext_hand_interaction &&
	                      (name == XRT_INPUT_HAND_AIM_POSE || name == XRT_INPUT_HAND_GRIP_POSE ||
	                       name == XRT_INPUT_HAND_PINCH_POSE || name == XRT_INPUT_HAND_POKE_POSE ||
	                       name == XRT_INPUT_GENERIC_PALM_POSE);
	if (!legacy_pose && !ext_pose) {
		U_LOG_XDEV_UNSUPPORTED_INPUT(&dev->base, dev->sys->log_level, name);
		return XRT_ERROR_INPUT_UNSUPPORTED;
	}
#ifdef __ANDROID__
	olfx_phone_state phone{};
	if (sys->ext_hand_interaction && olfx_phone_get(dev->hand_index, &phone)) {
		struct xrt_space_relation head = {};
		xrt_device_get_tracked_pose(sys->in_head, XRT_INPUT_GENERIC_HEAD_POSE, at_timestamp_ns, &head);
		if (!(head.relation_flags & XRT_SPACE_RELATION_ORIENTATION_VALID_BIT)) {
			out_relation->relation_flags = XRT_SPACE_RELATION_BITMASK_NONE;
			return XRT_SUCCESS;
		}
		struct xrt_pose local = {};
		local.orientation = {phone.x, phone.y, phone.z, phone.w};
		local.position = {dev->hand_index == 0 ? -0.22f : 0.22f, -0.35f, -0.35f};
		math_pose_transform(&head.pose, &local, &out_relation->pose);
		out_relation->relation_flags = (xrt_space_relation_flags)(XRT_SPACE_RELATION_ORIENTATION_VALID_BIT |
		    XRT_SPACE_RELATION_ORIENTATION_TRACKED_BIT | XRT_SPACE_RELATION_POSITION_VALID_BIT);
		return XRT_SUCCESS;
	}
#endif
	int64_t hand_timestamp_ns = 0;

	struct xrt_hand_joint_set joint_set;
	xrt_result_t xret = xrt_device_get_hand_tracking(sys->in_hand, dev->ht_input_name, at_timestamp_ns, &joint_set,
	                                                 &hand_timestamp_ns);
	U_LOG_CHK_AND_RET(sys->log_level, xret, "xrt_device_get_hand_tracking");

	if (joint_set.is_active == false) {
		out_relation->relation_flags = XRT_SPACE_RELATION_BITMASK_NONE;
		return XRT_SUCCESS;
	}

	switch (name) {
	case XRT_INPUT_HAND_CTRL_EMU_GRIP_POSE:
	case XRT_INPUT_HAND_GRIP_POSE: {
		xret = do_grip_pose(&joint_set, out_relation, sys->grip_offset_from_palm, dev->hand_index);
		break;
	}
	case XRT_INPUT_HAND_CTRL_EMU_AIM_POSE:
	case XRT_INPUT_HAND_AIM_POSE: {
		// Assume that now we're doing everything in the timestamp from the hand-tracker, so use
		// hand_timestamp_ns. This will cause the controller to lag behind but otherwise be correct
		xret = do_aim_pose(dev, &joint_set, at_timestamp_ns, hand_timestamp_ns, out_relation);
		break;
	}
	case XRT_INPUT_GENERIC_PALM_POSE: {
		out_relation->pose = joint_pose_global(&joint_set, XRT_HAND_JOINT_PALM);
		out_relation->relation_flags = valid_flags;
		break;
	}
	case XRT_INPUT_HAND_PINCH_POSE: {
		const xrt_vec3 thumb = joint_position_global(&joint_set, XRT_HAND_JOINT_THUMB_TIP);
		const xrt_vec3 index = joint_position_global(&joint_set, XRT_HAND_JOINT_INDEX_TIP);
		out_relation->pose.position = (thumb + index) * 0.5f;
		out_relation->pose.orientation = joint_pose_global(&joint_set, XRT_HAND_JOINT_PALM).orientation;
		out_relation->relation_flags = valid_flags;
		break;
	}
	case XRT_INPUT_HAND_POKE_POSE: {
		const xrt_vec3 tip = joint_position_global(&joint_set, XRT_HAND_JOINT_INDEX_TIP);
		const xrt_vec3 prox = joint_position_global(&joint_set, XRT_HAND_JOINT_INDEX_PROXIMAL);
		xrt_vec3 forward = tip - prox;
		if (m_vec3_len_sqrd(forward) < 1e-8f) {
			forward = {0.0f, 0.0f, -1.0f};
		}
		math_vec3_normalize(&forward);
		xrt_vec3 up = {0.0f, 1.0f, 0.0f};
		xrt_vec3 right;
		math_vec3_cross(&up, &forward, &right);
		if (m_vec3_len_sqrd(right) < 1e-8f) {
			right = {1.0f, 0.0f, 0.0f};
		}
		math_vec3_normalize(&right);
		out_relation->pose.position = tip;
		math_quat_from_plus_x_z(&right, &forward, &out_relation->pose.orientation);
		out_relation->relation_flags = valid_flags;
		break;
	}
	default: assert(false);
	}

	return xret;
}

static float
clamp01(float v)
{
	return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v);
}

//! Heuristic grasp strength from how close the non-index fingertips curl toward the wrist. It is not a
//! calibrated force; it only gives controller-only titles a usable squeeze/grasp channel from camera hands.
static float
hand_grasp_value(struct xrt_hand_joint_set *joint_set)
{
	const xrt_vec3 wrist = joint_position_global(joint_set, XRT_HAND_JOINT_WRIST);
	const xrt_vec3 middle_prox = joint_position_global(joint_set, XRT_HAND_JOINT_MIDDLE_PROXIMAL);
	const float hand_size = m_vec3_len(middle_prox - wrist);
	if (hand_size < 1e-5f) {
		return 0.0f;
	}
	const xrt_hand_joint tips[] = {XRT_HAND_JOINT_MIDDLE_TIP, XRT_HAND_JOINT_RING_TIP, XRT_HAND_JOINT_LITTLE_TIP};
	float sum = 0.0f;
	for (xrt_hand_joint tip : tips) {
		const float ratio = m_vec3_len(joint_position_global(joint_set, tip) - wrist) / hand_size;
		sum += clamp01((1.75f - ratio) / 0.9f);
	}
	return clamp01(sum / 3.0f);
}

//! @todo This is flickery; investigate once we get better hand tracking
static void
decide(xrt_vec3 one, xrt_vec3 two, bool *out)
{
	float dist = m_vec3_len_sqrd(one - two);
	// These used to be 0.02f and 0.04f, but I bumped them way up to compensate for bad tracking. Once our tracking
	// is better, bump these back down.
	float activation_dist = 0.02f;
	float deactivation_dist = 0.04f;
	const float pinch_activation_dist =
	    (*out ? deactivation_dist * deactivation_dist : activation_dist * activation_dist);

	*out = (dist < pinch_activation_dist);
}

/*!
 * EXT hand-interaction pinch uses a palm-relative threshold with hysteresis, matching the launcher’s
 * deliberate-pinch detector. Absolute 2 cm thresholds miss small/far hands and chatter on noisy depth.
 */
static void
decide_ext_pinch(struct xrt_hand_joint_set *joint_set, bool *out)
{
	const xrt_vec3 wrist = joint_position_global(joint_set, XRT_HAND_JOINT_WRIST);
	const xrt_vec3 middle = joint_position_global(joint_set, XRT_HAND_JOINT_MIDDLE_PROXIMAL);
	const float hand_size = m_vec3_len(middle - wrist);
	if (hand_size < 1e-4f) {
		decide(joint_set->values.hand_joint_set_default[XRT_HAND_JOINT_INDEX_TIP].relation.pose.position,
		       joint_set->values.hand_joint_set_default[XRT_HAND_JOINT_THUMB_TIP].relation.pose.position, out);
		return;
	}
	const float ratio =
	    m_vec3_len(joint_position_global(joint_set, XRT_HAND_JOINT_INDEX_TIP) -
	               joint_position_global(joint_set, XRT_HAND_JOINT_THUMB_TIP)) /
	    hand_size;
	const float threshold = *out ? 0.46f : 0.30f;
	*out = ratio <= threshold;
}

static xrt_result_t
cemu_device_update_inputs(struct xrt_device *xdev)
{
	struct cemu_device *dev = cemu_device(xdev);
	struct cemu_system *sys = dev->sys;

#ifdef __ANDROID__
	olfx_phone_state phone{};
	if (sys->ext_hand_interaction && olfx_phone_get(dev->hand_index, &phone)) {
		xdev->inputs[CEMU_INDEX_PINCH_BOOL].value.boolean = phone.trigger > 0.5f;
		xdev->inputs[CEMU_INDEX_PINCH_FLOAT].value.vec1.x = phone.trigger;
		for (uint32_t i = CEMU_NUM_INPUTS; i < CEMU_EXT_NUM_INPUTS; i++) xdev->inputs[i].active = true;
		xdev->inputs[CEMU_INDEX_HAND_PINCH_VALUE].value.vec1.x = phone.trigger;
		xdev->inputs[CEMU_INDEX_HAND_AIM_ACTIVATE_VALUE].value.vec1.x = phone.trigger;
		xdev->inputs[CEMU_INDEX_HAND_GRASP_VALUE].value.vec1.x = phone.grip;
		xdev->inputs[CEMU_INDEX_HAND_PINCH_READY].value.boolean = true;
		xdev->inputs[CEMU_INDEX_HAND_AIM_ACTIVATE_READY].value.boolean = true;
		xdev->inputs[CEMU_INDEX_HAND_GRASP_READY].value.boolean = true;
		return XRT_SUCCESS;
	}
#endif

	struct xrt_hand_joint_set joint_set;
	int64_t noop;

	xrt_result_t xret = xrt_device_get_hand_tracking(dev->sys->in_hand, dev->ht_input_name, os_monotonic_get_ns(),
	                                                 &joint_set, &noop);
	U_LOG_CHK_AND_RET(sys->log_level, xret, "xrt_device_get_hand_tracking");

	if (!joint_set.is_active) {
		xdev->inputs[CEMU_INDEX_PINCH_BOOL].value.boolean = false;
		xdev->inputs[CEMU_INDEX_PINCH_FLOAT].value.vec1.x = 0.f;
		if (sys->ext_hand_interaction) {
			for (uint32_t i = CEMU_NUM_INPUTS; i < CEMU_EXT_NUM_INPUTS; i++) {
				xdev->inputs[i].active = false;
				if (i == CEMU_INDEX_HAND_PINCH_VALUE || i == CEMU_INDEX_HAND_AIM_ACTIVATE_VALUE ||
				    i == CEMU_INDEX_HAND_GRASP_VALUE) {
					xdev->inputs[i].value.vec1.x = 0.0f;
				} else if (i == CEMU_INDEX_HAND_PINCH_READY || i == CEMU_INDEX_HAND_AIM_ACTIVATE_READY ||
				           i == CEMU_INDEX_HAND_GRASP_READY) {
					xdev->inputs[i].value.boolean = false;
				}
			}
		}
		return XRT_SUCCESS;
	}

	if (sys->ext_hand_interaction) {
		decide_ext_pinch(&joint_set, &xdev->inputs[CEMU_INDEX_PINCH_BOOL].value.boolean);
	} else {
		decide(joint_set.values.hand_joint_set_default[XRT_HAND_JOINT_INDEX_TIP].relation.pose.position,
		       joint_set.values.hand_joint_set_default[XRT_HAND_JOINT_THUMB_TIP].relation.pose.position,
		       &xdev->inputs[CEMU_INDEX_PINCH_BOOL].value.boolean);
	}

	// For now, all other inputs are off - detecting any gestures more complicated than pinch is too unreliable for
	// now.
	if (xdev->inputs[CEMU_INDEX_PINCH_BOOL].value.boolean) {
		xdev->inputs[CEMU_INDEX_PINCH_FLOAT].value.vec1.x = 1.0f;
	} else {
		xdev->inputs[CEMU_INDEX_PINCH_FLOAT].value.vec1.x = 0.0f;
	}

	if (sys->ext_hand_interaction) {
		const float pinch = xdev->inputs[CEMU_INDEX_PINCH_FLOAT].value.vec1.x;
		const float grasp = hand_grasp_value(&joint_set);
		xdev->inputs[CEMU_INDEX_HAND_PINCH_VALUE].value.vec1.x = pinch;
		xdev->inputs[CEMU_INDEX_HAND_AIM_ACTIVATE_VALUE].value.vec1.x = pinch;
		xdev->inputs[CEMU_INDEX_HAND_GRASP_VALUE].value.vec1.x = grasp;
		xdev->inputs[CEMU_INDEX_HAND_PINCH_READY].value.boolean = true;
		xdev->inputs[CEMU_INDEX_HAND_AIM_ACTIVATE_READY].value.boolean = true;
		xdev->inputs[CEMU_INDEX_HAND_GRASP_READY].value.boolean = true;
		for (uint32_t i = CEMU_NUM_INPUTS; i < CEMU_EXT_NUM_INPUTS; i++) {
			xdev->inputs[i].active = true;
		}
	}

	return XRT_SUCCESS;
}

static int
cemu_devices_create_impl(struct xrt_device *head,
                         struct xrt_device *hands,
                         struct xrt_device **out_xdevs,
                         bool ext_hand_interaction)
{
	enum xrt_input_name ht_input_names[2];
	if (!find_best_ht_input_names(hands, ht_input_names)) {
		U_LOG_E("\"hands\" parameter is not a hand-tracking device or does have expected inputs.");
		return 0;
	}

	enum u_device_alloc_flags flags = U_DEVICE_ALLOC_NO_FLAGS;

	struct cemu_device *cemud[2];

	struct cemu_system *system = U_TYPED_CALLOC(struct cemu_system);
	if (system == NULL) {
		return 0;
	}

	system->in_hand = hands;
	system->in_head = head;

	system->log_level = debug_get_log_option_cemu_log();
	system->ext_hand_interaction = ext_hand_interaction;

	system->grip_offset_from_palm = 0.03f; // 3 centimeters

	const size_t input_count = ext_hand_interaction ? CEMU_EXT_NUM_INPUTS : CEMU_NUM_INPUTS;
	for (int i = 0; i < 2; i++) {
		cemud[i] = U_DEVICE_ALLOCATE(struct cemu_device, flags, input_count, 0);

		cemud[i]->sys = system;

		cemud[i]->base.tracking_origin = hands->tracking_origin;

		cemud[i]->base.name =
		    ext_hand_interaction ? XRT_DEVICE_EXT_HAND_INTERACTION : XRT_DEVICE_HAND_CTRL_EMU;
		cemud[i]->base.supported.hand_tracking = true;
		cemud[i]->base.supported.orientation_tracking = true;
		cemud[i]->base.supported.position_tracking = true;

		cemud[i]->base.inputs[CEMU_INDEX_HAND_TRACKING].name = ht_input_names[i];
		cemud[i]->base.inputs[CEMU_INDEX_PINCH_BOOL].name = XRT_INPUT_HAND_CTRL_EMU_PINCH_BOOL;
		cemud[i]->base.inputs[CEMU_INDEX_PINCH_FLOAT].name = XRT_INPUT_HAND_CTRL_EMU_PINCH_VALUE;
		cemud[i]->base.inputs[CEMU_INDEX_GRIP].name = XRT_INPUT_HAND_CTRL_EMU_GRIP_POSE;
		cemud[i]->base.inputs[CEMU_INDEX_AIM].name = XRT_INPUT_HAND_CTRL_EMU_AIM_POSE;
		if (ext_hand_interaction) {
			cemud[i]->base.inputs[CEMU_INDEX_HAND_PINCH_VALUE].name = XRT_INPUT_HAND_PINCH_VALUE;
			cemud[i]->base.inputs[CEMU_INDEX_HAND_PINCH_READY].name = XRT_INPUT_HAND_PINCH_READY;
			cemud[i]->base.inputs[CEMU_INDEX_HAND_AIM_ACTIVATE_VALUE].name = XRT_INPUT_HAND_AIM_ACTIVATE_VALUE;
			cemud[i]->base.inputs[CEMU_INDEX_HAND_AIM_ACTIVATE_READY].name = XRT_INPUT_HAND_AIM_ACTIVATE_READY;
			cemud[i]->base.inputs[CEMU_INDEX_HAND_GRASP_VALUE].name = XRT_INPUT_HAND_GRASP_VALUE;
			cemud[i]->base.inputs[CEMU_INDEX_HAND_GRASP_READY].name = XRT_INPUT_HAND_GRASP_READY;
			cemud[i]->base.inputs[CEMU_INDEX_HAND_GRIP_POSE].name = XRT_INPUT_HAND_GRIP_POSE;
			cemud[i]->base.inputs[CEMU_INDEX_HAND_AIM_POSE].name = XRT_INPUT_HAND_AIM_POSE;
			cemud[i]->base.inputs[CEMU_INDEX_HAND_PINCH_POSE].name = XRT_INPUT_HAND_PINCH_POSE;
			cemud[i]->base.inputs[CEMU_INDEX_HAND_POKE_POSE].name = XRT_INPUT_HAND_POKE_POSE;
			cemud[i]->base.inputs[CEMU_INDEX_GENERIC_PALM_POSE].name = XRT_INPUT_GENERIC_PALM_POSE;
		}

		u_device_populate_function_pointers(&cemud[i]->base, cemu_device_get_tracked_pose, cemu_device_destroy);
		cemud[i]->base.update_inputs = cemu_device_update_inputs;
		cemud[i]->base.get_hand_tracking = cemu_device_get_hand_tracking;

		cemud[i]->base.device_type =
		    i ? XRT_DEVICE_TYPE_RIGHT_HAND_CONTROLLER : XRT_DEVICE_TYPE_LEFT_HAND_CONTROLLER;

		int n =
		    snprintf(cemud[i]->base.str, XRT_DEVICE_NAME_LEN, "%s %s Hand", i ? "Right" : "Left", hands->str);
		if (n > XRT_DEVICE_NAME_LEN) {
			CEMU_DEBUG(cemud[i], "name truncated: %s", cemud[i]->base.str);
		}

		n = snprintf(cemud[i]->base.serial, XRT_DEVICE_NAME_LEN, "%s (%d)", hands->str, i);
		if (n > XRT_DEVICE_NAME_LEN) {
			CEMU_WARN(cemud[i], "serial truncated: %s", cemud[i]->base.str);
		}

		cemud[i]->ht_input_name = ht_input_names[i];
		cemud[i]->hand_index = i;
		system->out_hand[i] = cemud[i];

		out_xdevs[i] = &cemud[i]->base;
	}

	u_var_add_root(system, "Controller emulation!", true);
	u_var_add_f32(system, &system->grip_offset_from_palm, "Grip pose offset");

	return 2;

	// We actually don't need these - no failure condition yet. Uncomment whenever you need 'em
	// cleanup:
	// 	cemu_device_destroy(&cemud[0]->base);
	// 	cemu_device_destroy(&cemud[1]->base);
	// 	return 0;
}

extern "C" int
cemu_devices_create(struct xrt_device *head, struct xrt_device *hands, struct xrt_device **out_xdevs)
{
	return cemu_devices_create_impl(head, hands, out_xdevs, false);
}

extern "C" int
cemu_devices_create_ext_hand_interaction(struct xrt_device *head,
                                         struct xrt_device *hands,
                                         struct xrt_device **out_xdevs)
{
	return cemu_devices_create_impl(head, hands, out_xdevs, true);
}
