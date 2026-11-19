// OLFX OpenXR: OpenGL ES swapchains and the stereo compositor.
//
// The game renders both eyes into textures we hand out (XR_KHR_opengl_es_enable). At xrEndFrame we draw the two eye
// images side by side into the game's own window surface, using Husk's ANGLE (tl_egl_resolve). GL state is saved
// and restored around it, because the game's engine caches state. No lens distortion yet (plain side-by-side).
// Vulkan games are refused (the session fails and it is logged): only GLES is implemented.
#include <stdlib.h>
#include <string.h>
#include "olfx_xr_internal.h"

extern void *tl_egl_resolve(const char *name);
extern int olfx_ovrp_gles_on;                          // OpenGL ES VR path switched on: the game has no window of its own
extern int tl_egl_screen_begin(int *w, int *h);        // Husk (patch husk-zz-egl-screen): our surface on the game layer, made current
extern int tl_egl_screen_end(void);                   // swap it and put the game's surfaces back

typedef unsigned int GLuint; typedef int GLint; typedef unsigned int GLenum; typedef int GLsizei; typedef unsigned char GLboolean;
#define GL_TEXTURE_2D 0x0DE1
#define GL_TEXTURE_2D_ARRAY 0x8C1A
#define GL_TEXTURE0 0x84C0
#define GL_FRAMEBUFFER 0x8D40
#define GL_VERTEX_SHADER 0x8B31
#define GL_FRAGMENT_SHADER 0x8B30
#define GL_COMPILE_STATUS 0x8B81
#define GL_LINK_STATUS 0x8B82
#define GL_TRIANGLE_STRIP 5
#define GL_VIEWPORT 0x0BA2
#define GL_CURRENT_PROGRAM 0x8B8D
#define GL_ACTIVE_TEXTURE 0x84E0
#define GL_TEXTURE_BINDING_2D 0x8069
#define GL_TEXTURE_BINDING_2D_ARRAY 0x8C1D
#define GL_FRAMEBUFFER_BINDING 0x8CA6
#define GL_VERTEX_ARRAY_BINDING 0x85B5
#define GL_DEPTH_TEST 0x0B71
#define GL_CULL_FACE 0x0B44
#define GL_BLEND 0x0BE2
#define GL_SCISSOR_TEST 0x0C11
#define GL_STENCIL_TEST 0x0B90
#define GL_COLOR_WRITEMASK 0x0C23
#define GL_TEXTURE_MIN_FILTER 0x2801
#define GL_TEXTURE_MAG_FILTER 0x2800
#define GL_TEXTURE_WRAP_S 0x2802
#define GL_TEXTURE_WRAP_T 0x2803
#define GL_LINEAR 0x2601
#define GL_CLAMP_TO_EDGE 0x812F
#define GL_RGBA8 0x8058
#define GL_RGBA 0x1908
#define GL_UNSIGNED_BYTE 0x1401
#define GL_ONE 1
#define GL_ONE_MINUS_SRC_ALPHA 0x0303
#define GL_SRC_ALPHA 0x0302
#define GL_SRGB8_ALPHA8 0x8C43
#define GL_RGBA16F 0x881A
#define GL_DEPTH_COMPONENT16 0x81A5
#define GL_DEPTH_COMPONENT24 0x81A6
#define GL_DEPTH_COMPONENT32F 0x8CAC
#define GL_DEPTH24_STENCIL8 0x88F0
#define GL_DEPTH32F_STENCIL8 0x8CAD
#define EGL_DRAW 0x3059
#define EGL_HEIGHT 0x3056
#define EGL_WIDTH 0x3057

