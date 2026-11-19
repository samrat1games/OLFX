// OLFX XR bridge: a small OpenXR runtime that lives inside the app, next to Husk's translation layer.
//
// Games running in Husk import xr* functions (Unity's OpenXR plugin binds them from libopenxr_loader.so).
// Husk's linker can interpose symbols by name (tl_ld_interpose), so every xr* import is bound to the functions here
// instead of the real loader (which would look for an Android runtime broker that does not exist on iOS).
//
// Files: olfx_xr.c (instance, session, spaces, frames, hand tracking), olfx_xr_gfx.c (OpenGL ES swapchains and the
// stereo compositor), olfx_xr_input.c (actions; hands and a gamepad stand in for Touch controllers).
// Tracking comes from Swift (ARKit head, Apple Vision hands, GCController) through olfx_xr_publish_*.
#include <math.h>
#include <stdatomic.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include "olfx_xr_internal.h"
#include "olfx_vrapi.h"
#include "olfx_ovrp.h"

extern void tl_ld_interpose(const char *name, void *fn);

// ---------- pose store ----------
static pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;
static olfx_mat4 g_head = { { 1,0,0,0, 0,1,0,0, 0,0,1,0, 0,1.6f,0,1 } };
static int g_head_valid;
static olfx_hand g_hand[2];
static olfx_pad g_pad;

// Launch state: waiting for a session, XR session created, stereo frame presented.
static atomic_int g_launch_frame;

void olfx_reset_launch_frame(void) { atomic_store(&g_launch_frame, 0); }
int olfx_launch_frame(void) { return atomic_load(&g_launch_frame); }
void olfx_stereo_frame_presented(void) { atomic_store(&g_launch_frame, 2); }

int olfx_loading_poses(float *head, float *hands)
{
	int mask = 0;
	pthread_mutex_lock(&g_mu);
	memcpy(head, g_head.m, sizeof g_head.m);
	if (g_head_valid) mask |= 4;
	for (int h = 0; h < 2; h++) {
		if (!g_hand[h].active) continue;
		mask |= 1 << h;
		for (int j = 0; j < XR_HAND_JOINT_COUNT_EXT; j++)
			memcpy(hands + (h * XR_HAND_JOINT_COUNT_EXT + j) * 3, g_hand[h].j[j], 3 * sizeof(float));
	}
	pthread_mutex_unlock(&g_mu);
	return mask;
}

void olfx_xr_publish_head(const float *m16) { pthread_mutex_lock(&g_mu); memcpy(g_head.m, m16, sizeof g_head.m); g_head_valid = 1; pthread_mutex_unlock(&g_mu); }
void olfx_xr_publish_hand(int hand, int active, const float *joints)
{
	if (hand < 0 || hand > 1) return;
	pthread_mutex_lock(&g_mu);
	g_hand[hand].active = active;
	if (active) memcpy(g_hand[hand].j, joints, sizeof g_hand[hand].j);
	pthread_mutex_unlock(&g_mu);
}
void olfx_xr_publish_pad(const float *v32) { pthread_mutex_lock(&g_mu); memcpy(g_pad.v, v32, sizeof g_pad.v); pthread_mutex_unlock(&g_mu); }
void olfx_get_hand(int hand, olfx_hand *out) { pthread_mutex_lock(&g_mu); *out = g_hand[hand]; pthread_mutex_unlock(&g_mu); }
void olfx_get_pad(olfx_pad *out) { pthread_mutex_lock(&g_mu); *out = g_pad; pthread_mutex_unlock(&g_mu); }
static olfx_mat4 head_world(void) { pthread_mutex_lock(&g_mu); olfx_mat4 h = g_head; pthread_mutex_unlock(&g_mu); return h; }

