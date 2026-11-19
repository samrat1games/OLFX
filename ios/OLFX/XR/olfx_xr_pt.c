// XR_FB_passthrough for the OpenGL ES compositor: the phone's back camera plays the part of the headset's passthrough.
// Swift (XRBridge / TrackingStore) delivers camera frames through olfx_xr_publish_camera while a game has passthrough running;
// the compositor (olfx_xr_gfx.c) draws the newest frame behind the game's eye images.
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include "olfx_xr_internal.h"

extern void olfx_xr_camera_request(int on);        // Swift: start/stop the camera feed

static pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;
static uint8_t *g_buf; static size_t g_cap; static int g_w, g_h; static uint32_t g_seq;
static int g_started;              // xrPassthroughStartFB without a following pause
static int g_layers_running;       // layers created or resumed and not paused

static void apply(void) { static int last = -1; int on = (g_started && g_layers_running > 0) ? 1 : 0; if (on != last) { last = on; OLFX_LOG("passthrough: camera %s", on ? "ON" : "off"); olfx_xr_camera_request(on); } }

void olfx_xr_publish_camera(const uint8_t *bgra, int w, int h, int stride)
{
	if (!bgra || w <= 0 || h <= 0) return;
	size_t need = (size_t)w * h * 4;
	pthread_mutex_lock(&g_mu);
	if (g_cap < need) { free(g_buf); g_buf = malloc(need); g_cap = g_buf ? need : 0; }
	if (g_buf) {
		for (int y = 0; y < h; y++) memcpy(g_buf + (size_t)y * w * 4, bgra + (size_t)y * stride, (size_t)w * 4);
		g_w = w; g_h = h; g_seq++;
	}
	pthread_mutex_unlock(&g_mu);
}
int olfx_pt_active(void) { pthread_mutex_lock(&g_mu); int a = g_started && g_layers_running > 0; pthread_mutex_unlock(&g_mu); return a; }
// Copies the newest frame into *dst (grown as needed) when it is newer than *seq. Returns 1 if *dst now holds a new frame, 0 if unchanged, -1 if there is none yet.
int olfx_pt_fetch(uint8_t **dst, size_t *cap, int *w, int *h, uint32_t *seq)
{
	int r = 0;
	pthread_mutex_lock(&g_mu);
	if (!g_buf || !g_seq) r = -1;
	else if (g_seq != *seq) {
		size_t need = (size_t)g_w * g_h * 4;
		if (*cap < need) { free(*dst); *dst = malloc(need); *cap = *dst ? need : 0; }
		if (*dst) { memcpy(*dst, g_buf, need); *w = g_w; *h = g_h; *seq = g_seq; r = 1; }
	}
	pthread_mutex_unlock(&g_mu);
	return r;
}

typedef struct { int paused; } pt_layer;
static int g_pt_obj;