#define GLF(ret, name, args) static ret (*gl_##name) args
GLF(void, GenTextures, (GLsizei, GLuint *)); GLF(void, DeleteTextures, (GLsizei, const GLuint *)); GLF(void, BindTexture, (GLenum, GLuint));
GLF(void, TexStorage2D, (GLenum, GLsizei, GLenum, GLsizei, GLsizei)); GLF(void, TexStorage3D, (GLenum, GLsizei, GLenum, GLsizei, GLsizei, GLsizei));
GLF(void, TexParameteri, (GLenum, GLenum, GLint)); GLF(GLuint, CreateShader, (GLenum)); GLF(void, ShaderSource, (GLuint, GLsizei, const char *const *, const GLint *));
GLF(void, CompileShader, (GLuint)); GLF(void, GetShaderiv, (GLuint, GLenum, GLint *)); GLF(GLuint, CreateProgram, (void)); GLF(void, AttachShader, (GLuint, GLuint));
GLF(void, LinkProgram, (GLuint)); GLF(void, GetProgramiv, (GLuint, GLenum, GLint *)); GLF(void, UseProgram, (GLuint)); GLF(GLint, GetUniformLocation, (GLuint, const char *));
GLF(void, Uniform1i, (GLint, GLint)); GLF(void, Uniform1f, (GLint, float)); GLF(void, Uniform4f, (GLint, float, float, float, float));
GLF(void, ActiveTexture, (GLenum)); GLF(void, DrawArrays, (GLenum, GLint, GLsizei)); GLF(void, Viewport, (GLint, GLint, GLsizei, GLsizei));
GLF(void, GetIntegerv, (GLenum, GLint *)); GLF(GLboolean, IsEnabled, (GLenum)); GLF(void, Enable, (GLenum)); GLF(void, Disable, (GLenum));
GLF(void, BindFramebuffer, (GLenum, GLuint)); GLF(void, BindVertexArray, (GLuint)); GLF(void, ColorMask, (GLboolean, GLboolean, GLboolean, GLboolean));
GLF(void, GetBooleanv, (GLenum, GLboolean *));
GLF(void, TexImage2D, (GLenum, GLint, GLint, GLsizei, GLsizei, GLint, GLenum, GLenum, const void *)); GLF(void, BlendFunc, (GLenum, GLenum));
static void *(*egl_GetCurrentContext)(void); static void *(*egl_GetCurrentDisplay)(void); static void *(*egl_GetCurrentSurface)(GLint);
static GLboolean (*egl_QuerySurface)(void *, void *, GLint, GLint *); static GLboolean (*egl_SwapBuffers)(void *, void *);

static int g_gl_ok;
static int load_gl(void)
{
	if (g_gl_ok) return 1;
#define L(n) do { *(void **)&gl_##n = tl_egl_resolve("gl" #n); if (!gl_##n) { OLFX_LOG("GL function missing: gl" #n); return 0; } } while (0)
	L(GenTextures); L(DeleteTextures); L(BindTexture); L(TexStorage2D); L(TexStorage3D); L(TexParameteri); L(CreateShader); L(ShaderSource); L(CompileShader);
	L(GetShaderiv); L(CreateProgram); L(AttachShader); L(LinkProgram); L(GetProgramiv); L(UseProgram); L(GetUniformLocation); L(Uniform1i); L(Uniform1f);
	L(Uniform4f); L(ActiveTexture); L(DrawArrays); L(Viewport); L(GetIntegerv); L(IsEnabled); L(Enable); L(Disable); L(BindFramebuffer); L(BindVertexArray);
	L(ColorMask); L(GetBooleanv); L(TexImage2D); L(BlendFunc);
#undef L
#define LE(n) do { *(void **)&egl_##n = tl_egl_resolve("egl" #n); if (!egl_##n) { OLFX_LOG("EGL function missing: egl" #n); return 0; } } while (0)
	LE(GetCurrentContext); LE(GetCurrentDisplay); LE(GetCurrentSurface); LE(QuerySurface); LE(SwapBuffers);
#undef LE
	g_gl_ok = 1; return 1;
}

// Swapchain formats offered to the game (GL enums), colour first, then depth.
static const int64_t k_formats[] = { GL_SRGB8_ALPHA8, GL_RGBA8, GL_RGBA16F, GL_DEPTH24_STENCIL8, GL_DEPTH_COMPONENT24, GL_DEPTH_COMPONENT32F, GL_DEPTH_COMPONENT16, GL_DEPTH32F_STENCIL8 };
#define NFORMATS ((uint32_t)(sizeof k_formats / sizeof k_formats[0]))
#define NIMG 3
typedef struct { int64_t format; uint32_t w, h, array, mips; GLuint tex[NIMG]; int made; int next, acquired; uint64_t usage; olfx_vk_chain vk; } swap_t;
static int g_vulkan;      // the session uses Vulkan (else OpenGL ES)

olfx_vk_chain *olfx_gfx_chain_of(XrSwapchain sc, int *acq) { swap_t *s = (swap_t *)sc; if (!s) return NULL; *acq = s->acquired; return &s->vk; }