// ---------- math ----------
olfx_mat4 olfx_m_identity(void) { olfx_mat4 r = { { 1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1 } }; return r; }
olfx_mat4 olfx_m_inv(olfx_mat4 a)
{
	olfx_mat4 r = olfx_m_identity();
	for (int c = 0; c < 3; c++) for (int rr = 0; rr < 3; rr++) r.m[c*4+rr] = a.m[rr*4+c];
	for (int rr = 0; rr < 3; rr++) r.m[12+rr] = -(r.m[0*4+rr]*a.m[12] + r.m[1*4+rr]*a.m[13] + r.m[2*4+rr]*a.m[14]);
	return r;
}
olfx_mat4 olfx_m_mul(olfx_mat4 a, olfx_mat4 b)
{
	olfx_mat4 r = { { 0 } };
	for (int c = 0; c < 4; c++) for (int rr = 0; rr < 4; rr++) { float s = 0; for (int k = 0; k < 4; k++) s += a.m[k*4+rr] * b.m[c*4+k]; r.m[c*4+rr] = s; }
	return r;
}
void olfx_m_to_pose(olfx_mat4 a, XrPosef *p)
{
	float r00=a.m[0], r10=a.m[1], r20=a.m[2], r01=a.m[4], r11=a.m[5], r21=a.m[6], r02=a.m[8], r12=a.m[9], r22=a.m[10];
	float tr = r00 + r11 + r22, x, y, z, w;
	if (tr > 0) { float s = sqrtf(tr + 1.f) * 2; w = s/4; x = (r21-r12)/s; y = (r02-r20)/s; z = (r10-r01)/s; }
	else if (r00 > r11 && r00 > r22) { float s = sqrtf(1.f + r00 - r11 - r22) * 2; w = (r21-r12)/s; x = s/4; y = (r01+r10)/s; z = (r02+r20)/s; }
	else if (r11 > r22) { float s = sqrtf(1.f + r11 - r00 - r22) * 2; w = (r02-r20)/s; x = (r01+r10)/s; y = s/4; z = (r12+r21)/s; }
	else { float s = sqrtf(1.f + r22 - r00 - r11) * 2; w = (r10-r01)/s; x = (r02+r20)/s; y = (r12+r21)/s; z = s/4; }
	p->orientation = (XrQuaternionf){ x, y, z, w };
	p->position = (XrVector3f){ a.m[12], a.m[13], a.m[14] };
}
olfx_mat4 olfx_pose_to_m(const XrPosef *p)
{
	float x = p->orientation.x, y = p->orientation.y, z = p->orientation.z, w = p->orientation.w;
	olfx_mat4 r = olfx_m_identity();
	r.m[0] = 1-2*(y*y+z*z); r.m[1] = 2*(x*y+z*w);   r.m[2] = 2*(x*z-y*w);
	r.m[4] = 2*(x*y-z*w);   r.m[5] = 1-2*(x*x+z*z); r.m[6] = 2*(y*z+x*w);
	r.m[8] = 2*(x*z+y*w);   r.m[9] = 2*(y*z-x*w);   r.m[10] = 1-2*(x*x+y*y);
	r.m[12] = p->position.x; r.m[13] = p->position.y; r.m[14] = p->position.z;
	return r;
}

// ---------- state ----------
typedef struct { int dummy; } inst_t;
typedef struct { int began; XrTime next_frame; } sess_t;
typedef struct { int hand; } hand_t;
static inst_t g_inst;
static sess_t g_sess;
static olfx_event g_events[32];
static int g_nev;
static int g_eye = 896;
void olfx_xr_set_eye_size(int px) { if (px >= 256 && px <= 2048) g_eye = px; }
#define OLFX_EYE_HEIGHT 1.6f
#define OLFX_RS_LOCAL_FLOOR 1000426000
#define FRAME_NS 16666667LL        // 60 Hz phone; the game sees a steady predicted period

XrTime olfx_now(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return (XrTime)t.tv_sec * 1000000000LL + t.tv_nsec; }
static pthread_mutex_t g_ev_mu = PTHREAD_MUTEX_INITIALIZER;
void olfx_push_event(int type, int arg) { pthread_mutex_lock(&g_ev_mu); if (g_nev < 32) g_events[g_nev++] = (olfx_event){ type, arg }; pthread_mutex_unlock(&g_ev_mu); }

void olfx_trace(const char *name)
{
	static struct { const char *n; unsigned c; } t[64]; static pthread_mutex_t m = PTHREAD_MUTEX_INITIALIZER;
	pthread_mutex_lock(&m);
	int k = 0; while (k < 64 && t[k].n && strcmp(t[k].n, name)) k++;
	unsigned c = 0;
	if (k < 64) { if (!t[k].n) t[k].n = name; c = ++t[k].c; }
	pthread_mutex_unlock(&m);
	if (c && (c <= 3 || c % 1000 == 0)) OLFX_LOG("call %s #%u", name, c);
}

