// OLFX OpenXR: Vulkan (XR_KHR_vulkan_enable / enable2) on MoltenVK.
//
// Games built for Vulkan (most Quest Unity games) give the runtime their VkInstance/VkDevice; the runtime makes the eye images
// (VkImage on the game's device) and shows them. The compositor here blits the two eyes side by side into its own swapchain on a
// CAMetalLayer that the app puts above the game's view (olfx_xr_set_metal_layer). No shaders: vkCmdBlitImage does the work.
// The game's own window keeps presenting underneath; ours is on top.
#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_metal.h>
#define XR_USE_GRAPHICS_API_VULKAN
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>
#include "olfx_xr_internal.h"
#include <stdatomic.h>

static void *g_layer;                       // CAMetalLayer*
static char g_mvk_path[1024];
static PFN_vkGetInstanceProcAddr g_gipa;    // MoltenVK's own
static PFN_vkGetInstanceProcAddr g_game_gipa;   // the one the game gave us (Husk's wrapper), for creating instance/device

static _Atomic int g_vk_frame_visible;
int olfx_vk_frame_visible(void) { return atomic_load(&g_vk_frame_visible); }
void olfx_xr_set_metal_layer(void *layer) {
    g_layer = layer;
    if (layer) atomic_store(&g_vk_frame_visible, 0);
}
void olfx_xr_set_vulkan_lib(const char *path) { snprintf(g_mvk_path, sizeof g_mvk_path, "%s", path ? path : ""); }

static int load_gipa(void)
{
	if (g_gipa) return 1;
	void *h = g_mvk_path[0] ? dlopen(g_mvk_path, RTLD_NOW) : RTLD_DEFAULT;
	g_gipa = h ? (PFN_vkGetInstanceProcAddr)dlsym(h, "vkGetInstanceProcAddr") : NULL;
	if (!g_gipa) OLFX_LOG("Vulkan: MoltenVK not found (%s)", g_mvk_path);
	return g_gipa != NULL;
}

// ---------- session state ----------
static struct {
	VkInstance inst; VkPhysicalDevice phys; VkDevice dev; uint32_t qfam, qidx; VkQueue queue; int ready;
	PFN_vkGetDeviceProcAddr gdpa;
	PFN_vkCreateImage CreateImage; PFN_vkDestroyImage DestroyImage; PFN_vkGetImageMemoryRequirements GetImgMem; PFN_vkAllocateMemory Alloc;
	PFN_vkFreeMemory Free; PFN_vkBindImageMemory Bind; PFN_vkGetPhysicalDeviceMemoryProperties MemProps;
	PFN_vkCreateCommandPool CreatePool; PFN_vkAllocateCommandBuffers AllocCb; PFN_vkBeginCommandBuffer Begin; PFN_vkEndCommandBuffer End;
	PFN_vkCmdPipelineBarrier Barrier; PFN_vkCmdBlitImage Blit; PFN_vkQueueSubmit Submit; PFN_vkQueueWaitIdle WaitIdle; PFN_vkResetCommandBuffer ResetCb;
	PFN_vkCreateSemaphore CreateSem; PFN_vkCreateFence CreateFence; PFN_vkWaitForFences WaitFences; PFN_vkResetFences ResetFences;
	PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR SurfCaps; PFN_vkGetPhysicalDeviceSurfaceFormatsKHR SurfFormats;
	PFN_vkCreateSwapchainKHR CreateSwap; PFN_vkDestroySwapchainKHR DestroySwap; PFN_vkGetSwapchainImagesKHR SwapImages;
	PFN_vkAcquireNextImageKHR Acquire; PFN_vkQueuePresentKHR Present; PFN_vkCreateMetalSurfaceEXT CreateMetalSurface;
	PFN_vkDeviceWaitIdle DevWaitIdle;
} V;

