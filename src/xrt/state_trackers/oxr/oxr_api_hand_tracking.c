// Copyright 2019-2024, Collabora, Ltd.
// Copyright 2025, NVIDIA CORPORATION.
// SPDX-License-Identifier: BSL-1.0
/*!
 * @file
 * @brief  Hand tracking related API functions.
 * @author Christoph Haag <christoph.haag@collabora.com>
 * @author Korcan Hussein <korcan.hussein@collabora.com>
 * @ingroup oxr_api
 */

#include "xrt/xrt_compiler.h"

#include "util/u_debug.h"
#include "util/u_trace_marker.h"

#include "oxr_objects.h"
#include "oxr_logger.h"

#include "oxr_hand_tracking.h"
#include "oxr_api_funcs.h"
#include "oxr_api_verify.h"
#include "oxr_handle_base.h"
#include "oxr_chain.h"


/*
 *
 * XR_EXT_hand_tracking
 *
 */

#ifdef OXR_HAVE_EXT_hand_tracking

XRAPI_ATTR XrResult XRAPI_CALL
oxr_xrCreateHandTrackerEXT(XrSession session,
                           const XrHandTrackerCreateInfoEXT *createInfo,
                           XrHandTrackerEXT *handTracker)
{
	OXR_TRACE_MARKER();

	struct oxr_hand_tracker *hand_tracker = NULL;
	struct oxr_session *sess = NULL;
	struct oxr_logger log;
	XrResult ret;
	OXR_VERIFY_SESSION_AND_INIT_LOG(&log, session, sess, "xrCreateHandTrackerEXT");
	OXR_VERIFY_SESSION_NOT_LOST(&log, sess);
	OXR_VERIFY_ARG_TYPE_AND_NOT_NULL(&log, createInfo, XR_TYPE_HAND_TRACKER_CREATE_INFO_EXT);
	OXR_VERIFY_ARG_NOT_NULL(&log, handTracker);

	OXR_VERIFY_EXTENSION(&log, sess->sys->inst, EXT_hand_tracking);

	if (createInfo->hand != XR_HAND_LEFT_EXT && createInfo->hand != XR_HAND_RIGHT_EXT) {
		return oxr_error(&log, XR_ERROR_VALIDATION_FAILURE, "Invalid hand value %d\n", createInfo->hand);
	}

	if (!oxr_system_get_hand_tracking_support(&log, sess->sys->inst)) {
		return oxr_error(&log, XR_ERROR_FEATURE_UNSUPPORTED, "System does not support hand tracking");
	}

#ifdef OXR_HAVE_EXT_hand_tracking_data_source
	const XrHandTrackingDataSourceInfoEXT *data_source_info = NULL;
	if (sess->sys->inst->extensions.EXT_hand_tracking_data_source) {
		data_source_info = OXR_GET_INPUT_FROM_CHAIN(createInfo, XR_TYPE_HAND_TRACKING_DATA_SOURCE_INFO_EXT,
		                                            XrHandTrackingDataSourceInfoEXT);
	}
	OXR_VERIFY_HAND_TRACKING_DATA_SOURCE_OR_NULL(&log, data_source_info);
#endif

	// Lookup the external xdev if provided via XrCreateHandTrackerXDevMNDX
	struct xrt_device *override_xdev = NULL;
#ifdef OXR_HAVE_MNDX_xdev_space
	const XrCreateHandTrackerXDevMNDX *xdev_info = NULL;
	if (sess->sys->supports_xdev_space && sess->sys->inst->extensions.MNDX_xdev_space) {
		xdev_info = OXR_GET_INPUT_FROM_CHAIN(createInfo, XR_TYPE_CREATE_HAND_TRACKER_XDEV_MNDX,
		                                     XrCreateHandTrackerXDevMNDX);
	}

	if (xdev_info != NULL) {
		struct oxr_xdev_list *xdl = NULL;
		OXR_VERIFY_ARG_TYPE_AND_NOT_NULL(&log, xdev_info, XR_TYPE_CREATE_HAND_TRACKER_XDEV_MNDX);
		OXR_VERIFY_XDEVLIST_NOT_NULL(&log, xdev_info->xdevList, xdl);

		if (!oxr_xdev_list_get_xdev(&log, xdl, xdev_info->id, &override_xdev)) {
			return oxr_error(&log, XR_ERROR_VALIDATION_FAILURE,
			                 "(createInfo->next->id == %" PRIu64 ") not a valid XrXDevIdMNDX",
			                 xdev_info->id);
		}

		if (override_xdev == NULL) {
			return oxr_error(&log, XR_ERROR_RUNTIME_FAILURE, "Got null xrt_device?");
		}

		if (!override_xdev->supported.hand_tracking) {
			return oxr_error(&log, XR_ERROR_FEATURE_UNSUPPORTED,
			                 "(createInfo->next->id == %" PRIu64 ") XDev does not support hand tracking",
			                 xdev_info->id);
		}
	}
#endif

	ret = oxr_hand_tracker_create(&log, sess, createInfo, override_xdev, &hand_tracker);
	if (ret != XR_SUCCESS) {
		return ret;
	}

	*handTracker = oxr_hand_tracker_to_openxr(hand_tracker);

	return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL
oxr_xrDestroyHandTrackerEXT(XrHandTrackerEXT handTracker)
{
	OXR_TRACE_MARKER();

	struct oxr_hand_tracker *hand_tracker;
	struct oxr_logger log;
	OXR_VERIFY_HAND_TRACKER_AND_INIT_LOG(&log, handTracker, hand_tracker, "xrDestroyHandTrackerEXT");

	return oxr_handle_destroy(&log, &hand_tracker->handle);
}

XRAPI_ATTR XrResult XRAPI_CALL
oxr_xrLocateHandJointsEXT(XrHandTrackerEXT handTracker,
                          const XrHandJointsLocateInfoEXT *locateInfo,
                          XrHandJointLocationsEXT *locations)
{
	OXR_TRACE_MARKER();

	struct oxr_hand_tracker *hand_tracker;
	struct oxr_space *spc;
	struct oxr_logger log;
	OXR_VERIFY_HAND_TRACKER_AND_INIT_LOG(&log, handTracker, hand_tracker, "xrLocateHandJointsEXT");
	OXR_VERIFY_SESSION_NOT_LOST(&log, hand_tracker->sess);
	OXR_VERIFY_ARG_TYPE_AND_NOT_NULL(&log, locateInfo, XR_TYPE_HAND_JOINTS_LOCATE_INFO_EXT);
	OXR_VERIFY_ARG_TYPE_AND_NOT_NULL(&log, locations, XR_TYPE_HAND_JOINT_LOCATIONS_EXT);
	OXR_VERIFY_ARG_NOT_NULL(&log, locations->jointLocations);
	OXR_VERIFY_SPACE_NOT_NULL(&log, locateInfo->baseSpace, spc);


	if (locateInfo->time <= (XrTime)0) {
		return oxr_error(&log, XR_ERROR_TIME_INVALID, "(time == %" PRIi64 ") is not a valid time.",
		                 locateInfo->time);
	}

	if (hand_tracker->hand_joint_set == XR_HAND_JOINT_SET_DEFAULT_EXT) {
		if (locations->jointCount != XR_HAND_JOINT_COUNT_EXT) {
			return oxr_error(&log, XR_ERROR_VALIDATION_FAILURE, "joint count must be %d, not %d\n",
			                 XR_HAND_JOINT_COUNT_EXT, locations->jointCount);
		}
	};

	XrHandJointVelocitiesEXT *vel =
	    OXR_GET_OUTPUT_FROM_CHAIN(locations, XR_TYPE_HAND_JOINT_VELOCITIES_EXT, XrHandJointVelocitiesEXT);
	if (vel) {
		if (vel->jointCount <= 0) {
			return oxr_error(&log, XR_ERROR_VALIDATION_FAILURE,
			                 "XrHandJointVelocitiesEXT joint count "
			                 "must be >0, is %d\n",
			                 vel->jointCount);
		}
		if (hand_tracker->hand_joint_set == XR_HAND_JOINT_SET_DEFAULT_EXT) {
			if (vel->jointCount != XR_HAND_JOINT_COUNT_EXT) {
				return oxr_error(&log, XR_ERROR_VALIDATION_FAILURE,
				                 "XrHandJointVelocitiesEXT joint count must "
				                 "be %d, not %d\n",
				                 XR_HAND_JOINT_COUNT_EXT, locations->jointCount);
			}
		}
	}

#ifdef OXR_HAVE_EXT_hand_tracking_data_source
	const XrHandTrackingDataSourceStateEXT *data_source_state = NULL;
	if (hand_tracker->sess->sys->inst->extensions.EXT_hand_tracking_data_source) {
		data_source_state = OXR_GET_OUTPUT_FROM_CHAIN(locations, XR_TYPE_HAND_TRACKING_DATA_SOURCE_STATE_EXT,
		                                              XrHandTrackingDataSourceStateEXT);
	}

	if (data_source_state != NULL) {
		OXR_VERIFY_ARG_TYPE_AND_NOT_NULL(&log, data_source_state, XR_TYPE_HAND_TRACKING_DATA_SOURCE_STATE_EXT);
		if (!hand_tracker->has_requested_sources) {
			return oxr_error(&log, XR_ERROR_VALIDATION_FAILURE,
			                 "XrHandTracker was created without sources.");
		}
	}
#endif


#ifdef OXR_HAVE_FB_hand_tracking_aim
	if (OXR_GET_OUTPUT_FROM_CHAIN(locations, XR_TYPE_HAND_TRACKING_AIM_STATE_FB, XrHandTrackingAimStateFB) != NULL) {
		OXR_VERIFY_EXTENSION(&log, hand_tracker->sess->sys->inst, FB_hand_tracking_aim);
	}
#endif
#ifdef OXR_HAVE_FB_hand_tracking_capsules
	if (OXR_GET_OUTPUT_FROM_CHAIN(locations, XR_TYPE_HAND_TRACKING_CAPSULES_STATE_FB, XrHandTrackingCapsulesStateFB) != NULL) {
		OXR_VERIFY_EXTENSION(&log, hand_tracker->sess->sys->inst, FB_hand_tracking_capsules);
	}
#endif

	return oxr_hand_tracker_joints(&log, hand_tracker, locateInfo, locations);
}

#endif


/*
 *
 * XR_MNDX_force_feedback_curl
 *
 */

#ifdef XR_MNDX_force_feedback_curl

XRAPI_ATTR XrResult XRAPI_CALL
oxr_xrApplyForceFeedbackCurlMNDX(XrHandTrackerEXT handTracker, const XrForceFeedbackCurlApplyLocationsMNDX *locations)
{
	OXR_TRACE_MARKER();

	struct oxr_hand_tracker *hand_tracker;
	struct oxr_logger log;
	OXR_VERIFY_HAND_TRACKER_AND_INIT_LOG(&log, handTracker, hand_tracker, "xrApplyForceFeedbackCurlMNDX");
	OXR_VERIFY_ARG_TYPE_AND_NOT_NULL(&log, locations, XR_TYPE_FORCE_FEEDBACK_CURL_APPLY_LOCATIONS_MNDX);

	return oxr_hand_tracker_apply_force_feedback(&log, hand_tracker, locations);
}

#endif

#ifdef OXR_HAVE_FB_hand_tracking_mesh
/*
 * OLFX: static open-hand bind pose so titles using Meta's OVRSkeleton can build and animate their bones.
 * Joint frames are axis-aligned (-Z toward the fingertips), positions in metres relative to the wrist.
 */
XRAPI_ATTR XrResult XRAPI_CALL
oxr_xrGetHandMeshFB(XrHandTrackerEXT handTracker, XrHandTrackingMeshFB *mesh)
{
	OXR_TRACE_MARKER();

	struct oxr_hand_tracker *hand_tracker;
	struct oxr_logger log;
	OXR_VERIFY_HAND_TRACKER_AND_INIT_LOG(&log, handTracker, hand_tracker, "xrGetHandMeshFB");
	OXR_VERIFY_ARG_TYPE_AND_NOT_NULL(&log, mesh, XR_TYPE_HAND_TRACKING_MESH_FB);

	const float side = hand_tracker->hand == XR_HAND_LEFT_EXT ? -1.0f : 1.0f; // right hand: thumb toward -X
	float p[XR_HAND_JOINT_COUNT_EXT][3] = {{0}};
	int parent[XR_HAND_JOINT_COUNT_EXT];
	float radius[XR_HAND_JOINT_COUNT_EXT];

	parent[XR_HAND_JOINT_PALM_EXT] = XR_HAND_JOINT_PALM_EXT;
	parent[XR_HAND_JOINT_WRIST_EXT] = XR_HAND_JOINT_PALM_EXT;
	p[XR_HAND_JOINT_PALM_EXT][0] = -0.002f;
	p[XR_HAND_JOINT_PALM_EXT][2] = -0.045f;

	static const float thumb[4][3] = {{-0.020f, 0.0f, -0.020f}, {-0.035f, 0.0f, -0.040f},
	                                  {-0.050f, 0.0f, -0.062f}, {-0.062f, 0.0f, -0.082f}};
	for (int i = 0; i < 4; i++) {
		int j = XR_HAND_JOINT_THUMB_METACARPAL_EXT + i;
		parent[j] = i == 0 ? XR_HAND_JOINT_WRIST_EXT : j - 1;
		for (int c = 0; c < 3; c++) p[j][c] = thumb[i][c];
	}

	static const float knuckle_x[4] = {-0.032f, -0.011f, 0.010f, 0.030f};
	static const float knuckle_z[4] = {-0.082f, -0.085f, -0.080f, -0.072f};
	static const float seg[4][3] = {{0.043f, 0.025f, 0.020f}, {0.047f, 0.028f, 0.021f},
	                                {0.043f, 0.026f, 0.020f}, {0.033f, 0.019f, 0.018f}};
	for (int f = 0; f < 4; f++) {
		int base = XR_HAND_JOINT_INDEX_METACARPAL_EXT + f * 5;
		p[base][0] = knuckle_x[f] * 0.25f;
		p[base][2] = knuckle_z[f] * 0.25f;
		parent[base] = XR_HAND_JOINT_WRIST_EXT;
		float z = knuckle_z[f];
		for (int i = 1; i < 5; i++) {
			p[base + i][0] = knuckle_x[f];
			p[base + i][2] = z;
			parent[base + i] = base + i - 1;
			if (i < 4) z -= seg[f][i - 1];
		}
	}

	for (int j = 0; j < XR_HAND_JOINT_COUNT_EXT; j++) {
		radius[j] = 0.010f;
	}
	radius[XR_HAND_JOINT_PALM_EXT] = 0.020f;
	radius[XR_HAND_JOINT_WRIST_EXT] = 0.018f;

	// Minimal one-triangle mesh bound to the wrist, so callers that insist on geometry get something valid.
	static const float tri[3][3] = {{-0.02f, 0.0f, 0.0f}, {0.02f, 0.0f, 0.0f}, {0.0f, 0.0f, -0.04f}};

	mesh->jointCountOutput = XR_HAND_JOINT_COUNT_EXT;
	mesh->vertexCountOutput = 3;
	mesh->indexCountOutput = 3;

	if (mesh->jointCapacityInput != 0) {
		if (mesh->jointCapacityInput < XR_HAND_JOINT_COUNT_EXT) {
			return oxr_error(&log, XR_ERROR_SIZE_INSUFFICIENT, "joint capacity too small");
		}
		for (int j = 0; j < XR_HAND_JOINT_COUNT_EXT; j++) {
			if (mesh->jointBindPoses != NULL) {
				mesh->jointBindPoses[j].orientation = (XrQuaternionf){0.0f, 0.0f, 0.0f, 1.0f};
				mesh->jointBindPoses[j].position = (XrVector3f){p[j][0] * side, p[j][1], p[j][2]};
			}
			if (mesh->jointRadii != NULL) mesh->jointRadii[j] = radius[j];
			if (mesh->jointParents != NULL) mesh->jointParents[j] = (XrHandJointEXT)parent[j];
		}
	}
	if (mesh->vertexCapacityInput != 0) {
		if (mesh->vertexCapacityInput < 3) {
			return oxr_error(&log, XR_ERROR_SIZE_INSUFFICIENT, "vertex capacity too small");
		}
		for (int v = 0; v < 3; v++) {
			if (mesh->vertexPositions != NULL)
				mesh->vertexPositions[v] = (XrVector3f){tri[v][0] * side, tri[v][1], tri[v][2]};
			if (mesh->vertexNormals != NULL) mesh->vertexNormals[v] = (XrVector3f){0.0f, 1.0f, 0.0f};
			if (mesh->vertexUVs != NULL) mesh->vertexUVs[v] = (XrVector2f){0.0f, 0.0f};
			if (mesh->vertexBlendIndices != NULL)
				mesh->vertexBlendIndices[v] = (XrVector4sFB){XR_HAND_JOINT_WRIST_EXT, 0, 0, 0};
			if (mesh->vertexBlendWeights != NULL) mesh->vertexBlendWeights[v] = (XrVector4f){1.0f, 0.0f, 0.0f, 0.0f};
		}
	}
	if (mesh->indexCapacityInput != 0) {
		if (mesh->indexCapacityInput < 3) {
			return oxr_error(&log, XR_ERROR_SIZE_INSUFFICIENT, "index capacity too small");
		}
		if (mesh->indices != NULL) {
			mesh->indices[0] = 0;
			mesh->indices[1] = side > 0 ? 1 : 2;
			mesh->indices[2] = side > 0 ? 2 : 1;
		}
	}
	return XR_SUCCESS;
}
#endif
