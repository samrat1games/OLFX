// OLFX OpenXR: actions and input.
//
// There are no Touch controllers on a phone, so they are stood in for by what exists:
//  - hands (Apple Vision): grip/aim pose = the palm; trigger = thumb-index pinch; squeeze = closed fist;
//  - a game controller (GCController, via olfx_xr_publish_pad): thumbsticks, triggers, grips, A/B/X/Y, menu.
// Any suggested binding is accepted whatever its interaction profile; paths like
// /user/hand/left/input/trigger/value are evaluated on demand from the latest data.
#include <math.h>
#include <stdlib.h>
#include <string.h>
#include "olfx_xr_internal.h"

typedef struct { char name[64]; } aset_t;
typedef struct {
	char name[64]; XrActionType type; int nsub; XrPath sub[4];
	char binds[32][96]; int nbind; int last_bool; XrTime last_change;
} action_t;

OLFX_FN(xrCreateActionSet)(XrInstance i, const XrActionSetCreateInfo *ci, XrActionSet *out)
{ (void)i; aset_t *s = calloc(1, sizeof *s); snprintf(s->name, sizeof s->name, "%s", ci->actionSetName); *out = (XrActionSet)s; return XR_SUCCESS; }
OLFX_FN(xrDestroyActionSet)(XrActionSet s) { free(s); return XR_SUCCESS; }
OLFX_FN(xrCreateAction)(XrActionSet set, const XrActionCreateInfo *ci, XrAction *out)
{
	(void)set; action_t *a = calloc(1, sizeof *a); snprintf(a->name, sizeof a->name, "%s", ci->actionName); a->type = ci->actionType;
	a->nsub = ci->countSubactionPaths > 4 ? 4 : (int)ci->countSubactionPaths;
	for (int k = 0; k < a->nsub; k++) a->sub[k] = ci->subactionPaths[k];
	*out = (XrAction)a; return XR_SUCCESS;
}
OLFX_FN(xrDestroyAction)(XrAction a) { free(a); return XR_SUCCESS; }
OLFX_FN(xrSuggestInteractionProfileBindings)(XrInstance i, const XrInteractionProfileSuggestedBinding *sb)
{
	(void)i; OLFX_LOG("suggest bindings for %s (%u)", olfx_path_str(sb->interactionProfile), sb->countSuggestedBindings);
	for (uint32_t k = 0; k < sb->countSuggestedBindings; k++) {
		action_t *a = (action_t *)sb->suggestedBindings[k].action; const char *p = olfx_path_str(sb->suggestedBindings[k].binding);
		int dup = 0; for (int b = 0; b < a->nbind; b++) if (!strcmp(a->binds[b], p)) dup = 1;
		if (!dup && a->nbind < 32) snprintf(a->binds[a->nbind++], sizeof a->binds[0], "%s", p);
	}
	return XR_SUCCESS;
}
OLFX_FN(xrAttachSessionActionSets)(XrSession s, const XrSessionActionSetsAttachInfo *ai)
{ (void)s; (void)ai; olfx_push_event(XR_TYPE_EVENT_DATA_INTERACTION_PROFILE_CHANGED, 0); return XR_SUCCESS; }
OLFX_FN(xrGetCurrentInteractionProfile)(XrSession s, XrPath top, XrInteractionProfileState *st)
{ (void)s; (void)top; return olfx_string_to_path("/interaction_profiles/oculus/touch_controller", &st->interactionProfile); }
OLFX_FN(xrSyncActions)(XrSession s, const XrActionsSyncInfo *si) { (void)s; (void)si; olfx_trace("xrSyncActions"); return XR_SUCCESS; }
OLFX_FN(xrEnumerateBoundSourcesForAction)(XrSession s, const XrBoundSourcesForActionEnumerateInfo *ei, uint32_t cap, uint32_t *count, XrPath *p)
{ (void)s; (void)ei; (void)cap; (void)p; *count = 0; return XR_SUCCESS; }
OLFX_FN(xrApplyHapticFeedback)(XrSession s, const XrHapticActionInfo *i, const XrHapticBaseHeader *h) { (void)s; (void)i; (void)h; return XR_SUCCESS; }
OLFX_FN(xrStopHapticFeedback)(XrSession s, const XrHapticActionInfo *i) { (void)s; (void)i; return XR_SUCCESS; }