static void load_device_fns(void)
{
	V.gdpa = (PFN_vkGetDeviceProcAddr)g_gipa(V.inst, "vkGetDeviceProcAddr");
#define D(n) V.n = (PFN_vk##n)V.gdpa(V.dev, "vk" #n)
	V.CreateImage = (PFN_vkCreateImage)V.gdpa(V.dev, "vkCreateImage"); V.DestroyImage = (PFN_vkDestroyImage)V.gdpa(V.dev, "vkDestroyImage");
	V.GetImgMem = (PFN_vkGetImageMemoryRequirements)V.gdpa(V.dev, "vkGetImageMemoryRequirements");
	V.Alloc = (PFN_vkAllocateMemory)V.gdpa(V.dev, "vkAllocateMemory"); V.Free = (PFN_vkFreeMemory)V.gdpa(V.dev, "vkFreeMemory");
	V.Bind = (PFN_vkBindImageMemory)V.gdpa(V.dev, "vkBindImageMemory");
	V.CreatePool = (PFN_vkCreateCommandPool)V.gdpa(V.dev, "vkCreateCommandPool"); V.AllocCb = (PFN_vkAllocateCommandBuffers)V.gdpa(V.dev, "vkAllocateCommandBuffers");
	V.Begin = (PFN_vkBeginCommandBuffer)V.gdpa(V.dev, "vkBeginCommandBuffer"); V.End = (PFN_vkEndCommandBuffer)V.gdpa(V.dev, "vkEndCommandBuffer");
	V.ResetCb = (PFN_vkResetCommandBuffer)V.gdpa(V.dev, "vkResetCommandBuffer");
	V.Barrier = (PFN_vkCmdPipelineBarrier)V.gdpa(V.dev, "vkCmdPipelineBarrier"); V.Blit = (PFN_vkCmdBlitImage)V.gdpa(V.dev, "vkCmdBlitImage");
	V.Submit = (PFN_vkQueueSubmit)V.gdpa(V.dev, "vkQueueSubmit"); V.WaitIdle = (PFN_vkQueueWaitIdle)V.gdpa(V.dev, "vkQueueWaitIdle");
	V.CreateSem = (PFN_vkCreateSemaphore)V.gdpa(V.dev, "vkCreateSemaphore"); V.CreateFence = (PFN_vkCreateFence)V.gdpa(V.dev, "vkCreateFence");
	V.WaitFences = (PFN_vkWaitForFences)V.gdpa(V.dev, "vkWaitForFences"); V.ResetFences = (PFN_vkResetFences)V.gdpa(V.dev, "vkResetFences");
	V.CreateSwap = (PFN_vkCreateSwapchainKHR)V.gdpa(V.dev, "vkCreateSwapchainKHR"); V.DestroySwap = (PFN_vkDestroySwapchainKHR)V.gdpa(V.dev, "vkDestroySwapchainKHR");
	V.SwapImages = (PFN_vkGetSwapchainImagesKHR)V.gdpa(V.dev, "vkGetSwapchainImagesKHR"); V.Acquire = (PFN_vkAcquireNextImageKHR)V.gdpa(V.dev, "vkAcquireNextImageKHR");
	V.Present = (PFN_vkQueuePresentKHR)V.gdpa(V.dev, "vkQueuePresentKHR"); V.DevWaitIdle = (PFN_vkDeviceWaitIdle)V.gdpa(V.dev, "vkDeviceWaitIdle");
	V.MemProps = (PFN_vkGetPhysicalDeviceMemoryProperties)g_gipa(V.inst, "vkGetPhysicalDeviceMemoryProperties");
	V.SurfCaps = (PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR)g_gipa(V.inst, "vkGetPhysicalDeviceSurfaceCapabilitiesKHR");
	V.SurfFormats = (PFN_vkGetPhysicalDeviceSurfaceFormatsKHR)g_gipa(V.inst, "vkGetPhysicalDeviceSurfaceFormatsKHR");
	V.CreateMetalSurface = (PFN_vkCreateMetalSurfaceEXT)g_gipa(V.inst, "vkCreateMetalSurfaceEXT");
	PFN_vkGetDeviceQueue gq = (PFN_vkGetDeviceQueue)V.gdpa(V.dev, "vkGetDeviceQueue"); gq(V.dev, V.qfam, V.qidx, &V.queue);
#undef D
}

