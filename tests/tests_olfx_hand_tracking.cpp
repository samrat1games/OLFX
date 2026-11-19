// Copyright 2026, OLFX contributors
// SPDX-License-Identifier: BSL-1.0
/*!
 * @file
 * @brief Executable delivery test for the native hand-tracking chain used by XR_EXT_hand_tracking.
 *
 * This is deliberately not a string/flag check: it creates system devices and a space overseer,
 * asks the same base hand tracker used by oxr_hand_tracker_create() to locate both hands, and
 * verifies that all 26 joints reach the requested OpenXR base space (and go inactive cleanly).
 */

#include "catch_amalgamated.hpp"

#include "b_space_overseer.h"
#include "b_system_devices.h"
#include "math/m_api.h"
#include "util/u_device.h"
#include "xrt/xrt_space.h"


namespace {

struct TestTrackingOrigin
{
	struct xrt_tracking_origin base;
};

struct TestHead
{
	struct xrt_device base;
	struct xrt_tracking_origin origin;
};

struct TestHands
{
	struct xrt_device base;
	bool active = true;
};

xrt_result_t
head_pose(struct xrt_device *xdev, enum xrt_input_name name, int64_t at_timestamp_ns, xrt_space_relation *out_relation)
{
	(void)xdev;
	(void)name;
	(void)at_timestamp_ns;
	math_pose_identity(&out_relation->pose);
	out_relation->relation_flags = (xrt_space_relation_flags)(XRT_SPACE_RELATION_POSITION_VALID_BIT | XRT_SPACE_RELATION_ORIENTATION_VALID_BIT);
	return XRT_SUCCESS;
}

void
destroy_device(struct xrt_device *xdev)
{
	u_device_free(xdev);
}

xrt_result_t
camera_hand_joints(struct xrt_device *xdev,
                   enum xrt_input_name name,
                   int64_t desired_timestamp_ns,
                   struct xrt_hand_joint_set *out_value,
                   int64_t *out_timestamp_ns)
{
	auto *hands = reinterpret_cast<TestHands *>(xdev);
	REQUIRE((name == XRT_INPUT_HT_UNOBSTRUCTED_LEFT || name == XRT_INPUT_HT_UNOBSTRUCTED_RIGHT));
	*out_value = {};
	out_value->is_active = hands->active;
	math_pose_identity(&out_value->hand_pose.pose);
	out_value->hand_pose.relation_flags =
	    (xrt_space_relation_flags)(XRT_SPACE_RELATION_POSITION_VALID_BIT | XRT_SPACE_RELATION_ORIENTATION_VALID_BIT);
	for (uint32_t i = 0; i < XRT_HAND_JOINT_COUNT; i++) {
		auto &joint = out_value->values.hand_joint_set_default[i];
		math_pose_identity(&joint.relation.pose);
		joint.relation.pose.position = {0.001f * static_cast<float>(i), 0.0f, -0.25f};
		joint.relation.relation_flags =
		    (xrt_space_relation_flags)(XRT_SPACE_RELATION_POSITION_VALID_BIT | XRT_SPACE_RELATION_ORIENTATION_VALID_BIT);
		joint.radius = 0.01f;
	}
	*out_timestamp_ns = desired_timestamp_ns;
	return XRT_SUCCESS;
}

TestHead *
make_head()
{
	auto *head = U_DEVICE_ALLOCATE(TestHead, U_DEVICE_ALLOC_NO_FLAGS, 1, 0);
	head->base.name = XRT_DEVICE_GENERIC_HMD;
	head->base.device_type = XRT_DEVICE_TYPE_HMD;
	head->base.tracking_origin = &head->origin;
	head->origin.type = XRT_TRACKING_TYPE_NONE;
	math_pose_identity(&head->origin.initial_offset);
	head->base.supported.orientation_tracking = true;
	head->base.supported.position_tracking = true;
	head->base.inputs[0].name = XRT_INPUT_GENERIC_HEAD_POSE;
	u_device_populate_function_pointers(&head->base, head_pose, destroy_device);
	return head;
}

TestHands *
make_hands(TestHead *head)
{
	auto *hands = U_DEVICE_ALLOCATE(TestHands, U_DEVICE_ALLOC_NO_FLAGS, 2, 0);
	hands->active = true; // u_device_allocate zeroes storage; C++ default member initializers do not run.
	hands->base.name = XRT_DEVICE_HAND_TRACKER;
	hands->base.device_type = XRT_DEVICE_TYPE_HAND_TRACKER;
	// OLFX publishes joints already in head space and shares the head tracking origin.
	hands->base.tracking_origin = head->base.tracking_origin;
	hands->base.supported.hand_tracking = true;
	hands->base.supported.orientation_tracking = true;
	hands->base.supported.position_tracking = true;
	hands->base.inputs[0].name = XRT_INPUT_HT_UNOBSTRUCTED_LEFT;
	hands->base.inputs[1].name = XRT_INPUT_HT_UNOBSTRUCTED_RIGHT;
	u_device_populate_function_pointers(&hands->base, head_pose, destroy_device);
	hands->base.get_hand_tracking = camera_hand_joints;
	return hands;
}

} // namespace