OLFX_FN(xrCreatePassthroughFB)(XrSession s, const XrPassthroughCreateInfoFB *ci, XrPassthroughFB *out)
{
	(void)s; if (!ci || !out) return XR_ERROR_VALIDATION_FAILURE;
	*out = (XrPassthroughFB)(uintptr_t)&g_pt_obj;
	pthread_mutex_lock(&g_mu); g_started = (ci->flags & XR_PASSTHROUGH_IS_RUNNING_AT_CREATION_BIT_FB) ? 1 : g_started; apply(); pthread_mutex_unlock(&g_mu);
	OLFX_LOG("passthrough: xrCreatePassthroughFB flags=%#llx", (unsigned long long)ci->flags); return XR_SUCCESS;
}
OLFX_FN(xrDestroyPassthroughFB)(XrPassthroughFB p) { (void)p; pthread_mutex_lock(&g_mu); g_started = 0; apply(); pthread_mutex_unlock(&g_mu); return XR_SUCCESS; }
OLFX_FN(xrPassthroughStartFB)(XrPassthroughFB p) { (void)p; pthread_mutex_lock(&g_mu); g_started = 1; apply(); pthread_mutex_unlock(&g_mu); return XR_SUCCESS; }
OLFX_FN(xrPassthroughPauseFB)(XrPassthroughFB p) { (void)p; pthread_mutex_lock(&g_mu); g_started = 0; apply(); pthread_mutex_unlock(&g_mu); return XR_SUCCESS; }
OLFX_FN(xrCreatePassthroughLayerFB)(XrSession s, const XrPassthroughLayerCreateInfoFB *ci, XrPassthroughLayerFB *out)
{
	(void)s; if (!ci || !out) return XR_ERROR_VALIDATION_FAILURE;
	if (ci->purpose == XR_PASSTHROUGH_LAYER_PURPOSE_PROJECTED_FB) return XR_ERROR_FEATURE_UNSUPPORTED;
	pt_layer *l = calloc(1, sizeof *l); if (!l) return XR_ERROR_OUT_OF_MEMORY;
	l->paused = (ci->flags & XR_PASSTHROUGH_IS_RUNNING_AT_CREATION_BIT_FB) ? 0 : 1;
	*out = (XrPassthroughLayerFB)(uintptr_t)l;
	pthread_mutex_lock(&g_mu); if (!l->paused) g_layers_running++; apply(); pthread_mutex_unlock(&g_mu);
	OLFX_LOG("passthrough: layer created (purpose %d, %s)", (int)ci->purpose, l->paused ? "paused" : "running"); return XR_SUCCESS;
}
OLFX_FN(xrDestroyPassthroughLayerFB)(XrPassthroughLayerFB h)
{
	pt_layer *l = (pt_layer *)(uintptr_t)h; if (!l) return XR_ERROR_HANDLE_INVALID;
	pthread_mutex_lock(&g_mu); if (!l->paused) g_layers_running--; apply(); pthread_mutex_unlock(&g_mu); free(l); return XR_SUCCESS;
}
OLFX_FN(xrPassthroughLayerPauseFB)(XrPassthroughLayerFB h)
{ pt_layer *l = (pt_layer *)(uintptr_t)h; if (!l) return XR_ERROR_HANDLE_INVALID; pthread_mutex_lock(&g_mu); if (!l->paused) { l->paused = 1; g_layers_running--; } apply(); pthread_mutex_unlock(&g_mu); return XR_SUCCESS; }
OLFX_FN(xrPassthroughLayerResumeFB)(XrPassthroughLayerFB h)
{ pt_layer *l = (pt_layer *)(uintptr_t)h; if (!l) return XR_ERROR_HANDLE_INVALID; pthread_mutex_lock(&g_mu); if (l->paused) { l->paused = 0; g_layers_running++; } apply(); pthread_mutex_unlock(&g_mu); return XR_SUCCESS; }
OLFX_FN(xrPassthroughLayerSetStyleFB)(XrPassthroughLayerFB h, const XrPassthroughStyleFB *st) { (void)h; (void)st; return XR_SUCCESS; }  // opacity / edge colour: not applied yet

// XR_FB_passthrough exposes these entry points. Projected geometry separately
// requires XR_FB_triangle_mesh, which this runtime does not advertise.
OLFX_FN(xrCreateGeometryInstanceFB)(XrSession s, const XrGeometryInstanceCreateInfoFB *info, XrGeometryInstanceFB *out)
{
	(void)s;
	if (!info || !out) return XR_ERROR_VALIDATION_FAILURE;
	*out = XR_NULL_HANDLE;
	return XR_ERROR_FEATURE_UNSUPPORTED;
}

OLFX_FN(xrDestroyGeometryInstanceFB)(XrGeometryInstanceFB handle)
{
	(void)handle;
	return XR_ERROR_HANDLE_INVALID;
}

OLFX_FN(xrGeometryInstanceSetTransformFB)(XrGeometryInstanceFB handle, const XrGeometryInstanceTransformFB *transform)
{
	(void)handle;
	(void)transform;
	return XR_ERROR_HANDLE_INVALID;
}

const olfx_entry olfx_pt_table[] = {
	OLFX_E(xrCreatePassthroughFB), OLFX_E(xrDestroyPassthroughFB), OLFX_E(xrPassthroughStartFB), OLFX_E(xrPassthroughPauseFB),
	OLFX_E(xrCreatePassthroughLayerFB), OLFX_E(xrDestroyPassthroughLayerFB), OLFX_E(xrPassthroughLayerPauseFB), OLFX_E(xrPassthroughLayerResumeFB),
	OLFX_E(xrPassthroughLayerSetStyleFB), OLFX_E(xrCreateGeometryInstanceFB),
	OLFX_E(xrDestroyGeometryInstanceFB), OLFX_E(xrGeometryInstanceSetTransformFB),
};
const int olfx_pt_n = (int)(sizeof olfx_pt_table / sizeof olfx_pt_table[0]);