// ---------- extension negotiation ----------
static XrResult fill_string(const char *s, uint32_t cap, uint32_t *count, char *buf)
{
	uint32_t n = (uint32_t)strlen(s) + 1; *count = n;
	if (cap == 0) return XR_SUCCESS; if (cap < n) return XR_ERROR_SIZE_INSUFFICIENT; memcpy(buf, s, n); return XR_SUCCESS;
}
#define INST_EXTS "VK_KHR_surface VK_EXT_metal_surface"
#define DEV_EXTS "VK_KHR_swapchain"

OLFX_FN(xrGetVulkanInstanceExtensionsKHR)(XrInstance i, XrSystemId s, uint32_t cap, uint32_t *count, char *buf) { (void)i; (void)s; return fill_string(INST_EXTS, cap, count, buf); }
OLFX_FN(xrGetVulkanDeviceExtensionsKHR)(XrInstance i, XrSystemId s, uint32_t cap, uint32_t *count, char *buf) { (void)i; (void)s; return fill_string(DEV_EXTS, cap, count, buf); }
OLFX_FN(xrGetVulkanGraphicsRequirementsKHR)(XrInstance i, XrSystemId s, XrGraphicsRequirementsVulkanKHR *r)
{ (void)i; (void)s; r->minApiVersionSupported = XR_MAKE_VERSION(1, 0, 0); r->maxApiVersionSupported = XR_MAKE_VERSION(1, 3, 0); return XR_SUCCESS; }

static VkPhysicalDevice first_physical(VkInstance inst)
{
	if (!load_gipa()) return VK_NULL_HANDLE;
	PFN_vkEnumeratePhysicalDevices en = (PFN_vkEnumeratePhysicalDevices)g_gipa(inst, "vkEnumeratePhysicalDevices");
	uint32_t n = 1; VkPhysicalDevice pd = VK_NULL_HANDLE; if (en) en(inst, &n, &pd); return pd;
}
OLFX_FN(xrGetVulkanGraphicsDeviceKHR)(XrInstance i, XrSystemId s, VkInstance vi, VkPhysicalDevice *out)
{ (void)i; (void)s; *out = first_physical(vi); return *out ? XR_SUCCESS : XR_ERROR_RUNTIME_FAILURE; }
OLFX_FN(xrGetVulkanGraphicsDevice2KHR)(XrInstance i, const XrVulkanGraphicsDeviceGetInfoKHR *gi, VkPhysicalDevice *out)
{ (void)i; *out = first_physical(gi->vulkanInstance); return *out ? XR_SUCCESS : XR_ERROR_RUNTIME_FAILURE; }