int olfx_gfx_session_ok(const XrSessionCreateInfo *ci)
{
	int saw_gles = 0;
	for (const XrBaseInStructure *n = ci->next; n; n = n->next) {
		OLFX_LOG("xrCreateSession graphics binding type=%d", (int)n->type);
		if ((int)n->type == OLFX_TYPE_GRAPHICS_BINDING_VULKAN) { g_vulkan = 1; if (olfx_vk_session(n)) return 1; return 0; }
		if ((int)n->type == OLFX_TYPE_GRAPHICS_BINDING_GLES_ANDROID) saw_gles = 1;
	}
	if (saw_gles) g_vulkan = 0;
	if (!saw_gles && !g_vulkan) OLFX_LOG("no graphics binding in xrCreateSession (headless)");
	return 0;
}

OLFX_FN(xrGetOpenGLESGraphicsRequirementsKHR)(XrInstance i, XrSystemId id, olfx_gfx_req_gles *r)
{ (void)i; (void)id; r->minApiVersionSupported = XR_MAKE_VERSION(3, 0, 0); r->maxApiVersionSupported = XR_MAKE_VERSION(3, 2, 0); return XR_SUCCESS; }
OLFX_FN(xrEnumerateSwapchainFormats)(XrSession s, uint32_t cap, uint32_t *count, int64_t *f)
{
	(void)s; const int64_t *fm = k_formats; uint32_t nf = NFORMATS;
	if (g_vulkan) nf = olfx_vk_formats(&fm);
	*count = nf; if (cap == 0) return XR_SUCCESS; if (cap < nf) return XR_ERROR_SIZE_INSUFFICIENT;
	memcpy(f, fm, nf * sizeof(int64_t)); return XR_SUCCESS;
}
OLFX_FN(xrCreateSwapchain)(XrSession s, const XrSwapchainCreateInfo *ci, XrSwapchain *out)
{
	(void)s; swap_t *sw = calloc(1, sizeof *sw);
	sw->vk.format = ci->format; sw->usage = ci->usageFlags; sw->format = ci->format; sw->w = ci->width; sw->h = ci->height; sw->array = ci->arraySize ? ci->arraySize : 1; sw->mips = ci->mipCount ? ci->mipCount : 1;
	sw->vk.w = sw->w; sw->vk.h = sw->h; sw->vk.array = sw->array; sw->vk.mips = sw->mips;
	OLFX_LOG("xrCreateSwapchain %ux%u format=0x%llx array=%u mips=%u usage=0x%llx", sw->w, sw->h, (unsigned long long)sw->format, sw->array, sw->mips, (unsigned long long)ci->usageFlags);
	*out = (XrSwapchain)sw; return XR_SUCCESS;
}
OLFX_FN(xrDestroySwapchain)(XrSwapchain h)
{
	swap_t *sw = (swap_t *)h;
	if (g_vulkan) olfx_vk_free(&sw->vk);
	if (sw->made && g_gl_ok && egl_GetCurrentContext()) gl_DeleteTextures(NIMG, sw->tex);
	free(sw); return XR_SUCCESS;
}
OLFX_FN(xrEnumerateSwapchainImages)(XrSwapchain h, uint32_t cap, uint32_t *count, olfx_swapchain_image_gles *img)
{
	swap_t *sw = (swap_t *)h; *count = NIMG; if (cap == 0) return XR_SUCCESS; if (cap < NIMG) return XR_ERROR_SIZE_INSUFFICIENT;
	if (g_vulkan) { XrResult r = olfx_vk_make_images(&sw->vk, sw->usage); if (r == XR_SUCCESS) olfx_vk_fill_images(&sw->vk, img, NIMG); return r; }
	if (!sw->made) {
		if (!load_gl() || !egl_GetCurrentContext()) { OLFX_LOG("xrEnumerateSwapchainImages: no GL context current on this thread"); return XR_ERROR_RUNTIME_FAILURE; }
		GLenum target = sw->array > 1 ? GL_TEXTURE_2D_ARRAY : GL_TEXTURE_2D;
		GLint prev = 0; gl_GetIntegerv(sw->array > 1 ? GL_TEXTURE_BINDING_2D_ARRAY : GL_TEXTURE_BINDING_2D, &prev);
		gl_GenTextures(NIMG, sw->tex);
		for (int k = 0; k < NIMG; k++) {
			gl_BindTexture(target, sw->tex[k]);
			if (sw->array > 1) gl_TexStorage3D(target, sw->mips, (GLenum)sw->format, sw->w, sw->h, sw->array);
			else gl_TexStorage2D(target, sw->mips, (GLenum)sw->format, sw->w, sw->h);
			gl_TexParameteri(target, GL_TEXTURE_MIN_FILTER, GL_LINEAR); gl_TexParameteri(target, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
			gl_TexParameteri(target, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE); gl_TexParameteri(target, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		}
		gl_BindTexture(target, (GLuint)prev);
		sw->made = 1;
	}
	for (int k = 0; k < NIMG; k++) { img[k].type = (XrStructureType)OLFX_TYPE_SWAPCHAIN_IMAGE_GLES; img[k].image = sw->tex[k]; }
	return XR_SUCCESS;
}
OLFX_FN(xrAcquireSwapchainImage)(XrSwapchain h, const XrSwapchainImageAcquireInfo *ai, uint32_t *index)
{ (void)ai; olfx_trace("xrAcquireSwapchainImage"); swap_t *sw = (swap_t *)h; sw->acquired = sw->next; *index = (uint32_t)sw->next; sw->next = (sw->next + 1) % NIMG; return XR_SUCCESS; }
OLFX_FN(xrWaitSwapchainImage)(XrSwapchain h, const XrSwapchainImageWaitInfo *wi) { (void)h; (void)wi; return XR_SUCCESS; }
OLFX_FN(xrReleaseSwapchainImage)(XrSwapchain h, const XrSwapchainImageReleaseInfo *ri) { (void)h; (void)ri; return XR_SUCCESS; }

// ---------- compositor ----------
static GLuint g_prog[2], g_u_rect[2], g_u_tex[2], g_u_layer, g_u_gamma[2], g_u_alpha[2];
static GLuint g_cam_tex; static uint8_t *g_cam_buf; static size_t g_cam_cap; static int g_cam_w, g_cam_h; static uint32_t g_cam_seq;
static const char *k_vs =
	"#version 300 es\nuniform vec4 uRect; out vec2 vUv;\n"
	"void main(){ vec2 p = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1)); vUv = uRect.xy + p * uRect.zw; gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0); }\n";
static const char *k_fs2d =
	"#version 300 es\nprecision highp float; uniform sampler2D uTex; uniform float uGamma; uniform float uAlpha; in vec2 vUv; out vec4 o;\n"
	"void main(){ vec4 c = texture(uTex, vUv); o = vec4(uGamma > 0.5 ? pow(c.rgb, vec3(1.0/2.2)) : c.rgb, uAlpha > 0.5 ? c.a : 1.0); }\n";
static const char *k_fsarr =
	"#version 300 es\nprecision highp float; precision highp sampler2DArray; uniform sampler2DArray uTex; uniform float uLayer; uniform float uGamma; uniform float uAlpha; in vec2 vUv; out vec4 o;\n"
	"void main(){ vec4 c = texture(uTex, vec3(vUv, uLayer)); o = vec4(uGamma > 0.5 ? pow(c.rgb, vec3(1.0/2.2)) : c.rgb, uAlpha > 0.5 ? c.a : 1.0); }\n";

static GLuint compile(GLenum type, const char *src)
{
	GLuint s = gl_CreateShader(type); gl_ShaderSource(s, 1, &src, NULL); gl_CompileShader(s);
	GLint ok = 0; gl_GetShaderiv(s, GL_COMPILE_STATUS, &ok); if (!ok) OLFX_LOG("compositor shader failed to compile");
	return s;
}
static int build_programs(void)
{
	if (g_prog[0]) return 1;
	const char *fs[2] = { k_fs2d, k_fsarr };
	for (int k = 0; k < 2; k++) {
		GLuint p = gl_CreateProgram(); gl_AttachShader(p, compile(GL_VERTEX_SHADER, k_vs)); gl_AttachShader(p, compile(GL_FRAGMENT_SHADER, fs[k])); gl_LinkProgram(p);
		GLint ok = 0; gl_GetProgramiv(p, GL_LINK_STATUS, &ok); if (!ok) { OLFX_LOG("compositor program failed to link"); return 0; }
		g_prog[k] = p; g_u_rect[k] = gl_GetUniformLocation(p, "uRect"); g_u_tex[k] = gl_GetUniformLocation(p, "uTex"); g_u_gamma[k] = gl_GetUniformLocation(p, "uGamma"); g_u_alpha[k] = gl_GetUniformLocation(p, "uAlpha");
		if (k == 1) g_u_layer = gl_GetUniformLocation(p, "uLayer");
	}
	return 1;
}

typedef struct { GLint prog, atex, tex2d, texarr, fbo, vao, vp[4]; GLboolean en[5]; GLboolean mask[4]; } glstate;
static const GLenum k_caps[5] = { GL_DEPTH_TEST, GL_CULL_FACE, GL_BLEND, GL_SCISSOR_TEST, GL_STENCIL_TEST };

void olfx_gfx_end_frame(const XrFrameEndInfo *ei)
{
	static int once_layers, once_nogl;
	if (g_vulkan) { olfx_vk_end_frame(ei); return; }
	if (!once_layers++) OLFX_LOG("xrEndFrame: %u layer(s) submitted", ei->layerCount);
	const XrCompositionLayerProjection *proj = NULL;
	for (uint32_t i = 0; i < ei->layerCount; i++)
		if (ei->layers[i] && ei->layers[i]->type == XR_TYPE_COMPOSITION_LAYER_PROJECTION) { proj = (const XrCompositionLayerProjection *)ei->layers[i]; break; }
	int pt = olfx_pt_active();
	int blend = 0, premult = 1;      // composite the game's alpha over the camera (passthrough)
	if (pt && proj && (proj->layerFlags & XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT)) { blend = 1; premult = !(proj->layerFlags & XR_COMPOSITION_LAYER_UNPREMULTIPLIED_ALPHA_BIT); }
	if (!proj || proj->viewCount < 2) return;
	if (!load_gl() || !egl_GetCurrentContext()) { if (!once_nogl++) OLFX_LOG("compositor: no GL context on the thread calling xrEndFrame"); return; }
	if (!build_programs()) return;

	// XR applications render into swapchains and may never swap an Android window.
	// Present both direct OpenXR and OVRPlugin frames explicitly.
	GLint W = 0, H = 0;
	int screen = tl_egl_screen_begin(&W, &H);
	if (!screen) { static int once_scr; if (!once_scr++) OLFX_LOG("compositor: no screen surface to draw on"); return; }
	static int once_ok; if (!once_ok++) OLFX_LOG("compositor: drawing both eyes on the screen surface %dx%d", W, H);
	if (W <= 0 || H <= 0) { if (screen) tl_egl_screen_end(); return; }

	glstate st;
	gl_GetIntegerv(GL_CURRENT_PROGRAM, &st.prog); gl_GetIntegerv(GL_ACTIVE_TEXTURE, &st.atex); gl_GetIntegerv(GL_FRAMEBUFFER_BINDING, &st.fbo);
	gl_GetIntegerv(GL_VERTEX_ARRAY_BINDING, &st.vao); gl_GetIntegerv(GL_VIEWPORT, st.vp); gl_GetBooleanv(GL_COLOR_WRITEMASK, st.mask);
	gl_ActiveTexture(GL_TEXTURE0); gl_GetIntegerv(GL_TEXTURE_BINDING_2D, &st.tex2d); gl_GetIntegerv(GL_TEXTURE_BINDING_2D_ARRAY, &st.texarr);
	for (int k = 0; k < 5; k++) { st.en[k] = gl_IsEnabled(k_caps[k]); gl_Disable(k_caps[k]); }

	gl_BindFramebuffer(GL_FRAMEBUFFER, 0); gl_BindVertexArray(0); gl_ColorMask(1, 1, 1, 1);
	int cam = 0;
	if (pt) {
		int f = olfx_pt_fetch(&g_cam_buf, &g_cam_cap, &g_cam_w, &g_cam_h, &g_cam_seq);
		if (!g_cam_tex) { gl_GenTextures(1, &g_cam_tex); gl_BindTexture(GL_TEXTURE_2D, g_cam_tex);
			gl_TexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR); gl_TexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
			gl_TexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE); gl_TexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE); }
		if (f > 0) { gl_BindTexture(GL_TEXTURE_2D, g_cam_tex); gl_TexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, g_cam_w, g_cam_h, 0, GL_RGBA, GL_UNSIGNED_BYTE, g_cam_buf); }
		cam = g_cam_w > 0;     // the buffer is stored as RGBA (Swift swaps the channels)
		static int once_cam; if (!once_cam++) OLFX_LOG("compositor: passthrough on, camera frame %s", cam ? "available" : "not yet available");
		if (cam) {
			float ea = (W / 2.f) / (float)H, ca = (float)g_cam_w / (float)g_cam_h, sx = 1, sy = 1;
			if (ca > ea) sx = ea / ca; else sy = ca / ea;
			for (int e = 0; e < 2; e++) {
				gl_UseProgram(g_prog[0]); gl_BindTexture(GL_TEXTURE_2D, g_cam_tex); gl_Uniform1i(g_u_tex[0], 0); gl_Uniform1f(g_u_gamma[0], 0.f); gl_Uniform1f(g_u_alpha[0], 0.f);
				gl_Uniform4f(g_u_rect[0], (1 - sx) / 2, (1 - sy) / 2 + sy, sx, -sy);       // cover the eye; flipped so the picture is upright
				gl_Viewport(e == 0 ? 0 : W / 2, 0, W / 2, H);
				gl_DrawArrays(GL_TRIANGLE_STRIP, 0, 4);
			}
		}
	}
	if (cam && blend) { gl_Enable(GL_BLEND); gl_BlendFunc(premult ? GL_ONE : GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA); }
	else if (cam && !blend) { /* passthrough layer without alpha blending: the game covers the camera, nothing to composite */ }
	int eyes_drawn = 0;
	for (int e = 0; e < 2; e++) {
		const XrCompositionLayerProjectionView *v = &proj->views[e];
		const swap_t *sw = (const swap_t *)v->subImage.swapchain; if (!sw || !sw->made) continue;
		int arr = sw->array > 1, k = arr ? 1 : 0;
		int idx = (sw->acquired + 0) % NIMG;      // the image released this frame (the one acquired last)
		gl_UseProgram(g_prog[k]);
		gl_BindTexture(arr ? GL_TEXTURE_2D_ARRAY : GL_TEXTURE_2D, sw->tex[idx]);
		gl_Uniform1i(g_u_tex[k], 0);
		gl_Uniform1f(g_u_gamma[k], sw->format == GL_SRGB8_ALPHA8 ? 1.f : 0.f); gl_Uniform1f(g_u_alpha[k], (cam && blend) ? 1.f : 0.f);
		if (arr) gl_Uniform1f(g_u_layer, (float)v->subImage.imageArrayIndex);
		const XrRect2Di *r = &v->subImage.imageRect;
		float rw = r->extent.width ? (float)r->extent.width : (float)sw->w, rh = r->extent.height ? (float)r->extent.height : (float)sw->h;
		gl_Uniform4f(g_u_rect[k], r->offset.x / (float)sw->w, r->offset.y / (float)sw->h, rw / sw->w, rh / sw->h);
		gl_Viewport(e == 0 ? 0 : W / 2, 0, W / 2, H);
		gl_DrawArrays(GL_TRIANGLE_STRIP, 0, 4);
		eyes_drawn++;
	}

	gl_Disable(GL_BLEND);
	gl_Viewport(st.vp[0], st.vp[1], st.vp[2], st.vp[3]); gl_ColorMask(st.mask[0], st.mask[1], st.mask[2], st.mask[3]);
	for (int k = 0; k < 5; k++) if (st.en[k]) gl_Enable(k_caps[k]);
	gl_BindTexture(GL_TEXTURE_2D, (GLuint)st.tex2d); gl_BindTexture(GL_TEXTURE_2D_ARRAY, (GLuint)st.texarr); gl_ActiveTexture((GLenum)st.atex);
	gl_UseProgram((GLuint)st.prog); gl_BindVertexArray((GLuint)st.vao); gl_BindFramebuffer(GL_FRAMEBUFFER, (GLuint)st.fbo);
	int swapped = tl_egl_screen_end();
	if (eyes_drawn == 2 && swapped) olfx_stereo_frame_presented();
}

const olfx_entry olfx_gfx_table[] = {
	OLFX_E(xrGetOpenGLESGraphicsRequirementsKHR), OLFX_E(xrEnumerateSwapchainFormats), OLFX_E(xrCreateSwapchain), OLFX_E(xrDestroySwapchain),
	OLFX_E(xrEnumerateSwapchainImages), OLFX_E(xrAcquireSwapchainImage), OLFX_E(xrWaitSwapchainImage), OLFX_E(xrReleaseSwapchainImage),
};
const int olfx_gfx_n = (int)(sizeof olfx_gfx_table / sizeof olfx_gfx_table[0]);
