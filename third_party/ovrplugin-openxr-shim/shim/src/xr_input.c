/* xr_input.c — OpenXR action-based input mapped to ovrpControllerState4 + hand poses.
 * Bindings target /interaction_profiles/oculus/touch_controller. ovrpButton bitmask
 * values are [VERIFIED from OVR_Plugin_Types.h]. */
#include "xr_runtime.h"
#include "log.h"
#include <string.h>

/* ovrpButton bits */
#define OVRP_BTN_A      0x00000001
#define OVRP_BTN_B      0x00000002
#define OVRP_BTN_X      0x00000100
#define OVRP_BTN_Y      0x00000200
#define OVRP_BTN_START  0x00100000   /* left menu                */
#define OVRP_BTN_LTHUMB 0x00000400
#define OVRP_BTN_RTHUMB 0x00000004

static XrActionSet s_set;
static XrAction a_A, a_B, a_X, a_Y, a_menu, a_lstickc, a_rstickc;
static XrAction a_ltrig, a_rtrig, a_lgrip, a_rgrip, a_lstick, a_rstick;
static XrAction a_lpose, a_rpose;
static XrAction a_lhaptic, a_rhaptic;
static XrSpace  s_lspace, s_rspace;

float xrr_hand_grip(int h);
float xrr_hand_trigger(int h);
void xrr_joy_poll(void);
int xrr_joy_orientation(int role, float q[4]);
int xrr_joy_position(int role, float p[3]);
void xrr_joy_merge(uint32_t *buttons, float *lt, float *rt, float *lg, float *rg, float *lsx, float *lsy, float *rsx, float *rsy);

typedef struct {
    uint32_t buttons;
    float ltrig, rtrig, lgrip, rgrip;
    ovrpVector2f lstick, rstick;
    XrPosef lpose, rpose; int lvalid, rvalid;
} InputState;

/* Published input snapshot. The render thread (xrr_input_sync, inside begin_frame under
 * g_xrlock — single writer) publishes it; the GAME thread reads it (controller state,
 * node validity, hand poses). Same SEQLOCK as the view snapshot in xr_runtime.c: even
 * s_inSeq = stable, odd = writer mid-update; readers retry instead of taking a lock, so a
 * reader never sees a pose from one frame paired with another frame's valid flag. */
static volatile unsigned s_inSeq;
static InputState s_in;

static void input_publish(const InputState *st) {
    unsigned s = s_inSeq;
    __atomic_store_n(&s_inSeq, s + 1, __ATOMIC_RELAXED);   /* odd: write in progress */
    __atomic_thread_fence(__ATOMIC_RELEASE);               /* odd-mark visible before data */
    s_in = *st;
    __atomic_store_n(&s_inSeq, s + 2, __ATOMIC_RELEASE);   /* even: stable */
}

static void input_snapshot(InputState *out) {
    for (;;) {
        unsigned s1 = __atomic_load_n(&s_inSeq, __ATOMIC_ACQUIRE);
        if (s1 & 1u) continue;                             /* writer mid-update -> retry */
        *out = s_in;
        __atomic_thread_fence(__ATOMIC_ACQUIRE);           /* data reads complete before re-check */
        if (__atomic_load_n(&s_inSeq, __ATOMIC_RELAXED) == s1) return;
    }
}

/* A hand pose is usable only if the runtime vouches for BOTH position and orientation
 * this frame; anything less (controller off, out of tracking) reports untracked so the
 * game hides the hand instead of freezing it at the last pose. */
#define POSE_VALID_BITS (XR_SPACE_LOCATION_ORIENTATION_VALID_BIT | XR_SPACE_LOCATION_POSITION_VALID_BIT)

static XrAction mk(XrActionType t, const char *n) {
    XrActionCreateInfo ci = { XR_TYPE_ACTION_CREATE_INFO };
    ci.actionType = t;
    strncpy(ci.actionName, n, XR_MAX_ACTION_NAME_SIZE - 1);
    strncpy(ci.localizedActionName, n, XR_MAX_LOCALIZED_ACTION_NAME_SIZE - 1);
    XrAction a = XR_NULL_HANDLE;
    xrCreateAction(s_set, &ci, &a);
    return a;
}