// enable2: the runtime creates the instance/device, adding what it needs.
OLFX_FN(xrCreateVulkanInstanceKHR)(XrInstance i, const XrVulkanInstanceCreateInfoKHR *ci, VkInstance *out, VkResult *vr)
{
	(void)i; load_gipa(); g_game_gipa = ci->pfnGetInstanceProcAddr;
	VkInstanceCreateInfo c = *ci->vulkanCreateInfo;
	const char **ext = calloc(c.enabledExtensionCount + 2, sizeof(char *)); uint32_t n = 0; int surf = 0, metal = 0;
	for (uint32_t k = 0; k < c.enabledExtensionCount; k++) { ext[n++] = c.ppEnabledExtensionNames[k]; if (!strcmp(ext[n-1], "VK_KHR_surface")) surf = 1; if (!strcmp(ext[n-1], "VK_EXT_metal_surface")) metal = 1; }
	if (!surf) ext[n++] = "VK_KHR_surface";
	if (!metal) ext[n++] = "VK_EXT_metal_surface";
	c.enabledExtensionCount = n; c.ppEnabledExtensionNames = ext;
	PFN_vkCreateInstance create = (PFN_vkCreateInstance)g_game_gipa(VK_NULL_HANDLE, "vkCreateInstance");
	VkResult r = create ? create(&c, ci->vulkanAllocator, out) : VK_ERROR_INITIALIZATION_FAILED;
	free(ext); if (vr) *vr = r;
	OLFX_LOG("xrCreateVulkanInstanceKHR -> %d", (int)r);
	return r == VK_SUCCESS ? XR_SUCCESS : XR_ERROR_VALIDATION_FAILURE;
}
OLFX_FN(xrCreateVulkanDeviceKHR)(XrInstance i, const XrVulkanDeviceCreateInfoKHR *ci, VkDevice *out, VkResult *vr)
{
	(void)i; load_gipa(); VkDeviceCreateInfo c = *ci->vulkanCreateInfo;
	const char **ext = calloc(c.enabledExtensionCount + 1, sizeof(char *)); uint32_t n = 0; int sw = 0;
	for (uint32_t k = 0; k < c.enabledExtensionCount; k++) { ext[n++] = c.ppEnabledExtensionNames[k]; if (!strcmp(ext[n-1], "VK_KHR_swapchain")) sw = 1; }
	if (!sw) ext[n++] = "VK_KHR_swapchain";
	c.enabledExtensionCount = n; c.ppEnabledExtensionNames = ext;
	PFN_vkCreateDevice create = (PFN_vkCreateDevice)ci->pfnGetInstanceProcAddr(V.inst ? V.inst : VK_NULL_HANDLE, "vkCreateDevice");
	VkResult r = create ? create(ci->vulkanPhysicalDevice, &c, ci->vulkanAllocator, out) : VK_ERROR_INITIALIZATION_FAILED;
	free(ext); if (vr) *vr = r;
	OLFX_LOG("xrCreateVulkanDeviceKHR -> %d", (int)r);
	return r == VK_SUCCESS ? XR_SUCCESS : XR_ERROR_VALIDATION_FAILURE;
}

int olfx_vk_session(const void *binding)
{
	const XrGraphicsBindingVulkanKHR *b = binding;
	V.inst = b->instance; V.phys = b->physicalDevice; V.dev = b->device; V.qfam = b->queueFamilyIndex; V.qidx = b->queueIndex;
	if (!load_gipa()) return 1;
	load_device_fns(); V.ready = 1;
	OLFX_LOG("Vulkan session: queue family %u index %u", V.qfam, V.qidx);
	return 0;
}

// ---------- swapchain images ----------
static const int64_t k_formats[] = { VK_FORMAT_R8G8B8A8_SRGB, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_B8G8R8A8_SRGB, VK_FORMAT_B8G8R8A8_UNORM, VK_FORMAT_R16G16B16A16_SFLOAT,
                                      VK_FORMAT_D32_SFLOAT, VK_FORMAT_D32_SFLOAT_S8_UINT, VK_FORMAT_D16_UNORM };
uint32_t olfx_vk_formats(const int64_t **out) { *out = k_formats; return (uint32_t)(sizeof k_formats / sizeof k_formats[0]); }

static int is_depth(int64_t f) { return f == VK_FORMAT_D32_SFLOAT || f == VK_FORMAT_D32_SFLOAT_S8_UINT || f == VK_FORMAT_D16_UNORM || f == VK_FORMAT_D24_UNORM_S8_UINT; }
static VkImageAspectFlags aspect_of(int64_t f) { return !is_depth(f) ? VK_IMAGE_ASPECT_COLOR_BIT : (f == VK_FORMAT_D32_SFLOAT_S8_UINT || f == VK_FORMAT_D24_UNORM_S8_UINT) ? (VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT) : VK_IMAGE_ASPECT_DEPTH_BIT; }