static float clamp01(float x) { return x < 0 ? 0 : (x > 1 ? 1 : x); }
static float dist(const olfx_hand *h, int a, int b)
{
	float dx = h->j[a][0] - h->j[b][0], dy = h->j[a][1] - h->j[b][1], dz = h->j[a][2] - h->j[b][2];
	return sqrtf(dx*dx + dy*dy + dz*dz);
}
// Joint indices (XR_EXT_hand_tracking): palm 0, thumb tip 5, index tip 10, middle tip 15, ring tip 20, little tip 25.
static float g_pinch_close = 0.012f, g_pinch_range = 0.035f;     // metres between thumb tip and index tip: fully pinched / fully open
void olfx_xr_set_pinch(float close, float range) { g_pinch_close = close; g_pinch_range = range > 0.005f ? range : 0.005f; }
static float hand_pinch(const olfx_hand *h) { return h->active ? clamp01(1.f - (dist(h, 5, 10) - g_pinch_close) / g_pinch_range) : 0; }
static float hand_fist(const olfx_hand *h)
{
	if (!h->active) return 0;
	float d = (dist(h, 15, 0) + dist(h, 20, 0) + dist(h, 25, 0)) / 3.f;
	return clamp01((0.095f - d) / 0.04f);
}

// Evaluates one binding path. Returns 1 if the path names something we provide.
static int eval_path(const char *p, float *x, float *y)
{
	int side; const char *rest;
	if (!strncmp(p, "/user/hand/left/input/", 22)) { side = 0; rest = p + 22; }
	else if (!strncmp(p, "/user/hand/right/input/", 23)) { side = 1; rest = p + 23; }
	else return 0;
	olfx_pad pad; olfx_get_pad(&pad); olfx_hand h; olfx_get_hand(side, &h);
	float trig = fmaxf(pad.v[4 + side], hand_pinch(&h)), grip = fmaxf(pad.v[6 + side], hand_fist(&h));
	*x = 0; *y = 0;
	if (!strncmp(rest, "trigger", 7)) { *x = trig; if (strstr(rest, "click")) *x = trig > 0.8f; else if (strstr(rest, "touch")) *x = trig > 0.1f; return 1; }
	if (!strncmp(rest, "squeeze", 7)) { *x = grip; if (strstr(rest, "click")) *x = grip > 0.8f; return 1; }
	if (!strncmp(rest, "thumbstick", 10)) {
		float sx = pad.v[side * 2], sy = pad.v[side * 2 + 1];
		if (strstr(rest, "click")) *x = pad.v[13 + side]; else if (strstr(rest, "touch")) *x = (fabsf(sx) + fabsf(sy)) > 0.05f;
		else if (!strcmp(rest, "thumbstick/x")) *x = sx; else if (!strcmp(rest, "thumbstick/y")) *x = sy; else { *x = sx; *y = sy; }
		return 1;
	}
	static const struct { const char *n; int side; int idx; } btn[] = { { "x/", 0, 10 }, { "y/", 0, 11 }, { "a/", 1, 8 }, { "b/", 1, 9 }, { "menu/", 0, 12 }, { "system/", 1, 12 } };
	for (unsigned k = 0; k < sizeof btn / sizeof btn[0]; k++)
		if (!strncmp(rest, btn[k].n, strlen(btn[k].n))) { if (btn[k].side == side || !strncmp(btn[k].n, "menu", 4)) *x = pad.v[btn[k].idx]; return 1; }
	if (!strncmp(rest, "grip/pose", 9) || !strncmp(rest, "aim/pose", 8)) return 1;
	return 1;        // thumbrest, etc: recognised, always zero
}

// Combines all bindings of an action, restricted to one hand when subaction is given.
static int eval_action(const action_t *a, XrPath sub, float *x, float *y)
{
	const char *prefix = sub ? olfx_path_str(sub) : NULL; size_t pl = prefix ? strlen(prefix) : 0; int any = 0;
	*x = 0; *y = 0;
	for (int b = 0; b < a->nbind; b++) {
		if (pl && strncmp(a->binds[b], prefix, pl)) continue;
		float bx, by; if (!eval_path(a->binds[b], &bx, &by)) continue;
		any = 1; if (fabsf(bx) > fabsf(*x)) *x = bx; if (fabsf(by) > fabsf(*y)) *y = by;
	}
	return any;
}

