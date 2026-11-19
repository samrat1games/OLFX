/* gl_session.c — OpenGL ES half of the OpenXR handshake (OLFX).
 *
 * The upstream shim was written for Unreal on Vulkan. Unity games (Oculus XR Plugin) on Husk/iOS render with GLES (ANGLE),
 * so ovrp_Initialize7 with an OpenGL ES api type creates the XrSession with XR_KHR_opengl_es_enable instead of
 * XR_KHR_vulkan_enable, and layer swapchains hand out GL texture names. XrSwapchainImageOpenGLESKHR enumeration needs the
 * game's GL context current on the calling thread, so it runs where the game calls ovrp_SetupLayer (its render thread).
 *
 * The OpenXR GL ES structs are declared here by value (type numbers from the OpenXR spec) so no EGL/GLES headers are needed.
 */
#include "xr_runtime.h"
#include "log.h"
#include <string.h>
#include <stdint.h>

/* EGL is present in every process that renders with GLES; under Husk the guest's imports are bound by its linker. */
extern void *eglGetCurrentDisplay(void);
extern void *eglGetCurrentContext(void);

#define OLFX_TYPE_GFX_BINDING_GLES_ANDROID  1000024001
#define OLFX_TYPE_SWAPCHAIN_IMAGE_GLES      1000024002
#define OLFX_TYPE_GFX_REQUIREMENTS_GLES     1000024003

typedef struct { XrStructureType type; const void *next; void *display; void *config; void *context; } GlesBinding;
typedef struct { XrStructureType type; void *next; uint32_t image; } GlesImage;
typedef struct { XrStructureType type; void *next; XrVersion minApi, maxApi; } GlesRequirements;

int xrr_gl_pending;
int xrr_gl_context_ready(void) { return eglGetCurrentDisplay() && eglGetCurrentContext(); }
int xrr_gl_mode;    /* 1 once ovrp_Initialize7 chose OpenGL ES */

int xrr_create_session_gles(void) {
    if (g_xr.instance == XR_NULL_HANDLE || g_xr.systemId == XR_NULL_SYSTEM_ID) return 0;

    /* required before session creation per XR_KHR_opengl_es_enable */
    PFN_xrVoidFunction req = NULL;
    xrGetInstanceProcAddr(g_xr.instance, "xrGetOpenGLESGraphicsRequirementsKHR", &req);
    if (req) {
        GlesRequirements gr = { (XrStructureType)OLFX_TYPE_GFX_REQUIREMENTS_GLES };
        XrResult rr = ((XrResult (*)(XrInstance, XrSystemId, GlesRequirements *))req)(g_xr.instance, g_xr.systemId, &gr);
        XRRLOG("gl_session: GraphicsRequirements rc=%d min=0x%llx max=0x%llx", (int)rr,
               (unsigned long long)gr.minApi, (unsigned long long)gr.maxApi);
    } else {
        XRRLOG("gl_session: runtime has no xrGetOpenGLESGraphicsRequirementsKHR");
    }

    GlesBinding b = { (XrStructureType)OLFX_TYPE_GFX_BINDING_GLES_ANDROID };
    b.display = eglGetCurrentDisplay();
    b.context = eglGetCurrentContext();
    XRRLOG("gl_session: binding display=%p context=%p (null context is fine here: images are made on the render thread)",
           b.display, b.context);

    XrSessionCreateInfo sci = { XR_TYPE_SESSION_CREATE_INFO };
    sci.next = &b;
    sci.systemId = g_xr.systemId;
    XrResult r = xrCreateSession(g_xr.instance, &sci, &g_xr.session);
    if (XR_FAILED(r)) {
        XRRERR("xrCreateSession(OpenGL ES) failed: %d", (int)r);
        g_xr.session = XR_NULL_HANDLE;
        return 0;
    }
    XRRLOG("gl_session: xrCreateSession OK session=%p", (void *)g_xr.session);
    return 1;
}

/* A swapchain's GL texture names, returned as the same opaque uint64 handles the Vulkan path uses. */
uint32_t xrr_gl_enumerate_images(XrSwapchain sc, uint64_t *out, uint32_t max) {
    uint32_t n = 0;
    if (xrEnumerateSwapchainImages(sc, 0, &n, NULL) != XR_SUCCESS || n == 0) return 0;
    if (n > max) n = max;
    GlesImage imgs[XRR_MAX_IMAGES];
    for (uint32_t i = 0; i < n; i++) { memset(&imgs[i], 0, sizeof imgs[i]); imgs[i].type = (XrStructureType)OLFX_TYPE_SWAPCHAIN_IMAGE_GLES; }
    uint32_t got = 0;
    XrResult r = xrEnumerateSwapchainImages(sc, n, &got, (XrSwapchainImageBaseHeader *)imgs);
    if (r != XR_SUCCESS) { XRRERR("gl_session: xrEnumerateSwapchainImages rc=%d (is the game's GL context current on this thread?)", (int)r); return 0; }
    for (uint32_t i = 0; i < got; i++) out[i] = imgs[i].image;
    XRRLOG("gl_session: %u GL swapchain images (first texture %llu)", got, got ? (unsigned long long)out[0] : 0ull);
    return got;
}

/* VkFormat (what xr_runtime's layer code speaks) -> GL internal format of the same pixels. 0 = unknown. */
int64_t xrr_gl_format_from_vk(int64_t vk) {
    switch (vk) {
        case 37: case 44: return 0x8058;   /* RGBA8 / BGRA8 unorm -> GL_RGBA8          */
        case 43: case 50: return 0x8C43;   /* RGBA8 / BGRA8 sRGB  -> GL_SRGB8_ALPHA8   */
        case 97:          return 0x881A;   /* RGBA16 sfloat       -> GL_RGBA16F        */
        case 122:         return 0x8C3A;   /* B10G11R11 ufloat    -> GL_R11F_G11F_B10F */
        case 4:           return 0x8D62;   /* R5G6B5              -> GL_RGB565         */
        default:          return 0;
    }
}