static VkCommandPool g_pool; static VkCommandBuffer g_cb; static VkFence g_fence; static int g_cmd_ok;
static int ensure_cmd(void)
{
	if (g_cmd_ok) return 1;
	VkCommandPoolCreateInfo pi = { VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO, NULL, VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT, V.qfam };
	if (V.CreatePool(V.dev, &pi, NULL, &g_pool) != VK_SUCCESS) return 0;
	VkCommandBufferAllocateInfo ai = { VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO, NULL, g_pool, VK_COMMAND_BUFFER_LEVEL_PRIMARY, 1 };
	if (V.AllocCb(V.dev, &ai, &g_cb) != VK_SUCCESS) return 0;
	VkFenceCreateInfo fi = { VK_STRUCTURE_TYPE_FENCE_CREATE_INFO, NULL, VK_FENCE_CREATE_SIGNALED_BIT };
	if (V.CreateFence(V.dev, &fi, NULL, &g_fence) != VK_SUCCESS) return 0;
	g_cmd_ok = 1; return 1;
}

static void barrier(VkCommandBuffer cb, VkImage img, VkImageAspectFlags asp, uint32_t layer, uint32_t layers, uint32_t mips, VkImageLayout from, VkImageLayout to,
                    VkAccessFlags sa, VkAccessFlags da, VkPipelineStageFlags ss, VkPipelineStageFlags ds)
{
	VkImageMemoryBarrier b = { VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, NULL, sa, da, from, to, VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED, img, { asp, 0, mips, layer, layers } };
	V.Barrier(cb, ss, ds, 0, 0, NULL, 0, NULL, 1, &b);
}