olfx_mat4 olfx_space_world(const olfx_space *s, int *valid)
{
	if (valid) *valid = 1;
	if (!s->is_action) {
		// World is floor-based, metres, y up. VIEW = the head. LOCAL = eye level above the origin (where tracking started). STAGE / LOCAL_FLOOR = the floor.
		olfx_mat4 base = olfx_m_identity();
		if (s->type == XR_REFERENCE_SPACE_TYPE_VIEW) base = head_world();
		else if (s->type == XR_REFERENCE_SPACE_TYPE_LOCAL) base.m[13] = OLFX_EYE_HEIGHT;
		return olfx_m_mul(base, olfx_pose_to_m(&s->offset));
	}
	olfx_hand h; olfx_get_hand(s->hand, &h);
	olfx_pad pad; olfx_get_pad(&pad);
	if (pad.v[24 + s->hand] >= 0.5f || !h.active) {
		// Prefer the controller gyro when available; its position is estimated relative to the head.
		if (pad.v[24 + s->hand] < 0.5f) { if (valid) *valid = 0; return olfx_m_identity(); }
		if (valid) *valid = 2; // Orientation tracked; position is an estimate, not 6DoF.
		olfx_mat4 head = head_world();
		float fx = -head.m[8], fz = -head.m[10], len = sqrtf(fx*fx + fz*fz);      // the head's heading (yaw only)
		if (len < 1e-4f) { fx = 0; fz = -1; len = 1; }
		fx /= len; fz /= len;
		olfx_mat4 yaw = olfx_m_identity();                                        // maps -Z to the heading
		yaw.m[0] = -fz; yaw.m[2] = fx; yaw.m[8] = -fx; yaw.m[10] = -fz;
		XrPosef p = { { pad.v[16 + 4 * s->hand], pad.v[17 + 4 * s->hand], pad.v[18 + 4 * s->hand], pad.v[19 + 4 * s->hand] }, { 0, 0, 0 } };
		olfx_mat4 rot = olfx_m_mul(yaw, olfx_pose_to_m(&p));
		float side = s->hand == 0 ? -0.22f : 0.22f;
		rot.m[12] = head.m[12] + yaw.m[0] * side + yaw.m[8] * -0.35f;             // 22 cm to the side, 35 cm ahead
		rot.m[13] = head.m[13] - 0.35f;                                          // and 35 cm below the eyes
		rot.m[14] = head.m[14] + yaw.m[2] * side + yaw.m[10] * -0.35f;
		return olfx_m_mul(rot, olfx_pose_to_m(&s->offset));
	}
	XrPosef p = { { h.j[0][3], h.j[0][4], h.j[0][5], h.j[0][6] }, { h.j[0][0], h.j[0][1], h.j[0][2] } };   // palm
	return olfx_m_mul(olfx_pose_to_m(&p), olfx_pose_to_m(&s->offset));
}

// ---------- paths ----------
static char g_paths[1024][96];
static int g_npaths;
XrResult olfx_string_to_path(const char *s, XrPath *p)
{
	for (int k = 0; k < g_npaths; k++) if (!strcmp(g_paths[k], s)) { *p = k + 1; return XR_SUCCESS; }
	if (g_npaths >= 1024) return XR_ERROR_PATH_COUNT_EXCEEDED;
	snprintf(g_paths[g_npaths], sizeof g_paths[0], "%s", s); *p = ++g_npaths; return XR_SUCCESS;
}
const char *olfx_path_str(XrPath p) { return (p >= 1 && (int)p <= g_npaths) ? g_paths[p - 1] : ""; }

// ---------- OpenXR functions ----------
static const char *k_ext[] = { "XR_EXT_hand_tracking", "XR_KHR_opengl_es_enable", "XR_KHR_vulkan_enable", "XR_KHR_vulkan_enable2", "XR_KHR_android_create_instance", "XR_KHR_convert_timespec_time", "XR_KHR_android_thread_settings", "XR_EXT_performance_settings", "XR_FB_passthrough" };
#define NEXT_EXT ((uint32_t)(sizeof k_ext / sizeof k_ext[0]))

