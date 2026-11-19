// SPDX-License-Identifier: MIT
// Copyright (c) 2026 OLFX contributors
//
// OLFX bridge: OpenXR XR_EXT_hand_tracking -> OVRPlugin HandStateInternal.
// The 512-byte layout matches Unity's OVRPlugin.HandStateInternal (Sequential,
// 24 bone rotations, 5 pinch strengths, 5 finger confidences, two doubles).
// Bone rotations are local relative rotations derived from OpenXR joint poses;
// this is an experimental approximation of Meta's skinned skeleton, not a mesh.
#include "xr_runtime.h"
#include "log.h"
#include <math.h>
#include <stdlib.h>
#include <sys/system_properties.h>
#include <string.h>

typedef struct { float x, y, z, w; } OlfxQuat;
typedef struct { OlfxQuat Orientation; ovrpVector3f Position; } OlfxPose;
typedef struct {
    int32_t Status;                 // 0
    OlfxPose RootPose;              // 4
    OlfxQuat BoneRotations[24];     // 32
    int32_t Pinches;                // 416
    float PinchStrength[5];         // 420
    OlfxPose PointerPose;           // 440
    float HandScale;                // 468
    int32_t HandConfidence;         // 472
    int32_t FingerConfidences[5];   // 476
    double RequestedTimeStamp;      // 496
    double SampleTimeStamp;         // 504
} OlfxHandState;                    // 512
_Static_assert(sizeof(OlfxHandState) == 512, "OVRPlugin HandStateInternal must be 512 bytes");

#define HAND_TRACKED 1
#define INPUT_VALID 2
#define CONFIDENCE_HIGH 0x3f800000

static void hand_state_reset(OlfxHandState *state) {
    memset(state, 0, sizeof(*state));
    state->RootPose.Orientation.w = 1.0f;
    state->PointerPose.Orientation.w = 1.0f;
    state->HandScale = 1.0f;
    for (int i = 0; i < 24; ++i) state->BoneRotations[i].w = 1.0f;
}

static volatile float s_hgrip[2], s_htrig[2];
float xrr_hand_grip(int h) { return (h >= 0 && h < 2) ? s_hgrip[h] : 0.0f; }
float xrr_hand_trigger(int h) { return (h >= 0 && h < 2) ? s_htrig[h] : 0.0f; }

static int s_have_ext;
static int s_have_aim;
static XrHandTrackerEXT s_tracker[2];
static PFN_xrCreateHandTrackerEXT s_create_hand;
static PFN_xrDestroyHandTrackerEXT s_destroy_hand;
static PFN_xrLocateHandJointsEXT s_locate_hand;
static struct {
    volatile unsigned seq;
    OlfxHandState state;
} s_snap[2];

void xrr_hands_set_extensions(int hand_tracking, int aim) {
    s_have_ext = hand_tracking;
    s_have_aim = aim;
}

int xrr_hands_init(void) {
    if (!s_have_ext || g_xr.instance == XR_NULL_HANDLE || g_xr.session == XR_NULL_HANDLE) return 0;
    for (int h = 0; h < 2; ++h) hand_state_reset(&s_snap[h].state);
    // Extension entry points are not exported by every Android loader; resolve them through the instance.
    xrGetInstanceProcAddr(g_xr.instance, "xrCreateHandTrackerEXT", (PFN_xrVoidFunction *)&s_create_hand);
    xrGetInstanceProcAddr(g_xr.instance, "xrDestroyHandTrackerEXT", (PFN_xrVoidFunction *)&s_destroy_hand);
    xrGetInstanceProcAddr(g_xr.instance, "xrLocateHandJointsEXT", (PFN_xrVoidFunction *)&s_locate_hand);
    if (!s_create_hand || !s_destroy_hand || !s_locate_hand) {
        XRRERR("hands: OpenXR hand entry points unavailable");
        return 0;
    }
    for (int h = 0; h < 2; h++) {
        XrHandTrackerCreateInfoEXT ci = { XR_TYPE_HAND_TRACKER_CREATE_INFO_EXT };
        ci.hand = h == 0 ? XR_HAND_LEFT_EXT : XR_HAND_RIGHT_EXT;
        ci.handJointSet = XR_HAND_JOINT_SET_DEFAULT_EXT;
        XrResult r = s_create_hand(g_xr.session, &ci, &s_tracker[h]);
        if (r != XR_SUCCESS) {
            XRRERR("hands: xrCreateHandTrackerEXT(%d) failed: %d", h, r);
            s_tracker[h] = XR_NULL_HANDLE;
        }
    }
    int ok = s_tracker[0] != XR_NULL_HANDLE || s_tracker[1] != XR_NULL_HANDLE;
    XRRLOG("hands: OpenXR trackers L=%d R=%d aim=%d", s_tracker[0] != 0, s_tracker[1] != 0, s_have_aim);
    return ok;
}