int xrr_input_init(void) {
    if (g_xr.instance == XR_NULL_HANDLE || g_xr.session == XR_NULL_HANDLE) return 0;
    XrActionSetCreateInfo asci = { XR_TYPE_ACTION_SET_CREATE_INFO };
    strcpy(asci.actionSetName, "gameplay");
    strcpy(asci.localizedActionSetName, "gameplay");
    if (xrCreateActionSet(g_xr.instance, &asci, &s_set) != XR_SUCCESS) return 0;

    a_A = mk(XR_ACTION_TYPE_BOOLEAN_INPUT, "a_btn");
    a_B = mk(XR_ACTION_TYPE_BOOLEAN_INPUT, "b_btn");
    a_X = mk(XR_ACTION_TYPE_BOOLEAN_INPUT, "x_btn");
    a_Y = mk(XR_ACTION_TYPE_BOOLEAN_INPUT, "y_btn");
    a_menu = mk(XR_ACTION_TYPE_BOOLEAN_INPUT, "menu_btn");
    a_lstickc = mk(XR_ACTION_TYPE_BOOLEAN_INPUT, "lstick_click");
    a_rstickc = mk(XR_ACTION_TYPE_BOOLEAN_INPUT, "rstick_click");
    a_ltrig = mk(XR_ACTION_TYPE_FLOAT_INPUT, "ltrigger");
    a_rtrig = mk(XR_ACTION_TYPE_FLOAT_INPUT, "rtrigger");
    a_lgrip = mk(XR_ACTION_TYPE_FLOAT_INPUT, "lgrip");
    a_rgrip = mk(XR_ACTION_TYPE_FLOAT_INPUT, "rgrip");
    a_lstick = mk(XR_ACTION_TYPE_VECTOR2F_INPUT, "lstick");
    a_rstick = mk(XR_ACTION_TYPE_VECTOR2F_INPUT, "rstick");
    a_lpose = mk(XR_ACTION_TYPE_POSE_INPUT, "lpose");
    a_rpose = mk(XR_ACTION_TYPE_POSE_INPUT, "rpose");
    a_lhaptic = mk(XR_ACTION_TYPE_VIBRATION_OUTPUT, "lhaptic");
    a_rhaptic = mk(XR_ACTION_TYPE_VIBRATION_OUTPUT, "rhaptic");

    XrActionSuggestedBinding b[32]; int n = 0; XrPath p;
#define BIND(act, path) do { if (xrStringToPath(g_xr.instance, path, &p) == XR_SUCCESS) \
    { b[n].action = (act); b[n].binding = p; n++; } } while (0)
    BIND(a_A, "/user/hand/right/input/a/click");
    BIND(a_B, "/user/hand/right/input/b/click");
    BIND(a_X, "/user/hand/left/input/x/click");
    BIND(a_Y, "/user/hand/left/input/y/click");
    BIND(a_menu, "/user/hand/left/input/menu/click");
    BIND(a_lstickc, "/user/hand/left/input/thumbstick/click");
    BIND(a_rstickc, "/user/hand/right/input/thumbstick/click");
    BIND(a_ltrig, "/user/hand/left/input/trigger/value");
    BIND(a_rtrig, "/user/hand/right/input/trigger/value");
    BIND(a_lgrip, "/user/hand/left/input/squeeze/value");
    BIND(a_rgrip, "/user/hand/right/input/squeeze/value");
    BIND(a_lstick, "/user/hand/left/input/thumbstick");
    BIND(a_rstick, "/user/hand/right/input/thumbstick");
    BIND(a_lpose, "/user/hand/left/input/aim/pose");
    BIND(a_rpose, "/user/hand/right/input/aim/pose");
    BIND(a_lhaptic, "/user/hand/left/output/haptic");
    BIND(a_rhaptic, "/user/hand/right/output/haptic");

    XrPath profile;
    xrStringToPath(g_xr.instance, "/interaction_profiles/oculus/touch_controller", &profile);
    XrInteractionProfileSuggestedBinding sb = { XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING };
    sb.interactionProfile = profile;
    sb.suggestedBindings = b;
    sb.countSuggestedBindings = n;
    if (xrSuggestInteractionProfileBindings(g_xr.instance, &sb) != XR_SUCCESS)
        XRRERR("suggest bindings failed");

    XrActionSpaceCreateInfo spci = { XR_TYPE_ACTION_SPACE_CREATE_INFO };
    spci.poseInActionSpace.orientation.w = 1.0f;
    spci.action = a_lpose; xrCreateActionSpace(g_xr.session, &spci, &s_lspace);
    spci.action = a_rpose; xrCreateActionSpace(g_xr.session, &spci, &s_rspace);

    XrSessionActionSetsAttachInfo at = { XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO };
    at.countActionSets = 1; at.actionSets = &s_set;
    if (xrAttachSessionActionSets(g_xr.session, &at) != XR_SUCCESS) {
        XRRERR("attach action sets failed"); return 0;
    }
    XRRLOG("input: action set attached (%d bindings)", n);
    return 1;
}