OLFX_FN(xrEnumerateInstanceExtensionProperties)(const char *layer, uint32_t cap, uint32_t *count, XrExtensionProperties *p)
{
	(void)layer; *count = NEXT_EXT; if (cap == 0) return XR_SUCCESS; if (cap < NEXT_EXT) return XR_ERROR_SIZE_INSUFFICIENT;
	for (uint32_t i = 0; i < NEXT_EXT; i++) { snprintf(p[i].extensionName, sizeof p[i].extensionName, "%s", k_ext[i]); p[i].extensionVersion = 1; }
	return XR_SUCCESS;
}
OLFX_FN(xrCreateInstance)(const XrInstanceCreateInfo *ci, XrInstance *out)
{
	OLFX_LOG("xrCreateInstance app=%s engine=%s", ci->applicationInfo.applicationName, ci->applicationInfo.engineName);
	for (uint32_t i = 0; i < ci->enabledExtensionCount; i++) OLFX_LOG("  ext requested: %s", ci->enabledExtensionNames[i]);
	*out = (XrInstance)&g_inst; return XR_SUCCESS;
}
OLFX_FN(xrDestroyInstance)(XrInstance i) { (void)i; return XR_SUCCESS; }
OLFX_FN(xrGetInstanceProperties)(XrInstance i, XrInstanceProperties *p)
{ (void)i; p->runtimeVersion = XR_MAKE_VERSION(0, 2, 0); snprintf(p->runtimeName, sizeof p->runtimeName, "OLFX iOS"); return XR_SUCCESS; }
OLFX_FN(xrGetSystem)(XrInstance i, const XrSystemGetInfo *gi, XrSystemId *id)
{ (void)i; if (gi->formFactor != XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY) return XR_ERROR_FORM_FACTOR_UNSUPPORTED; *id = 1; return XR_SUCCESS; }
OLFX_FN(xrGetSystemProperties)(XrInstance i, XrSystemId id, XrSystemProperties *p)
{
	(void)i; (void)id;
	p->vendorId = 0x4F4C; snprintf(p->systemName, sizeof p->systemName, "OLFX phone headset"); p->systemId = 1;
	p->graphicsProperties.maxSwapchainImageWidth = 4096; p->graphicsProperties.maxSwapchainImageHeight = 4096; p->graphicsProperties.maxLayerCount = 16;
	p->trackingProperties.orientationTracking = XR_TRUE; p->trackingProperties.positionTracking = XR_TRUE;
	for (XrBaseOutStructure *n = p->next; n; n = n->next)
		if (n->type == XR_TYPE_SYSTEM_HAND_TRACKING_PROPERTIES_EXT) ((XrSystemHandTrackingPropertiesEXT *)n)->supportsHandTracking = XR_TRUE;
	return XR_SUCCESS;
}
OLFX_FN(xrEnumerateViewConfigurations)(XrInstance i, XrSystemId id, uint32_t cap, uint32_t *count, XrViewConfigurationType *t)
{ (void)i; (void)id; *count = 1; if (cap == 0) return XR_SUCCESS; if (cap < 1) return XR_ERROR_SIZE_INSUFFICIENT; t[0] = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO; return XR_SUCCESS; }
OLFX_FN(xrGetViewConfigurationProperties)(XrInstance i, XrSystemId id, XrViewConfigurationType vt, XrViewConfigurationProperties *p)
{ (void)i; (void)id; p->viewConfigurationType = vt; p->fovMutable = XR_FALSE; return XR_SUCCESS; }
OLFX_FN(xrEnumerateViewConfigurationViews)(XrInstance i, XrSystemId id, XrViewConfigurationType vt, uint32_t cap, uint32_t *count, XrViewConfigurationView *v)
{
	(void)i; (void)id; if (vt != XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO) return XR_ERROR_VIEW_CONFIGURATION_TYPE_UNSUPPORTED;
	*count = 2; if (cap == 0) return XR_SUCCESS; if (cap < 2) return XR_ERROR_SIZE_INSUFFICIENT;
	for (int e = 0; e < 2; e++) {
		v[e].recommendedImageRectWidth = (uint32_t)g_eye; v[e].recommendedImageRectHeight = (uint32_t)g_eye; v[e].maxImageRectWidth = 2048; v[e].maxImageRectHeight = 2048;
		v[e].recommendedSwapchainSampleCount = 1; v[e].maxSwapchainSampleCount = 1;
	}
	return XR_SUCCESS;
}
OLFX_FN(xrEnumerateEnvironmentBlendModes)(XrInstance i, XrSystemId id, XrViewConfigurationType vt, uint32_t cap, uint32_t *count, XrEnvironmentBlendMode *m)
{ (void)i; (void)id; (void)vt; *count = 2; if (cap == 0) return XR_SUCCESS; if (cap < 2) return XR_ERROR_SIZE_INSUFFICIENT; m[0] = XR_ENVIRONMENT_BLEND_MODE_OPAQUE; m[1] = XR_ENVIRONMENT_BLEND_MODE_ALPHA_BLEND; return XR_SUCCESS; }
OLFX_FN(xrCreateSession)(XrInstance i, const XrSessionCreateInfo *ci, XrSession *out)
{
	(void)i; olfx_trace("xrCreateSession");
	if (ci->systemId != 1) return XR_ERROR_SYSTEM_INVALID;
	atomic_store(&g_launch_frame, 1);
	int bad = olfx_gfx_session_ok(ci);
	if (bad) return XR_ERROR_GRAPHICS_DEVICE_INVALID;
	memset(&g_sess, 0, sizeof g_sess); g_nev = 0;
	olfx_push_event(XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED, XR_SESSION_STATE_IDLE);
	olfx_push_event(XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED, XR_SESSION_STATE_READY);
	*out = (XrSession)&g_sess; return XR_SUCCESS;
}
OLFX_FN(xrDestroySession)(XrSession s) { (void)s; return XR_SUCCESS; }
OLFX_FN(xrBeginSession)(XrSession s, const XrSessionBeginInfo *bi)
{
	(void)bi; ((sess_t *)s)->began = 1; ((sess_t *)s)->next_frame = olfx_now();
	olfx_push_event(XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED, XR_SESSION_STATE_SYNCHRONIZED);
	olfx_push_event(XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED, XR_SESSION_STATE_VISIBLE);
	olfx_push_event(XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED, XR_SESSION_STATE_FOCUSED);
	return XR_SUCCESS;
}
OLFX_FN(xrEndSession)(XrSession s) { ((sess_t *)s)->began = 0; olfx_push_event(XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED, XR_SESSION_STATE_IDLE); return XR_SUCCESS; }
OLFX_FN(xrRequestExitSession)(XrSession s) { (void)s; olfx_push_event(XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED, XR_SESSION_STATE_STOPPING); return XR_SUCCESS; }
OLFX_FN(xrPollEvent)(XrInstance i, XrEventDataBuffer *eb)
{
	(void)i; pthread_mutex_lock(&g_ev_mu);
	if (!g_nev) { pthread_mutex_unlock(&g_ev_mu); return XR_EVENT_UNAVAILABLE; }
	olfx_event ev = g_events[0]; memmove(g_events, g_events + 1, (--g_nev) * sizeof g_events[0]);
	pthread_mutex_unlock(&g_ev_mu);
	if (ev.type == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
		XrEventDataSessionStateChanged *e = (XrEventDataSessionStateChanged *)eb;
		e->type = ev.type; e->next = NULL; e->session = (XrSession)&g_sess; e->state = (XrSessionState)ev.arg; e->time = olfx_now();
		OLFX_LOG("event: session state -> %d", ev.arg);
	} else {
		XrEventDataInteractionProfileChanged *e = (XrEventDataInteractionProfileChanged *)eb;
		e->type = XR_TYPE_EVENT_DATA_INTERACTION_PROFILE_CHANGED; e->next = NULL; e->session = (XrSession)&g_sess;
		OLFX_LOG("event: interaction profile changed");
	}
	return XR_SUCCESS;
}
OLFX_FN(xrEnumerateReferenceSpaces)(XrSession s, uint32_t cap, uint32_t *count, XrReferenceSpaceType *t)
{
	(void)s; *count = 4; if (cap == 0) return XR_SUCCESS; if (cap < 4) return XR_ERROR_SIZE_INSUFFICIENT;
	t[0] = XR_REFERENCE_SPACE_TYPE_VIEW; t[1] = XR_REFERENCE_SPACE_TYPE_LOCAL; t[2] = XR_REFERENCE_SPACE_TYPE_STAGE; t[3] = (XrReferenceSpaceType)OLFX_RS_LOCAL_FLOOR; return XR_SUCCESS;
}
OLFX_FN(xrGetReferenceSpaceBoundsRect)(XrSession s, XrReferenceSpaceType t, XrExtent2Df *b)
{ (void)s; (void)t; b->width = 0; b->height = 0; return XR_SPACE_BOUNDS_UNAVAILABLE; }
OLFX_FN(xrCreateReferenceSpace)(XrSession s, const XrReferenceSpaceCreateInfo *ci, XrSpace *out)
{
	(void)s; XrReferenceSpaceType t = ci->referenceSpaceType;
	if (t != XR_REFERENCE_SPACE_TYPE_VIEW && t != XR_REFERENCE_SPACE_TYPE_LOCAL && t != XR_REFERENCE_SPACE_TYPE_STAGE && (int)t != OLFX_RS_LOCAL_FLOOR) return XR_ERROR_REFERENCE_SPACE_UNSUPPORTED;
	olfx_space *sp = calloc(1, sizeof *sp); sp->type = t; sp->offset = ci->poseInReferenceSpace; *out = (XrSpace)sp; return XR_SUCCESS;
}
OLFX_FN(xrDestroySpace)(XrSpace sp) { free(sp); return XR_SUCCESS; }
OLFX_FN(xrLocateSpace)(XrSpace space, XrSpace base, XrTime t, XrSpaceLocation *loc)
{
	(void)t; int va, vb;
	for (XrBaseOutStructure *n = (XrBaseOutStructure *)loc->next; n; n = n->next)
		if (n->type == XR_TYPE_SPACE_VELOCITY) { XrSpaceVelocity *v = (XrSpaceVelocity *)n; v->velocityFlags = 0; v->linearVelocity = (XrVector3f){ 0, 0, 0 }; v->angularVelocity = (XrVector3f){ 0, 0, 0 }; }
	olfx_mat4 a = olfx_space_world((olfx_space *)space, &va), b = olfx_space_world((olfx_space *)base, &vb);
	olfx_m_to_pose(olfx_m_mul(olfx_m_inv(b), a), &loc->pose);
	loc->locationFlags = (va && vb) ? (XR_SPACE_LOCATION_ORIENTATION_VALID_BIT | XR_SPACE_LOCATION_POSITION_VALID_BIT |
	                                   XR_SPACE_LOCATION_ORIENTATION_TRACKED_BIT | XR_SPACE_LOCATION_POSITION_TRACKED_BIT) : 0;
	if (va == 2 || vb == 2) loc->locationFlags &= ~XR_SPACE_LOCATION_POSITION_TRACKED_BIT;
	return XR_SUCCESS;
}
OLFX_FN(xrWaitFrame)(XrSession s, const XrFrameWaitInfo *wi, XrFrameState *fs)
{
	(void)wi; olfx_trace("xrWaitFrame"); sess_t *ss = (sess_t *)s; XrTime now = olfx_now();
	if (ss->next_frame > now) usleep((useconds_t)((ss->next_frame - now) / 1000)); else ss->next_frame = now;
	ss->next_frame += FRAME_NS;
	fs->predictedDisplayTime = ss->next_frame; fs->predictedDisplayPeriod = FRAME_NS; fs->shouldRender = XR_TRUE; return XR_SUCCESS;
}
OLFX_FN(xrBeginFrame)(XrSession s, const XrFrameBeginInfo *bi) { (void)s; (void)bi; olfx_trace("xrBeginFrame"); return XR_SUCCESS; }
OLFX_FN(xrEndFrame)(XrSession s, const XrFrameEndInfo *ei) { (void)s; olfx_trace("xrEndFrame"); olfx_gfx_end_frame(ei); return XR_SUCCESS; }
OLFX_FN(xrLocateViews)(XrSession s, const XrViewLocateInfo *li, XrViewState *vs, uint32_t cap, uint32_t *count, XrView *v)
{
	(void)s; olfx_trace("xrLocateViews"); *count = 2; if (cap == 0) return XR_SUCCESS; if (cap < 2) return XR_ERROR_SIZE_INSUFFICIENT;
	olfx_mat4 base_inv = olfx_m_inv(olfx_space_world((olfx_space *)li->space, NULL)), head = head_world();
	for (int e = 0; e < 2; e++) {
		olfx_mat4 eye = olfx_m_identity(); eye.m[12] = e == 0 ? -0.0315f : 0.0315f;       // 63 mm IPD
		olfx_m_to_pose(olfx_m_mul(base_inv, olfx_m_mul(head, eye)), &v[e].pose);
		v[e].fov = (XrFovf){ -0.85f, 0.85f, 0.85f, -0.85f };                              // ~97 deg
	}
	vs->viewStateFlags = XR_VIEW_STATE_ORIENTATION_VALID_BIT | XR_VIEW_STATE_POSITION_VALID_BIT | XR_VIEW_STATE_ORIENTATION_TRACKED_BIT | XR_VIEW_STATE_POSITION_TRACKED_BIT;
	return XR_SUCCESS;
}
OLFX_FN(xrCreateHandTrackerEXT)(XrSession s, const XrHandTrackerCreateInfoEXT *ci, XrHandTrackerEXT *out)
{ (void)s; hand_t *h = calloc(1, sizeof *h); h->hand = ci->hand == XR_HAND_LEFT_EXT ? 0 : 1; *out = (XrHandTrackerEXT)h; return XR_SUCCESS; }
OLFX_FN(xrDestroyHandTrackerEXT)(XrHandTrackerEXT h) { free(h); return XR_SUCCESS; }
OLFX_FN(xrLocateHandJointsEXT)(XrHandTrackerEXT ht, const XrHandJointsLocateInfoEXT *li, XrHandJointLocationsEXT *out)
{
	hand_t *h = (hand_t *)ht; olfx_hand hs; olfx_get_hand(h->hand, &hs);
	out->isActive = hs.active ? XR_TRUE : XR_FALSE;
	if (!hs.active) {
		for (uint32_t i = 0; i < out->jointCount && i < XR_HAND_JOINT_COUNT_EXT; ++i) {
			memset(&out->jointLocations[i], 0, sizeof(out->jointLocations[i]));
			out->jointLocations[i].pose.orientation.w = 1.0f;
		}
		return XR_SUCCESS;
	}
	olfx_mat4 base_inv = olfx_m_inv(olfx_space_world((olfx_space *)li->baseSpace, NULL));
	for (uint32_t i = 0; i < out->jointCount && i < XR_HAND_JOINT_COUNT_EXT; i++) {
		XrPosef w = { { hs.j[i][3], hs.j[i][4], hs.j[i][5], hs.j[i][6] }, { hs.j[i][0], hs.j[i][1], hs.j[i][2] } };
		olfx_m_to_pose(olfx_m_mul(base_inv, olfx_pose_to_m(&w)), &out->jointLocations[i].pose);
		out->jointLocations[i].radius = hs.j[i][7];
		out->jointLocations[i].locationFlags = XR_SPACE_LOCATION_ORIENTATION_VALID_BIT | XR_SPACE_LOCATION_POSITION_VALID_BIT |
		                                       XR_SPACE_LOCATION_ORIENTATION_TRACKED_BIT | XR_SPACE_LOCATION_POSITION_TRACKED_BIT;
	}
	return XR_SUCCESS;
}
OLFX_FN(xrEnumerateApiLayerProperties)(uint32_t cap, uint32_t *count, XrApiLayerProperties *p) { (void)cap; (void)p; *count = 0; return XR_SUCCESS; }
OLFX_FN(xrResultToString)(XrInstance i, XrResult r, char buf[XR_MAX_RESULT_STRING_SIZE]) { (void)i; snprintf(buf, XR_MAX_RESULT_STRING_SIZE, "XR_RESULT_%d", (int)r); return XR_SUCCESS; }
OLFX_FN(xrStructureTypeToString)(XrInstance i, XrStructureType t, char buf[XR_MAX_STRUCTURE_NAME_SIZE]) { (void)i; snprintf(buf, XR_MAX_STRUCTURE_NAME_SIZE, "XR_TYPE_%d", (int)t); return XR_SUCCESS; }
OLFX_FN(xrGetInputSourceLocalizedName)(XrSession s, const XrInputSourceLocalizedNameGetInfo *gi, uint32_t cap, uint32_t *count, char *buf)
{ (void)s; (void)gi; *count = 1; if (cap == 0) return XR_SUCCESS; if (cap < 1) return XR_ERROR_SIZE_INSUFFICIENT; buf[0] = 0; return XR_SUCCESS; }
OLFX_FN(xrSetAndroidApplicationThreadKHR)(XrSession s, int type, uint32_t tid) { (void)s; (void)type; (void)tid; return XR_SUCCESS; }
OLFX_FN(xrPerfSettingsSetPerformanceLevelEXT)(XrSession s, int domain, int level) { (void)s; (void)domain; (void)level; return XR_SUCCESS; }
OLFX_FN(xrInitializeLoaderKHR)(const void *info) { (void)info; return XR_SUCCESS; }
OLFX_FN(xrConvertTimespecTimeToTimeKHR)(XrInstance i, const struct timespec *ts, XrTime *t)
{ (void)i; *t = (XrTime)ts->tv_sec * 1000000000LL + ts->tv_nsec; return XR_SUCCESS; }      // CLOCK_MONOTONIC, same as olfx_now
OLFX_FN(xrConvertTimeToTimespecTimeKHR)(XrInstance i, XrTime t, struct timespec *ts)
{ (void)i; ts->tv_sec = t / 1000000000LL; ts->tv_nsec = t % 1000000000LL; return XR_SUCCESS; }
OLFX_FN(xrStringToPath)(XrInstance i, const char *s, XrPath *p)
{
	(void)i; size_t n = s ? strlen(s) : 0;
	if (n < 2 || s[0] != '/' || s[n - 1] == '/') return XR_ERROR_PATH_FORMAT_INVALID;
	for (size_t k = 0; k < n; k++) {
		char c = s[k]; int ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '/';
		if (!ok || (c == '/' && k + 1 < n && s[k + 1] == '/')) return XR_ERROR_PATH_FORMAT_INVALID;
	}
	return olfx_string_to_path(s, p);
}
OLFX_FN(xrPathToString)(XrInstance i, XrPath p, uint32_t cap, uint32_t *count, char *buf)
{
	(void)i; const char *s = olfx_path_str(p); uint32_t n = (uint32_t)strlen(s) + 1;
	*count = n; if (cap == 0) return XR_SUCCESS; if (cap < n) return XR_ERROR_SIZE_INSUFFICIENT; memcpy(buf, s, n); return XR_SUCCESS;
}

