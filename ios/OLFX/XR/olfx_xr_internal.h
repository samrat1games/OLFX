// Shared between the OLFX OpenXR runtime's files (core, graphics, input).
#ifndef OLFX_XR_INTERNAL_H
#define OLFX_XR_INTERNAL_H
#include <stdio.h>
#include <openxr/openxr.h>

#define OLFX_LOG(...) do { fprintf(stderr, "[olfx-xr] " __VA_ARGS__); fputc('\n', stderr); } while (0)
#define OLFX_FN(name) static XrResult XRAPI_CALL olfx_##name
#define OLFX_E(n) { #n, (PFN_xrVoidFunction)olfx_##n }

// OpenXR structure types from platform headers we do not include.
#define OLFX_TYPE_GRAPHICS_BINDING_GLES_ANDROID 1000024001
#define OLFX_TYPE_SWAPCHAIN_IMAGE_GLES          1000024002
#define OLFX_TYPE_GRAPHICS_REQUIREMENTS_GLES    1000024003
#define OLFX_TYPE_GRAPHICS_BINDING_VULKAN       1000025000
typedef struct { XrStructureType type; void *next; uint32_t image; } olfx_swapchain_image_gles;
typedef struct { XrStructureType type; void *next; XrVersion minApiVersionSupported, maxApiVersionSupported; } olfx_gfx_req_gles;

typedef struct { float m[16]; } olfx_mat4;      // column-major
olfx_mat4 olfx_m_identity(void);
olfx_mat4 olfx_m_inv(olfx_mat4 rigid);
olfx_mat4 olfx_m_mul(olfx_mat4 a, olfx_mat4 b);
void olfx_m_to_pose(olfx_mat4 a, XrPosef *p);
olfx_mat4 olfx_pose_to_m(const XrPosef *p);

typedef struct { float j[XR_HAND_JOINT_COUNT_EXT][8]; int active; } olfx_hand;   // world: px py pz qx qy qz qw radius
void olfx_get_hand(int hand, olfx_hand *out);
// v[0..14]: lx ly rx ry lTrig rTrig lGrip rGrip A B X Y menu lClick rClick; v[16..19]/v[20..23]: left/right controller orientation (x y z w, relative
// to the user's heading, from the Joy-Con gyro); v[24]/v[25]: that pose is valid.
typedef struct { float v[32]; } olfx_pad;
void olfx_get_pad(olfx_pad *out);

// Reference spaces and action (hand) spaces.
typedef struct { int is_action; XrReferenceSpaceType type; XrPosef offset; int hand; int is_aim; } olfx_space;
olfx_mat4 olfx_space_world(const olfx_space *s, int *valid);

void olfx_trace(const char *name);     // logs the first calls of a function, then every 1000th
typedef struct { int type; int arg; } olfx_event;
void olfx_push_event(int type, int arg);
XrTime olfx_now(void);
XrResult olfx_string_to_path(const char *s, XrPath *p);
const char *olfx_path_str(XrPath p);

typedef struct { const char *name; PFN_xrVoidFunction fn; } olfx_entry;
extern const olfx_entry olfx_gfx_table[];   extern const int olfx_gfx_n;
extern const olfx_entry olfx_input_table[]; extern const int olfx_input_n;
extern const olfx_entry olfx_pt_table[]; extern const int olfx_pt_n;     // XR_FB_passthrough (olfx_xr_pt.c)
int olfx_pt_active(void);
int olfx_pt_fetch(uint8_t **dst, size_t *cap, int *w, int *h, uint32_t *seq);

// Vulkan (olfx_xr_vk.c): per-swapchain images, owned by the swapchain object in olfx_xr_gfx.c
typedef struct { uint64_t img[3], mem[3]; int64_t format; uint32_t w, h, array, mips; int made; } olfx_vk_chain;
extern const olfx_entry olfx_vk_table[]; extern const int olfx_vk_n;
int olfx_vk_session(const void *graphics_binding);                       // 0 = ok
uint32_t olfx_vk_formats(const int64_t **out);
XrResult olfx_vk_make_images(olfx_vk_chain *c, uint64_t xr_usage);
void olfx_vk_fill_images(const olfx_vk_chain *c, void *out, uint32_t cap);
void olfx_vk_free(olfx_vk_chain *c);
void olfx_vk_end_frame(const XrFrameEndInfo *ei);
olfx_vk_chain *olfx_gfx_chain_of(XrSwapchain sc, int *acquired_index);   // olfx_xr_gfx.c

// graphics (olfx_xr_gfx.c)
int olfx_gfx_session_ok(const XrSessionCreateInfo *ci);                     // 0 = ok
void olfx_stereo_frame_presented(void);
void olfx_gfx_end_frame(const XrFrameEndInfo *ei);
#endif