static int bget(XrAction a) {
    XrActionStateGetInfo gi = { XR_TYPE_ACTION_STATE_GET_INFO }; gi.action = a;
    XrActionStateBoolean st = { XR_TYPE_ACTION_STATE_BOOLEAN };
    xrGetActionStateBoolean(g_xr.session, &gi, &st);
    return st.isActive && st.currentState;
}
static float fget(XrAction a) {
    XrActionStateGetInfo gi = { XR_TYPE_ACTION_STATE_GET_INFO }; gi.action = a;
    XrActionStateFloat st = { XR_TYPE_ACTION_STATE_FLOAT };
    xrGetActionStateFloat(g_xr.session, &gi, &st);
    return st.isActive ? st.currentState : 0.0f;
}
static ovrpVector2f v2get(XrAction a) {
    XrActionStateGetInfo gi = { XR_TYPE_ACTION_STATE_GET_INFO }; gi.action = a;
    XrActionStateVector2f st = { XR_TYPE_ACTION_STATE_VECTOR2F };
    xrGetActionStateVector2f(g_xr.session, &gi, &st);
    ovrpVector2f v = { 0, 0 };
    if (st.isActive) { v.x = st.currentState.x; v.y = st.currentState.y; }
    return v;
}

void xrr_input_sync(void) {
    if (s_set == XR_NULL_HANDLE || !g_xr.running) return;
    XrActiveActionSet aas = { s_set, XR_NULL_PATH };
    XrActionsSyncInfo si = { XR_TYPE_ACTIONS_SYNC_INFO };
    si.countActiveActionSets = 1; si.activeActionSets = &aas;
    XrResult sr = xrSyncActions(g_xr.session, &si);
    static int dbg = 0;
    if (sr != XR_SUCCESS && dbg < 3) { dbg++; XRRLOG("xrSyncActions rc=%d", (int)sr); }
    if (XR_FAILED(sr)) return;

    InputState st;
    input_snapshot(&st);   /* start from the last published state (keeps last poses) */
    uint32_t btn = 0;
    if (bget(a_A)) btn |= OVRP_BTN_A;
    if (bget(a_B)) btn |= OVRP_BTN_B;
    if (bget(a_X)) btn |= OVRP_BTN_X;
    if (bget(a_Y)) btn |= OVRP_BTN_Y;
    if (bget(a_menu)) btn |= OVRP_BTN_START;
    if (bget(a_lstickc)) btn |= OVRP_BTN_LTHUMB;
    if (bget(a_rstickc)) btn |= OVRP_BTN_RTHUMB;
    st.buttons = btn;
    st.ltrig = fget(a_ltrig);  st.rtrig = fget(a_rtrig);
    st.lgrip = fget(a_lgrip);  st.rgrip = fget(a_rgrip);
    st.lstick = v2get(a_lstick); st.rstick = v2get(a_rstick);
    if (xrr_hand_grip(0) > st.lgrip) st.lgrip = xrr_hand_grip(0);
    if (xrr_hand_grip(1) > st.rgrip) st.rgrip = xrr_hand_grip(1);
    if (xrr_hand_trigger(0) > st.ltrig) st.ltrig = xrr_hand_trigger(0);
    if (xrr_hand_trigger(1) > st.rtrig) st.rtrig = xrr_hand_trigger(1);
    xrr_joy_poll();
    xrr_joy_merge(&st.buttons, &st.ltrig, &st.rtrig, &st.lgrip, &st.rgrip, &st.lstick.x, &st.lstick.y, &st.rstick.x, &st.rstick.y);

    /* validity is re-derived EVERY sync, so lost tracking drops back to untracked */
    XrSpaceLocation loc = { XR_TYPE_SPACE_LOCATION };
    XrResult lr = xrLocateSpace(s_lspace, g_xr.appSpace, g_xr.frameState.predictedDisplayTime, &loc);
    st.lvalid = (lr == XR_SUCCESS && (loc.locationFlags & POSE_VALID_BITS) == POSE_VALID_BITS);
    if (st.lvalid) st.lpose = loc.pose;
    XrSpaceLocation rloc = { XR_TYPE_SPACE_LOCATION };
    XrResult rr = xrLocateSpace(s_rspace, g_xr.appSpace, g_xr.frameState.predictedDisplayTime, &rloc);
    st.rvalid = (rr == XR_SUCCESS && (rloc.locationFlags & POSE_VALID_BITS) == POSE_VALID_BITS);
    if (st.rvalid) st.rpose = rloc.pose;
    {   /* OLFX: Joy-Con IMU orientation stands in for a controller whose hand is not camera-tracked */
        float jq[4];
        if (!st.lvalid && xrr_joy_orientation(1, jq)) {
            st.lpose.orientation.w = jq[0]; st.lpose.orientation.x = jq[1]; st.lpose.orientation.y = jq[2]; st.lpose.orientation.z = jq[3];
            float jp[3];
            if (xrr_joy_position(1, jp)) { st.lpose.position.x = jp[0]; st.lpose.position.y = jp[1]; st.lpose.position.z = jp[2]; }
            else { st.lpose.position.x = -0.20f; st.lpose.position.y = -0.30f; st.lpose.position.z = -0.40f; }
            st.lvalid = 1;
        }
        if (!st.rvalid && (xrr_joy_orientation(2, jq) || xrr_joy_orientation(0, jq))) {
            st.rpose.orientation.w = jq[0]; st.rpose.orientation.x = jq[1]; st.rpose.orientation.y = jq[2]; st.rpose.orientation.z = jq[3];
            float jp[3];
            if (xrr_joy_position(xrr_joy_orientation(2, jq) ? 2 : 0, jp)) { st.rpose.position.x = jp[0]; st.rpose.position.y = jp[1]; st.rpose.position.z = jp[2]; }
            else { st.rpose.position.x = 0.20f; st.rpose.position.y = -0.30f; st.rpose.position.z = -0.40f; }
            st.rvalid = 1;
        }
    }
    input_publish(&st);
    {   /* DIAG: log each hand's tracked<->untracked transition (capped) */
        static int plv = -1, prv = -1, tl = 0;
        if ((st.lvalid != plv || st.rvalid != prv) && tl++ < 40)
            XRRLOG("input: hand valid L=%d (flags=0x%x) R=%d (flags=0x%x)",
                   st.lvalid, (unsigned)loc.locationFlags, st.rvalid, (unsigned)rloc.locationFlags);
        plv = st.lvalid; prv = st.rvalid;
    }
    static int pd = 0;
    if (pd < 4) { pd++;
        XRRLOG("input poses: L rc=%d flags=0x%x pos=(%.2f,%.2f,%.2f) | R rc=%d flags=0x%x pos=(%.2f,%.2f,%.2f)",
               (int)lr, (unsigned)loc.locationFlags, loc.pose.position.x, loc.pose.position.y, loc.pose.position.z,
               (int)rr, (unsigned)rloc.locationFlags, rloc.pose.position.x, rloc.pose.position.y, rloc.pose.position.z);
    }
}