OLFX_FN(xrGetActionStateBoolean)(XrSession s, const XrActionStateGetInfo *gi, XrActionStateBoolean *st)
{
	(void)s; action_t *a = (action_t *)gi->action; float x, y; int any = eval_action(a, gi->subactionPath, &x, &y);
	int v = x > 0.5f; XrTime now = olfx_now();
	st->isActive = any ? XR_TRUE : XR_FALSE; st->currentState = v ? XR_TRUE : XR_FALSE;
	st->changedSinceLastSync = (v != a->last_bool) ? XR_TRUE : XR_FALSE;
	if (v != a->last_bool) { a->last_bool = v; a->last_change = now; }
	st->lastChangeTime = a->last_change; return XR_SUCCESS;
}
OLFX_FN(xrGetActionStateFloat)(XrSession s, const XrActionStateGetInfo *gi, XrActionStateFloat *st)
{
	(void)s; float x, y; int any = eval_action((action_t *)gi->action, gi->subactionPath, &x, &y);
	st->isActive = any ? XR_TRUE : XR_FALSE; st->currentState = x; st->changedSinceLastSync = XR_FALSE; st->lastChangeTime = olfx_now(); return XR_SUCCESS;
}
OLFX_FN(xrGetActionStateVector2f)(XrSession s, const XrActionStateGetInfo *gi, XrActionStateVector2f *st)
{
	(void)s; float x, y; int any = eval_action((action_t *)gi->action, gi->subactionPath, &x, &y);
	st->isActive = any ? XR_TRUE : XR_FALSE; st->currentState.x = x; st->currentState.y = y; st->changedSinceLastSync = XR_FALSE; st->lastChangeTime = olfx_now(); return XR_SUCCESS;
}
static int hand_of(const action_t *a, XrPath sub, int *is_aim)
{
	const char *s = sub ? olfx_path_str(sub) : ""; *is_aim = 0;
	for (int b = 0; b < a->nbind; b++) if (strstr(a->binds[b], "/aim/pose") && (!*s || !strncmp(a->binds[b], s, strlen(s)))) *is_aim = 1;
	if (strstr(s, "left")) return 0; if (strstr(s, "right")) return 1;
	for (int b = 0; b < a->nbind; b++) { if (strstr(a->binds[b], "/left/")) return 0; if (strstr(a->binds[b], "/right/")) return 1; }
	return 1;
}
OLFX_FN(xrGetActionStatePose)(XrSession s, const XrActionStateGetInfo *gi, XrActionStatePose *st)
{
	(void)s; int aim; int hand = hand_of((action_t *)gi->action, gi->subactionPath, &aim);
	olfx_hand h; olfx_get_hand(hand, &h); olfx_pad pad; olfx_get_pad(&pad);
	st->isActive = (h.active || pad.v[24 + hand] > 0.5f) ? XR_TRUE : XR_FALSE; return XR_SUCCESS;
}
OLFX_FN(xrCreateActionSpace)(XrSession s, const XrActionSpaceCreateInfo *ci, XrSpace *out)
{
	(void)s; olfx_space *sp = calloc(1, sizeof *sp); sp->is_action = 1; sp->offset = ci->poseInActionSpace;
	sp->hand = hand_of((action_t *)ci->action, ci->subactionPath, &sp->is_aim); *out = (XrSpace)sp; return XR_SUCCESS;
}

const olfx_entry olfx_input_table[] = {
	OLFX_E(xrCreateActionSet), OLFX_E(xrDestroyActionSet), OLFX_E(xrCreateAction), OLFX_E(xrDestroyAction),
	OLFX_E(xrSuggestInteractionProfileBindings), OLFX_E(xrAttachSessionActionSets), OLFX_E(xrGetCurrentInteractionProfile), OLFX_E(xrSyncActions),
	OLFX_E(xrEnumerateBoundSourcesForAction), OLFX_E(xrApplyHapticFeedback), OLFX_E(xrStopHapticFeedback), OLFX_E(xrGetActionStateBoolean),
	OLFX_E(xrGetActionStateFloat), OLFX_E(xrGetActionStateVector2f), OLFX_E(xrGetActionStatePose), OLFX_E(xrCreateActionSpace),
};
const int olfx_input_n = (int)(sizeof olfx_input_table / sizeof olfx_input_table[0]);