TEST_CASE("olfx_hand_tracking delivers both hands through the native OpenXR tracker path")
{
	auto *head = make_head();
	auto *hands = make_hands(head);

	auto *sysd = b_system_devices_static_allocate();
	sysd->base.base.static_xdevs[0] = &head->base;
	sysd->base.base.static_xdevs[1] = &hands->base;
	sysd->base.base.static_xdev_count = 2;
	sysd->base.base.static_roles.hand_tracking.unobstructed.left = &hands->base;
	sysd->base.base.static_roles.hand_tracking.unobstructed.right = &hands->base;
	REQUIRE(b_system_devices_static_finalize(sysd, &hands->base, &hands->base, nullptr) == XRT_SUCCESS);

	auto *overseer = b_space_overseer_create(nullptr);
	auto *xso = reinterpret_cast<xrt_space_overseer *>(overseer);
	xrt_pose identity;
	math_pose_identity(&identity);
	b_space_overseer_legacy_setup(overseer, sysd->base.base.static_xdevs, 2, &head->base, &identity, false, false);
	REQUIRE(xso->semantic.local != nullptr);

	for (auto hand : {XRT_HAND_LEFT, XRT_HAND_RIGHT}) {
		xrt_hand_tracker_create_info info = {};
		info.hand = hand;
		info.requested_sources[0] =
		    hand == XRT_HAND_LEFT ? XRT_INPUT_HT_UNOBSTRUCTED_LEFT : XRT_INPUT_HT_UNOBSTRUCTED_RIGHT;
		info.requested_source_count = 1;
		xrt_hand_tracker *tracker = nullptr;
		REQUIRE(xrt_system_devices_create_hand_tracker(&sysd->base.base, &info, &tracker) == XRT_SUCCESS);
		REQUIRE(tracker != nullptr);

		xrt_hand_tracker_location location = {};
		REQUIRE(xrt_hand_tracker_locate(tracker, xso, xso->semantic.local, &identity,
		                                123456789, &location) == XRT_SUCCESS);
		CHECK(location.is_active);
		CHECK(location.source == info.requested_sources[0]);
		for (uint32_t i = 0; i < XRT_HAND_JOINT_COUNT; i++) {
			const auto &joint = location.hand_joint_set.values.hand_joint_set_default[i];
			CHECK((joint.relation.relation_flags & XRT_SPACE_RELATION_POSITION_VALID_BIT) != 0);
			CHECK(joint.relation.pose.position.x == Catch::Approx(0.001f * static_cast<float>(i)));
			CHECK(joint.relation.pose.position.z == Catch::Approx(-0.25f));
			CHECK(joint.radius == Catch::Approx(0.01f));
		}

		hands->active = false;
		xrt_hand_tracker_location inactive = {};
		REQUIRE(xrt_hand_tracker_locate(tracker, xso, xso->semantic.local, &identity,
		                                123456790, &inactive) == XRT_SUCCESS);
		CHECK_FALSE(inactive.is_active);
		hands->active = true;
		xrt_hand_tracker_destroy(&tracker);
	}

	xrt_space_overseer_destroy(&xso);
	sysd->base.base.destroy(&sysd->base.base);
}