/* ovrp_SetControllerVibration2(mask, freq, amplitude) -> xrApplyHapticFeedback.
 * amplitude 0 stops; >0 starts a short pulse (game re-issues for sustained rumble). */
void xrr_set_vibration(unsigned int mask, float frequency, float amplitude) {
    if (s_set == XR_NULL_HANDLE || !g_xr.running) return;
    XrHapticVibration hv = { XR_TYPE_HAPTIC_VIBRATION };
    hv.amplitude = amplitude < 0 ? 0 : (amplitude > 1 ? 1 : amplitude);
    hv.frequency = frequency > 0.0f ? frequency : XR_FREQUENCY_UNSPECIFIED;
    hv.duration  = 300000000;   /* 0.3 s; replaced/stopped by the next call */
    XrHapticActionInfo hai = { XR_TYPE_HAPTIC_ACTION_INFO };
    for (int side = 0; side < 2; side++) {
        if (!(mask & (side == 0 ? 0x01u : 0x02u))) continue;
        hai.action = side == 0 ? a_lhaptic : a_rhaptic;
        if (amplitude > 0.0f)
            xrApplyHapticFeedback(g_xr.session, &hai, (const XrHapticBaseHeader *)&hv);
        else
            xrStopHapticFeedback(g_xr.session, &hai);
    }
}