// ---------- dispatch ----------
static const olfx_entry k_core[] = {
	OLFX_E(xrEnumerateInstanceExtensionProperties), OLFX_E(xrCreateInstance), OLFX_E(xrDestroyInstance), OLFX_E(xrGetInstanceProperties),
	OLFX_E(xrGetSystem), OLFX_E(xrGetSystemProperties), OLFX_E(xrEnumerateViewConfigurations), OLFX_E(xrGetViewConfigurationProperties),
	OLFX_E(xrEnumerateViewConfigurationViews), OLFX_E(xrEnumerateEnvironmentBlendModes), OLFX_E(xrCreateSession), OLFX_E(xrDestroySession),
	OLFX_E(xrBeginSession), OLFX_E(xrEndSession), OLFX_E(xrRequestExitSession), OLFX_E(xrPollEvent), OLFX_E(xrEnumerateReferenceSpaces),
	OLFX_E(xrGetReferenceSpaceBoundsRect), OLFX_E(xrCreateReferenceSpace), OLFX_E(xrDestroySpace), OLFX_E(xrLocateSpace), OLFX_E(xrWaitFrame),
	OLFX_E(xrBeginFrame), OLFX_E(xrEndFrame), OLFX_E(xrLocateViews), OLFX_E(xrCreateHandTrackerEXT), OLFX_E(xrDestroyHandTrackerEXT),
	OLFX_E(xrLocateHandJointsEXT), OLFX_E(xrInitializeLoaderKHR), OLFX_E(xrEnumerateApiLayerProperties), OLFX_E(xrResultToString), OLFX_E(xrStructureTypeToString), OLFX_E(xrGetInputSourceLocalizedName), OLFX_E(xrSetAndroidApplicationThreadKHR), OLFX_E(xrPerfSettingsSetPerformanceLevelEXT), OLFX_E(xrConvertTimespecTimeToTimeKHR), OLFX_E(xrConvertTimeToTimespecTimeKHR), OLFX_E(xrStringToPath), OLFX_E(xrPathToString),
};
#define NCORE ((int)(sizeof k_core / sizeof k_core[0]))