void xrr_hands_shutdown(void) {
    for (int h = 0; h < 2; h++) {
        if (s_tracker[h] != XR_NULL_HANDLE && s_destroy_hand) {
            s_destroy_hand(s_tracker[h]);
            s_tracker[h] = XR_NULL_HANDLE;
        }
        __atomic_store_n(&s_snap[h].seq, 1u, __ATOMIC_RELEASE); // odd => invalid/empty
        hand_state_reset(&s_snap[h].state);
        __atomic_store_n(&s_snap[h].seq, 2u, __ATOMIC_RELEASE);
    }
}

static OlfxQuat qmul(OlfxQuat a, OlfxQuat b) {
    return (OlfxQuat){
        a.w*b.x + a.x*b.w + a.y*b.z - a.z*b.y,
        a.w*b.y - a.x*b.z + a.y*b.w + a.z*b.x,
        a.w*b.z + a.x*b.y - a.y*b.x + a.z*b.w,
        a.w*b.w - a.x*b.x - a.y*b.y - a.z*b.z,
    };
}
static OlfxQuat qconj(OlfxQuat q) { return (OlfxQuat){-q.x, -q.y, -q.z, q.w}; }
static OlfxQuat qnorm(OlfxQuat q) {
    float n = sqrtf(q.x*q.x + q.y*q.y + q.z*q.z + q.w*q.w);
    if (!(n > 1e-8f) || !isfinite(n)) return (OlfxQuat){0, 0, 0, 1};
    return (OlfxQuat){q.x/n, q.y/n, q.z/n, q.w/n};
}
static OlfxQuat from_xr(XrQuaternionf q) { return (OlfxQuat){q.x, q.y, q.z, q.w}; }
static float dist(XrVector3f a, XrVector3f b) {
    float dx=a.x-b.x, dy=a.y-b.y, dz=a.z-b.z;
    return sqrtf(dx*dx+dy*dy+dz*dz);
}
static float clamp01(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

// OVRPlugin bone index -> OpenXR joint used for that bone's orientation.
static const XrHandJointEXT kBJoint[24] = {
    XR_HAND_JOINT_WRIST_EXT, XR_HAND_JOINT_WRIST_EXT,
    // OVR has a trapezium bone before the three OpenXR thumb bones. Anchor that
    // extra bone at the wrist instead of duplicating the proximal joint: the
    // latter makes the thumb's proximal rotation permanently identity.
    XR_HAND_JOINT_WRIST_EXT, XR_HAND_JOINT_THUMB_METACARPAL_EXT,
    XR_HAND_JOINT_THUMB_PROXIMAL_EXT, XR_HAND_JOINT_THUMB_DISTAL_EXT,
    XR_HAND_JOINT_INDEX_PROXIMAL_EXT, XR_HAND_JOINT_INDEX_INTERMEDIATE_EXT, XR_HAND_JOINT_INDEX_DISTAL_EXT,
    XR_HAND_JOINT_MIDDLE_PROXIMAL_EXT, XR_HAND_JOINT_MIDDLE_INTERMEDIATE_EXT, XR_HAND_JOINT_MIDDLE_DISTAL_EXT,
    XR_HAND_JOINT_RING_PROXIMAL_EXT, XR_HAND_JOINT_RING_INTERMEDIATE_EXT, XR_HAND_JOINT_RING_DISTAL_EXT,
    XR_HAND_JOINT_LITTLE_METACARPAL_EXT, XR_HAND_JOINT_LITTLE_PROXIMAL_EXT,
    XR_HAND_JOINT_LITTLE_INTERMEDIATE_EXT, XR_HAND_JOINT_LITTLE_DISTAL_EXT,
    XR_HAND_JOINT_THUMB_DISTAL_EXT, XR_HAND_JOINT_INDEX_DISTAL_EXT, XR_HAND_JOINT_MIDDLE_DISTAL_EXT,
    XR_HAND_JOINT_RING_DISTAL_EXT, XR_HAND_JOINT_LITTLE_DISTAL_EXT,
};
static const int kBBoneParent[24] = {
    -1, 0, 0, 2, 3, 4, 0, 6, 7, 0, 9, 10, 0, 12, 13, 0, 15, 16, 17, 5, 8, 11, 14, 18,
};

void xrr_hands_sync(XrTime time) {
    if (g_xr.session == XR_NULL_HANDLE || g_xr.appSpace == XR_NULL_HANDLE || !s_locate_hand) return;
    for (int h = 0; h < 2; h++) {
        if (s_tracker[h] == XR_NULL_HANDLE) continue;
        XrHandJointsLocateInfoEXT li = { XR_TYPE_HAND_JOINTS_LOCATE_INFO_EXT };
        li.baseSpace = g_xr.appSpace;
        li.time = time;
        XrHandJointLocationEXT joints[XR_HAND_JOINT_COUNT_EXT];
        XrHandJointLocationsEXT loc = { XR_TYPE_HAND_JOINT_LOCATIONS_EXT };
        loc.jointCount = XR_HAND_JOINT_COUNT_EXT;
        loc.jointLocations = joints;
        XrHandTrackingAimStateFB aim = { XR_TYPE_HAND_TRACKING_AIM_STATE_FB };
        if (s_have_aim) loc.next = &aim;
        XrResult r = s_locate_hand(s_tracker[h], &li, &loc);
        { static int n[2]; if ((n[h]++ % 90) == 0) XRRLOG("hand sync h=%d r=%d active=%d tr=%p", h, (int)r, (int)loc.isActive, (void*)s_tracker[h]); }
        OlfxHandState st;
        hand_state_reset(&st);
        st.RequestedTimeStamp = (double)time * 1e-9;
        st.SampleTimeStamp = st.RequestedTimeStamp;
        if (r == XR_SUCCESS && loc.isActive) {
            int all_valid = 1;
            for (uint32_t i = 0; i < XR_HAND_JOINT_COUNT_EXT; i++) {
                if ((joints[i].locationFlags & (XR_SPACE_LOCATION_POSITION_VALID_BIT | XR_SPACE_LOCATION_ORIENTATION_VALID_BIT)) !=
                    (XR_SPACE_LOCATION_POSITION_VALID_BIT | XR_SPACE_LOCATION_ORIENTATION_VALID_BIT)) all_valid = 0;
            }
            st.Status = HAND_TRACKED | INPUT_VALID;
            st.HandConfidence = all_valid ? CONFIDENCE_HIGH : 0;
            for (int f = 0; f < 5; f++) st.FingerConfidences[f] = st.HandConfidence;
            const XrHandJointLocationEXT *w = &joints[XR_HAND_JOINT_WRIST_EXT];
            // OpenXR joint frames point -Z along the bone; OVR bone frames point +X (right) / -X (left) along it.
            // Debug: `adb shell setprop debug.olfx.hand N` (0..8) picks the frame fix, debug.olfx.handmirror 0/1 mirrors the left hand.
            const float rs = 0.70710678f;
            static const OlfxQuat fixes[9] = {
                {0, 0, 0, 1}, {0, rs, 0, rs}, {0, -rs, 0, rs}, {0, 0, rs, rs}, {0, 0, -rs, rs},
                {rs, 0, 0, rs}, {-rs, 0, 0, rs}, {0, 1, 0, 0}, {0, 0, 1, 0}};
            char pv[PROP_VALUE_MAX] = {0};
            int mode = __system_property_get("debug.olfx.hand", pv) > 0 ? atoi(pv) : 1;
            if (mode < 0 || mode > 8) mode = 1;
            int mirror = __system_property_get("debug.olfx.handmirror", pv) > 0 ? atoi(pv) : 1;
            OlfxQuat fix = fixes[mode];
            if (h == 0 && mirror) fix = qconj(fix);
            st.RootPose.Orientation = qnorm(qmul(from_xr(w->pose.orientation), fix));
            st.RootPose.Position = (ovrpVector3f){w->pose.position.x, w->pose.position.y, w->pose.position.z};
            st.PointerPose = st.RootPose;
            for (int b = 0; b < 24; b++) {
                OlfxQuat world = qmul(qnorm(from_xr(joints[kBJoint[b]].pose.orientation)), fix);
                int p = kBBoneParent[b];
                OlfxQuat local = p < 0 ? (OlfxQuat){0,0,0,1}
                    : qmul(qconj(qmul(qnorm(from_xr(joints[kBJoint[p]].pose.orientation)), fix)), world);
                st.BoneRotations[b] = qnorm(local);
            }
            {   // Finger curl -> controller-style grip / trigger so hand models driven by Touch grip/trigger curl their fingers.
                float hs = dist(joints[XR_HAND_JOINT_WRIST_EXT].pose.position, joints[XR_HAND_JOINT_MIDDLE_PROXIMAL_EXT].pose.position);
                if (hs > 1e-4f) {
                    const XrHandJointEXT gt[3] = {XR_HAND_JOINT_MIDDLE_TIP_EXT, XR_HAND_JOINT_RING_TIP_EXT, XR_HAND_JOINT_LITTLE_TIP_EXT};
                    float g = 0;
                    for (int i = 0; i < 3; i++)
                        g += clamp01((1.75f - dist(joints[XR_HAND_JOINT_WRIST_EXT].pose.position, joints[gt[i]].pose.position) / hs) / 0.9f);
                    s_hgrip[h] = clamp01(g / 3.0f);
                    s_htrig[h] = clamp01((1.75f - dist(joints[XR_HAND_JOINT_WRIST_EXT].pose.position, joints[XR_HAND_JOINT_INDEX_TIP_EXT].pose.position) / hs) / 0.9f);
                }
            }
            float palm = dist(joints[XR_HAND_JOINT_WRIST_EXT].pose.position,
                              joints[XR_HAND_JOINT_MIDDLE_METACARPAL_EXT].pose.position);
            if (!(palm > 1e-4f)) palm = 0.08f;
            const XrHandJointEXT tips[5] = {
                XR_HAND_JOINT_THUMB_TIP_EXT, XR_HAND_JOINT_INDEX_TIP_EXT, XR_HAND_JOINT_MIDDLE_TIP_EXT,
                XR_HAND_JOINT_RING_TIP_EXT, XR_HAND_JOINT_LITTLE_TIP_EXT,
            };
            // Thumb contact is derived from the other fingers, not thumb-to-itself.
            for (int f = 1; f < 5; f++) {
                float ratio = dist(joints[XR_HAND_JOINT_THUMB_TIP_EXT].pose.position,
                                   joints[tips[f]].pose.position) / palm;
                st.PinchStrength[f] = clamp01((0.46f - ratio) / 0.16f);
                if (ratio <= 0.30f) st.Pinches |= (1 << f);
            }
            for (int f = 1; f < 5; f++)
                if (st.PinchStrength[f] > st.PinchStrength[0]) st.PinchStrength[0] = st.PinchStrength[f];
            if (st.Pinches & ~1) st.Pinches |= 1;
            if (s_have_aim && (aim.status & XR_HAND_TRACKING_AIM_COMPUTED_BIT_FB)) {
                st.PinchStrength[1] = clamp01(aim.pinchStrengthIndex);
                st.PinchStrength[2] = clamp01(aim.pinchStrengthMiddle);
                st.PinchStrength[3] = clamp01(aim.pinchStrengthRing);
                st.PinchStrength[4] = clamp01(aim.pinchStrengthLittle);
                st.Pinches = (st.Pinches & 1)
                    | ((aim.status & XR_HAND_TRACKING_AIM_INDEX_PINCHING_BIT_FB) ? 2 : 0)
                    | ((aim.status & XR_HAND_TRACKING_AIM_MIDDLE_PINCHING_BIT_FB) ? 4 : 0)
                    | ((aim.status & XR_HAND_TRACKING_AIM_RING_PINCHING_BIT_FB) ? 8 : 0)
                    | ((aim.status & XR_HAND_TRACKING_AIM_LITTLE_PINCHING_BIT_FB) ? 16 : 0);
                if (aim.status & XR_HAND_TRACKING_AIM_VALID_BIT_FB) {
                    st.PointerPose.Orientation = from_xr(aim.aimPose.orientation);
                    st.PointerPose.Position = (ovrpVector3f){aim.aimPose.position.x, aim.aimPose.position.y, aim.aimPose.position.z};
                }
                if (aim.status & XR_HAND_TRACKING_AIM_DOMINANT_HAND_BIT_FB) st.Status |= (1 << 7);
                if (aim.status & XR_HAND_TRACKING_AIM_MENU_PRESSED_BIT_FB) st.Status |= (1 << 8);
                if (aim.status & XR_HAND_TRACKING_AIM_SYSTEM_GESTURE_BIT_FB) st.Status |= (1 << 6);
            }
        }
        if (!(r == XR_SUCCESS && loc.isActive)) { s_hgrip[h] = 0; s_htrig[h] = 0; }
        unsigned seq = __atomic_load_n(&s_snap[h].seq, __ATOMIC_RELAXED);
        __atomic_store_n(&s_snap[h].seq, seq + 1, __ATOMIC_RELAXED);
        __atomic_thread_fence(__ATOMIC_RELEASE);
        s_snap[h].state = st;
        __atomic_store_n(&s_snap[h].seq, seq + 2, __ATOMIC_RELEASE);
    }
}

OVRP_EXPORT ovrpResult ovrp_GetHandTrackingEnabled(ovrpBool *enabled) {
    if (!enabled) return ovrpFailure_InvalidParameter;
    *enabled = s_have_ext ? ovrpBool_True : ovrpBool_False;
    return ovrpSuccess;
}

OVRP_EXPORT ovrpResult ovrp_GetHandState(ovrpStep stepId, int hand, OlfxHandState *out) {
    static int seen;
    if (!__atomic_exchange_n(&seen, 1, __ATOMIC_RELAXED)) XRRLOG("OLFX: game requested hand state");
    (void)stepId;
    { static int n[2]; if (hand >= 0 && hand < 2 && (n[hand]++ % 90) == 0) XRRLOG("GetHandState h=%d status=%d b6=%.2f,%.2f,%.2f,%.2f", hand, s_snap[hand].state.Status, s_snap[hand].state.BoneRotations[6].x, s_snap[hand].state.BoneRotations[6].y, s_snap[hand].state.BoneRotations[6].z, s_snap[hand].state.BoneRotations[6].w); }
    if (!out || hand < 0 || hand > 1) return ovrpFailure_InvalidParameter;
    if (!s_have_ext) return ovrpFailure_Unsupported;
    for (;;) {
        unsigned s1 = __atomic_load_n(&s_snap[hand].seq, __ATOMIC_ACQUIRE);
        if (s1 & 1u) continue;
        *out = s_snap[hand].state;
        __atomic_thread_fence(__ATOMIC_ACQUIRE);
        if (__atomic_load_n(&s_snap[hand].seq, __ATOMIC_RELAXED) == s1) break;
    }
    return ovrpSuccess;
}

// Older/unknown revision: do not guess a different struct layout.
OVRP_EXPORT ovrpResult ovrp_GetHandState2(void) { return ovrpFailure_Unsupported; }

// OVRPlugin Skeleton2Internal begins with this stable header and at least 24 hand bones.
// Only the hand prefix is written: newer SDKs append body bones and capsules.
typedef struct {
    int32_t id;
    int16_t parent;
    int16_t padding;
    OlfxPose pose;
} OlfxSkeletonBone;
typedef struct {
    int32_t type;
    uint32_t count;
    uint32_t capsule_count;
    OlfxSkeletonBone bones[24];
} OlfxHandSkeletonPrefix;
_Static_assert(sizeof(OlfxSkeletonBone) == 36, "OVR bone ABI mismatch");

OVRP_EXPORT ovrpResult ovrp_GetSkeleton2(int type, void *out) {
    static int seen;
    if (!__atomic_exchange_n(&seen, 1, __ATOMIC_RELAXED)) XRRLOG("OLFX: game requested hand skeleton");
    if (!out || type < 0 || type > 1) return ovrpFailure_InvalidParameter;
    OlfxHandSkeletonPrefix *s = (OlfxHandSkeletonPrefix *)out;
    memset(s, 0, sizeof(*s));
    s->type = type;
    s->count = 24;
    // Collider capsules are not fabricated. The game can use its own interaction colliders.
    s->capsule_count = 0;
    static const int16_t parents[24] = {
        -1, 0, 0, 2, 3, 4, 0, 6, 7, 0, 9, 10,
        0, 12, 13, 0, 15, 16, 17, 5, 8, 11, 14, 18
    };
    // Approximate neutral bind-pose offsets in metres; directions follow the hand's local frame.
    static const float offsets[24][3] = {
        {0,0,0}, {0,0,0}, {-0.030f,0,-0.020f}, {-0.025f,0,-0.025f},
        {0,0,-0.030f}, {0,0,-0.023f},
        {-0.027f,0,-0.065f}, {0,0,-0.038f}, {0,0,-0.024f},
        {-0.009f,0,-0.071f}, {0,0,-0.043f}, {0,0,-0.026f},
        {0.010f,0,-0.067f}, {0,0,-0.039f}, {0,0,-0.024f},
        {0.027f,0,-0.026f}, {0,0,-0.037f}, {0,0,-0.030f}, {0,0,-0.018f},
        {0,0,-0.022f}, {0,0,-0.019f}, {0,0,-0.020f}, {0,0,-0.020f}, {0,0,-0.017f}
    };
    for (int i = 0; i < 24; ++i) {
        s->bones[i].id = i;
        s->bones[i].parent = parents[i];
        s->bones[i].pose.Orientation.w = 1.0f;
        s->bones[i].pose.Position.x = (type == 1 ? -1.0f : 1.0f) * offsets[i][0];
        s->bones[i].pose.Position.y = offsets[i][1];
        s->bones[i].pose.Position.z = offsets[i][2];
    }
    return ovrpSuccess;
}