void xrr_get_controller_state(unsigned int mask, ovrpControllerState4 *out) {
    (void)mask;
    InputState st; input_snapshot(&st);
    memset(out, 0, sizeof(*out));
    out->ConnectedControllers = 0x01 | 0x02 | 0x80000000u;   /* LTouch|RTouch|Active */
    out->Buttons       = st.buttons;
    out->LIndexTrigger = st.ltrig; out->RIndexTrigger = st.rtrig;
    out->LHandTrigger  = st.lgrip; out->RHandTrigger  = st.rgrip;
    out->LThumbstick   = st.lstick; out->RThumbstick  = st.rstick;
}

/* node presence/validity — the game gates pose queries on these. Reporting the
 * controllers as present+tracked is what makes it actually ASK for hand poses. */
int xrr_node_present(int node) {
    switch (node) {
        case ovrpNode_Head: case ovrpNode_EyeLeft: case ovrpNode_EyeRight:
        case ovrpNode_EyeCenter: case ovrpNode_HandLeft: case ovrpNode_HandRight:
            return 1;
        default: return 0;
    }
}
int xrr_node_valid(int node) {
    switch (node) {
        case ovrpNode_Head: case ovrpNode_EyeLeft: case ovrpNode_EyeRight:
        case ovrpNode_EyeCenter:
            return g_xr.running ? 1 : 0;
        case ovrpNode_HandLeft:
        case ovrpNode_HandRight: {
            InputState st; input_snapshot(&st);
            return node == ovrpNode_HandLeft ? st.lvalid : st.rvalid;
        }
        default: return 0;
    }
}

int xrr_get_hand_pose(int node, ovrpPoseStatef *out) {
    InputState st; input_snapshot(&st);   /* pose + valid from the SAME sync */
    const XrPosef *p = NULL;
    if (node == ovrpNode_HandLeft  && st.lvalid) p = &st.lpose;
    if (node == ovrpNode_HandRight && st.rvalid) p = &st.rpose;
    if (!p) return 0;
    ovrp_pose_from_xr(p, &out->Pose);
    return 1;
}