static PFN_xrVoidFunction find_fn(const char *name)
{
	for (int k = 0; k < NCORE; k++) if (!strcmp(k_core[k].name, name)) return k_core[k].fn;
	for (int k = 0; k < olfx_gfx_n; k++) if (!strcmp(olfx_gfx_table[k].name, name)) return olfx_gfx_table[k].fn;
	for (int k = 0; k < olfx_input_n; k++) if (!strcmp(olfx_input_table[k].name, name)) return olfx_input_table[k].fn;
	for (int k = 0; k < olfx_pt_n; k++) if (!strcmp(olfx_pt_table[k].name, name)) return olfx_pt_table[k].fn;
	for (int k = 0; k < olfx_vk_n; k++) if (!strcmp(olfx_vk_table[k].name, name)) return olfx_vk_table[k].fn;
	return NULL;
}
static XrResult XRAPI_CALL olfx_xrGetInstanceProcAddr(XrInstance i, const char *name, PFN_xrVoidFunction *fn)
{
	(void)i;
	if (!strcmp(name, "xrGetInstanceProcAddr")) { *fn = (PFN_xrVoidFunction)olfx_xrGetInstanceProcAddr; return XR_SUCCESS; }
	PFN_xrVoidFunction f = find_fn(name);
	if (f) { *fn = f; return XR_SUCCESS; }
	static char seen[64][64]; static int ns; int dup = 0;
	for (int k = 0; k < ns; k++) if (!strcmp(seen[k], name)) dup = 1;
	if (!dup && ns < 64) { snprintf(seen[ns++], 64, "%s", name); OLFX_LOG("unsupported (so far): %s", name); }
	*fn = NULL; return XR_ERROR_FUNCTION_UNSUPPORTED;
}