XrResult olfx_vk_make_images(olfx_vk_chain *c, uint64_t xr_usage)
{
	if (c->made) return XR_SUCCESS;
	if (!V.ready || !ensure_cmd()) return XR_ERROR_RUNTIME_FAILURE;
	VkImageUsageFlags usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
	if (xr_usage & 0x1) usage |= VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
	if (xr_usage & 0x2) usage |= VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT;
	if (xr_usage & 0x4) usage |= VK_IMAGE_USAGE_STORAGE_BIT;
	if (is_depth(c->format)) { usage &= ~VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT; usage |= VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT; }
	else usage |= VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
	VkPhysicalDeviceMemoryProperties mp; V.MemProps(V.phys, &mp);
	V.WaitFences(V.dev, 1, &g_fence, VK_TRUE, UINT64_MAX); V.ResetFences(V.dev, 1, &g_fence);
	V.ResetCb(g_cb, 0);
	VkCommandBufferBeginInfo bi = { VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO, NULL, VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT, NULL };
	V.Begin(g_cb, &bi);
	for (int k = 0; k < 3; k++) {
		VkImageCreateInfo ii = { VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, NULL, (xr_usage & 0x40) ? VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT : 0, VK_IMAGE_TYPE_2D, (VkFormat)c->format,
			{ c->w, c->h, 1 }, c->mips, c->array, VK_SAMPLE_COUNT_1_BIT, VK_IMAGE_TILING_OPTIMAL, usage, VK_SHARING_MODE_EXCLUSIVE, 0, NULL, VK_IMAGE_LAYOUT_UNDEFINED };
		VkImage img; if (V.CreateImage(V.dev, &ii, NULL, &img) != VK_SUCCESS) { OLFX_LOG("vkCreateImage failed (format %lld)", (long long)c->format); return XR_ERROR_RUNTIME_FAILURE; }
		VkMemoryRequirements mr; V.GetImgMem(V.dev, img, &mr);
		uint32_t type = UINT32_MAX;
		for (uint32_t t = 0; t < mp.memoryTypeCount; t++) if ((mr.memoryTypeBits & (1u << t)) && (mp.memoryTypes[t].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)) { type = t; break; }
		if (type == UINT32_MAX) for (uint32_t t = 0; t < mp.memoryTypeCount; t++) if (mr.memoryTypeBits & (1u << t)) { type = t; break; }
		VkMemoryAllocateInfo mi = { VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, NULL, mr.size, type }; VkDeviceMemory mem;
		if (type == UINT32_MAX || V.Alloc(V.dev, &mi, NULL, &mem) != VK_SUCCESS) { OLFX_LOG("vkAllocateMemory failed"); return XR_ERROR_RUNTIME_FAILURE; }
		V.Bind(V.dev, img, mem, 0);
		c->img[k] = (uint64_t)img; c->mem[k] = (uint64_t)mem;
		// OpenXR: after xrWaitSwapchainImage the image is in COLOR_ATTACHMENT_OPTIMAL / DEPTH_STENCIL_ATTACHMENT_OPTIMAL
		barrier(g_cb, img, aspect_of(c->format), 0, c->array, c->mips, VK_IMAGE_LAYOUT_UNDEFINED,
		        is_depth(c->format) ? VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL : VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, 0,
		        is_depth(c->format) ? VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT : VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
		        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
	}
	V.End(g_cb);
	VkSubmitInfo si = { VK_STRUCTURE_TYPE_SUBMIT_INFO, NULL, 0, NULL, NULL, 1, &g_cb, 0, NULL };
	V.Submit(V.queue, 1, &si, g_fence); V.WaitFences(V.dev, 1, &g_fence, VK_TRUE, UINT64_MAX);
	c->made = 1; return XR_SUCCESS;
}
void olfx_vk_fill_images(const olfx_vk_chain *c, void *out, uint32_t cap)
{
	XrSwapchainImageVulkanKHR *o = out;
	for (uint32_t k = 0; k < cap && k < 3; k++) { o[k].type = XR_TYPE_SWAPCHAIN_IMAGE_VULKAN_KHR; o[k].image = (VkImage)c->img[k]; }
}
void olfx_vk_free(olfx_vk_chain *c)
{
	if (!c->made || !V.ready) return;
	V.DevWaitIdle(V.dev);
	for (int k = 0; k < 3; k++) { V.DestroyImage(V.dev, (VkImage)c->img[k], NULL); V.Free(V.dev, (VkDeviceMemory)c->mem[k], NULL); }
	c->made = 0;
}

// ---------- compositor: both eyes side by side onto the Metal layer ----------
static VkSurfaceKHR g_surf; static VkSwapchainKHR g_sc; static VkImage g_scimg[8]; static uint32_t g_scn; static VkExtent2D g_ext; static VkFormat g_scfmt;
static VkSemaphore g_acq, g_rel;

static int make_swapchain(int want_srgb)
{
	if (!g_layer || !V.CreateMetalSurface) return 0;
	if (!g_surf) {
		VkMetalSurfaceCreateInfoEXT mi = { VK_STRUCTURE_TYPE_METAL_SURFACE_CREATE_INFO_EXT, NULL, 0, g_layer };
		if (V.CreateMetalSurface(V.inst, &mi, NULL, &g_surf) != VK_SUCCESS) { OLFX_LOG("vkCreateMetalSurfaceEXT failed"); return 0; }
		VkSemaphoreCreateInfo si = { VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO }; V.CreateSem(V.dev, &si, NULL, &g_acq); V.CreateSem(V.dev, &si, NULL, &g_rel);
	}
	VkSurfaceCapabilitiesKHR caps; if (V.SurfCaps(V.phys, g_surf, &caps) != VK_SUCCESS) return 0;
	uint32_t nf = 0;
	if (V.SurfFormats(V.phys, g_surf, &nf, NULL) != VK_SUCCESS || nf == 0) return 0;
	VkSurfaceFormatKHR fm[16];
	if (nf > 16) nf = 16;
	VkResult format_result = V.SurfFormats(V.phys, g_surf, &nf, fm);
	if ((format_result != VK_SUCCESS && format_result != VK_INCOMPLETE) || nf == 0) return 0;
	VkSurfaceFormatKHR pick = fm[0];
	for (uint32_t k = 0; k < nf; k++) {
		int srgb = fm[k].format == VK_FORMAT_B8G8R8A8_SRGB || fm[k].format == VK_FORMAT_R8G8B8A8_SRGB;
		int rgba8 = srgb || fm[k].format == VK_FORMAT_B8G8R8A8_UNORM || fm[k].format == VK_FORMAT_R8G8B8A8_UNORM;
		if (rgba8 && srgb == want_srgb) { pick = fm[k]; break; }
	}
	g_ext = caps.currentExtent;
	if (g_ext.width == 0xFFFFFFFFu || g_ext.width == 0) { g_ext.width = 1688; g_ext.height = 780; }
	VkSwapchainCreateInfoKHR sc = { VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR, NULL, 0, g_surf, caps.minImageCount < 3 ? 3 : caps.minImageCount, pick.format, pick.colorSpace, g_ext, 1,
		VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT, VK_SHARING_MODE_EXCLUSIVE, 0, NULL, caps.currentTransform, VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR,
		VK_PRESENT_MODE_FIFO_KHR, VK_TRUE, g_sc };
	if (sc.minImageCount > caps.maxImageCount && caps.maxImageCount) sc.minImageCount = caps.maxImageCount;
	VkSwapchainKHR old = g_sc; VkSwapchainKHR nsc;
	VkResult r = V.CreateSwap(V.dev, &sc, NULL, &nsc);
	if (r != VK_SUCCESS) { OLFX_LOG("compositor: vkCreateSwapchainKHR failed (%d)", (int)r); return 0; }
	if (old) V.DestroySwap(V.dev, old, NULL);
	g_sc = nsc; g_scfmt = pick.format; g_scn = 8; V.SwapImages(V.dev, g_sc, &g_scn, g_scimg);
	OLFX_LOG("compositor: Vulkan swapchain %ux%u format %d, %u images", g_ext.width, g_ext.height, (int)pick.format, g_scn);
	return 1;
}

void olfx_vk_end_frame(const XrFrameEndInfo *ei)
{
	static int once;
	if (!V.ready || !g_layer) { if (!once++) OLFX_LOG("compositor (Vulkan): %s", !g_layer ? "no Metal layer from the app yet" : "not ready"); return; }
	const XrCompositionLayerProjection *proj = NULL;
	for (uint32_t i = 0; i < ei->layerCount; i++) if (ei->layers[i] && ei->layers[i]->type == XR_TYPE_COMPOSITION_LAYER_PROJECTION) { proj = (const XrCompositionLayerProjection *)ei->layers[i]; break; }
	if (!proj || proj->viewCount < 2) return;
	olfx_vk_chain *ch[2]; int idx[2];
	for (int e = 0; e < 2; e++) { ch[e] = olfx_gfx_chain_of(proj->views[e].subImage.swapchain, &idx[e]); if (!ch[e] || !ch[e]->made) return; }
	if (!ensure_cmd()) return;
	int want_srgb = ch[0]->format == VK_FORMAT_R8G8B8A8_SRGB || ch[0]->format == VK_FORMAT_B8G8R8A8_SRGB;
	if (!g_sc && !make_swapchain(want_srgb)) return;

	V.WaitFences(V.dev, 1, &g_fence, VK_TRUE, UINT64_MAX);
	uint32_t ii; VkResult r = V.Acquire(V.dev, g_sc, UINT64_MAX, g_acq, VK_NULL_HANDLE, &ii);
	if (r == VK_ERROR_OUT_OF_DATE_KHR) { make_swapchain(want_srgb); return; }
	if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR) return;
	V.ResetFences(V.dev, 1, &g_fence); V.ResetCb(g_cb, 0);
	VkCommandBufferBeginInfo bi = { VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO, NULL, VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT, NULL };
	V.Begin(g_cb, &bi);
	VkImage dst = g_scimg[ii];
	barrier(g_cb, dst, VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 1, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT,
	        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
	for (int e = 0; e < 2; e++) {
		const XrSwapchainSubImage *sub = &proj->views[e].subImage; VkImage src = (VkImage)ch[e]->img[idx[e]];
		int32_t sx = sub->imageRect.offset.x, sy = sub->imageRect.offset.y;
		int32_t sw = sub->imageRect.extent.width ? sub->imageRect.extent.width : (int32_t)ch[e]->w, sh = sub->imageRect.extent.height ? sub->imageRect.extent.height : (int32_t)ch[e]->h;
		uint32_t layer = sub->imageArrayIndex;
		barrier(g_cb, src, VK_IMAGE_ASPECT_COLOR_BIT, layer, 1, 1, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
		        VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
		int32_t half = (int32_t)g_ext.width / 2;
		VkImageBlit bl = { { VK_IMAGE_ASPECT_COLOR_BIT, 0, layer, 1 }, { { sx, sy, 0 }, { sx + sw, sy + sh, 1 } },
		                   { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 }, { { e == 0 ? 0 : half, 0, 0 }, { e == 0 ? half : (int32_t)g_ext.width, (int32_t)g_ext.height, 1 } } };
		V.Blit(g_cb, src, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, dst, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &bl, VK_FILTER_LINEAR);
		barrier(g_cb, src, VK_IMAGE_ASPECT_COLOR_BIT, layer, 1, 1, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
		        VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
	}
	barrier(g_cb, dst, VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 1, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, VK_ACCESS_TRANSFER_WRITE_BIT, 0,
	        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
	V.End(g_cb);
	VkPipelineStageFlags wait = VK_PIPELINE_STAGE_TRANSFER_BIT;
	VkSubmitInfo si = { VK_STRUCTURE_TYPE_SUBMIT_INFO, NULL, 1, &g_acq, &wait, 1, &g_cb, 1, &g_rel };
	V.Submit(V.queue, 1, &si, g_fence);
	VkPresentInfoKHR pi = { VK_STRUCTURE_TYPE_PRESENT_INFO_KHR, NULL, 1, &g_rel, 1, &g_sc, &ii, NULL };
	r = V.Present(V.queue, &pi);
	if (r == VK_SUCCESS || r == VK_SUBOPTIMAL_KHR) {
        atomic_store(&g_vk_frame_visible, 1);
        olfx_stereo_frame_presented();
    }
	if (r == VK_ERROR_OUT_OF_DATE_KHR || r == VK_SUBOPTIMAL_KHR) make_swapchain(want_srgb);
	static int logged; if (!logged++) OLFX_LOG("compositor (Vulkan): first frame presented (%d)", (int)r);
}

const olfx_entry olfx_vk_table[] = {
	OLFX_E(xrGetVulkanInstanceExtensionsKHR), OLFX_E(xrGetVulkanDeviceExtensionsKHR), OLFX_E(xrGetVulkanGraphicsRequirementsKHR),
	OLFX_E(xrGetVulkanGraphicsDeviceKHR), OLFX_E(xrGetVulkanGraphicsDevice2KHR), OLFX_E(xrCreateVulkanInstanceKHR), OLFX_E(xrCreateVulkanDeviceKHR),
	{ "xrGetVulkanGraphicsRequirements2KHR", (PFN_xrVoidFunction)olfx_xrGetVulkanGraphicsRequirementsKHR },
};
const int olfx_vk_n = (int)(sizeof olfx_vk_table / sizeof olfx_vk_table[0]);