// Android's exported libm symbol is a function, even when the host C header supplies a macro.
static int olfx_android_isnan(double value) { return isnan(value) ? 1 : 0; }

// Called once at app start, before any game loads: every xr* import resolves here.
void olfx_xr_install(const char *libdir)
{
	int n = 1;
	tl_ld_interpose("isnan", (void *)olfx_android_isnan);
	tl_ld_interpose("xrGetInstanceProcAddr", (void *)olfx_xrGetInstanceProcAddr);
	for (int k = 0; k < NCORE; k++, n++) tl_ld_interpose(k_core[k].name, (void *)k_core[k].fn);
	for (int k = 0; k < olfx_gfx_n; k++, n++) tl_ld_interpose(olfx_gfx_table[k].name, (void *)olfx_gfx_table[k].fn);
	for (int k = 0; k < olfx_input_n; k++, n++) tl_ld_interpose(olfx_input_table[k].name, (void *)olfx_input_table[k].fn);
	for (int k = 0; k < olfx_vk_n; k++, n++) tl_ld_interpose(olfx_vk_table[k].name, (void *)olfx_vk_table[k].fn);
	for (int k = 0; k < olfx_pt_n; k++, n++) tl_ld_interpose(olfx_pt_table[k].name, (void *)olfx_pt_table[k].fn);
	OLFX_LOG("installed: %d OpenXR entry points", n);
	olfx_vrapi_install(libdir);
	olfx_ovrp_install(libdir);
}
