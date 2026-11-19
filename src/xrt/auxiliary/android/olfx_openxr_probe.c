// Copyright 2026 OLFX contributors
// SPDX-License-Identifier: BSL-1.0
/*!
 * @brief Game-style OpenXR hand-delivery probe. Loads the bundled Khronos loader, creates a
 * headless OpenXR client through the normal broker/runtime path, and calls the real
 * xrCreateHandTrackerEXT / xrLocateHandJointsEXT entry points. This is not a compile-time flag check.
 * @ingroup aux_android
 */
#include "xrt/xrt_config_os.h"
#ifdef XRT_OS_ANDROID

#include "xrt/xrt_openxr_includes.h"
#include <jni.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
#include <string.h>
#include <time.h>

static PFN_xrGetInstanceProcAddr s_gpa;
static int s_loader_initialized;

static void
append(char *buf, size_t cap, size_t *off, const char *fmt, ...)
{
	if (*off >= cap - 1) {
		return;
	}
	va_list ap;
	va_start(ap, fmt);
	int n = vsnprintf(buf + *off, cap - *off, fmt, ap);
	va_end(ap);
	if (n > 0) {
		*off += (size_t)n < cap - *off ? (size_t)n : cap - *off - 1;
	}
}

static XrResult
proc(XrInstance inst, const char *name, PFN_xrVoidFunction *out)
{
	return s_gpa(inst, name, out);
}

JNIEXPORT jstring JNICALL
Java_org_freedesktop_monado_ipc_OlfxOpenXrProbe_nativeProbe(JNIEnv *env, jclass clazz, jstring loader_path, jobject context)
{
	(void)clazz;
	char out[2048];
	size_t off = 0;
	out[0] = '\0';
	const char *path = (*env)->GetStringUTFChars(env, loader_path, NULL);
	if (path == NULL) {
		return (*env)->NewStringUTF(env, "stage=loader_path result=JNI_NULL");
	}
	void *lib = dlopen(path, RTLD_NOW | RTLD_LOCAL);
	(*env)->ReleaseStringUTFChars(env, loader_path, path);
	if (lib == NULL) {
		snprintf(out, sizeof(out), "stage=dlopen result=%s", dlerror());
		return (*env)->NewStringUTF(env, out);
	}
	s_gpa = (PFN_xrGetInstanceProcAddr)dlsym(lib, "xrGetInstanceProcAddr");
	if (s_gpa == NULL) {
		return (*env)->NewStringUTF(env, "stage=dlsym result=xrGetInstanceProcAddr_missing");
	}

	PFN_xrInitializeLoaderKHR init = NULL;
	proc(XR_NULL_HANDLE, "xrInitializeLoaderKHR", (PFN_xrVoidFunction *)&init);
	if (init != NULL && !s_loader_initialized) {
		JavaVM *vm = NULL;
		(*env)->GetJavaVM(env, &vm);
		XrLoaderInitInfoAndroidKHR li = {XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR};
		li.applicationVM = vm;
		li.applicationContext = context;
		XrResult r = init((XrLoaderInitInfoBaseHeaderKHR *)&li);
		append(out, sizeof(out), &off, "stage=loader_init result=%d\n", r);
		if (XR_FAILED(r)) {
			return (*env)->NewStringUTF(env, out);
		}
		s_loader_initialized = 1;
	}

	PFN_xrCreateInstance create_instance = NULL;
	PFN_xrDestroyInstance destroy_instance = NULL;
	PFN_xrGetSystem get_system = NULL;
	PFN_xrCreateSession create_session = NULL;
	PFN_xrDestroySession destroy_session = NULL;
	PFN_xrBeginSession begin_session = NULL;
	PFN_xrEndSession end_session = NULL;
	PFN_xrCreateReferenceSpace create_space = NULL;
	PFN_xrDestroySpace destroy_space = NULL;
	PFN_xrCreateHandTrackerEXT create_hand = NULL;
	PFN_xrDestroyHandTrackerEXT destroy_hand = NULL;
	PFN_xrLocateHandJointsEXT locate_hand = NULL;
	PFN_xrConvertTimespecTimeToTimeKHR convert_time = NULL;
	PFN_xrGetInstanceProperties get_instance_props = NULL;
	proc(XR_NULL_HANDLE, "xrCreateInstance", (PFN_xrVoidFunction *)&create_instance);
	proc(XR_NULL_HANDLE, "xrDestroyInstance", (PFN_xrVoidFunction *)&destroy_instance);
	if (create_instance == NULL || destroy_instance == NULL) {
		append(out, sizeof(out), &off, "stage=loader_procs result=missing");
		return (*env)->NewStringUTF(env, out);
	}

	uint32_t ext_count = 0;
	PFN_xrEnumerateInstanceExtensionProperties enum_ext = NULL;
	proc(XR_NULL_HANDLE, "xrEnumerateInstanceExtensionProperties", (PFN_xrVoidFunction *)&enum_ext);
	int have_ext = 0, have_headless = 0, have_time = 0, have_android = 0, have_aim = 0, have_caps = 0;
	if (enum_ext != NULL && enum_ext(NULL, 0, &ext_count, NULL) == XR_SUCCESS && ext_count > 0 && ext_count < 512) {
		XrExtensionProperties *props = calloc(ext_count, sizeof(*props));
		if (props != NULL) {
			for (uint32_t i = 0; i < ext_count; i++) props[i].type = XR_TYPE_EXTENSION_PROPERTIES;
			if (enum_ext(NULL, ext_count, &ext_count, props) == XR_SUCCESS) {
				for (uint32_t i = 0; i < ext_count; i++) {
					if (strcmp(props[i].extensionName, XR_EXT_HAND_TRACKING_EXTENSION_NAME) == 0) have_ext = 1;
					if (strcmp(props[i].extensionName, XR_MND_HEADLESS_EXTENSION_NAME) == 0) have_headless = 1;
					if (strcmp(props[i].extensionName, XR_KHR_CONVERT_TIMESPEC_TIME_EXTENSION_NAME) == 0) have_time = 1;
					if (strcmp(props[i].extensionName, XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME) == 0) have_android = 1;
					if (strcmp(props[i].extensionName, XR_FB_HAND_TRACKING_AIM_EXTENSION_NAME) == 0) have_aim = 1;
					if (strcmp(props[i].extensionName, XR_FB_HAND_TRACKING_CAPSULES_EXTENSION_NAME) == 0) have_caps = 1;
				}
			}
			free(props);
		}
	}
	append(out, sizeof(out), &off, "ext_hand=%d headless=%d aim=%d capsules=%d\n", have_ext, have_headless, have_aim, have_caps);
	if (!have_ext || !have_headless) {
		append(out, sizeof(out), &off, "stage=extensions result=required_missing");
		return (*env)->NewStringUTF(env, out);
	}
	const char *exts[6];
	uint32_t ne = 0;
	exts[ne++] = XR_EXT_HAND_TRACKING_EXTENSION_NAME;
	exts[ne++] = XR_MND_HEADLESS_EXTENSION_NAME;
	if (have_time) exts[ne++] = XR_KHR_CONVERT_TIMESPEC_TIME_EXTENSION_NAME;
	if (have_android) exts[ne++] = XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME;
	if (have_aim) exts[ne++] = XR_FB_HAND_TRACKING_AIM_EXTENSION_NAME;
	if (have_caps) exts[ne++] = XR_FB_HAND_TRACKING_CAPSULES_EXTENSION_NAME;

	JavaVM *vm = NULL;
	(*env)->GetJavaVM(env, &vm);
	XrInstanceCreateInfoAndroidKHR android_ci = {XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR};
	android_ci.applicationVM = vm;
	android_ci.applicationActivity = context;
	XrInstanceCreateInfo ici = {XR_TYPE_INSTANCE_CREATE_INFO};
	ici.next = have_android ? &android_ci : NULL;
	strcpy(ici.applicationInfo.applicationName, "OLFX Hand Probe");
	ici.applicationInfo.apiVersion = XR_API_VERSION_1_0;
	ici.enabledExtensionCount = ne;
	ici.enabledExtensionNames = exts;
	XrInstance inst = XR_NULL_HANDLE;
	XrResult r = create_instance(&ici, &inst);
	append(out, sizeof(out), &off, "stage=xrCreateInstance result=%d\n", r);
	if (XR_FAILED(r)) {
		return (*env)->NewStringUTF(env, out);
	}
	proc(inst, "xrGetInstanceProperties", (PFN_xrVoidFunction *)&get_instance_props);
	if (get_instance_props != NULL) {
		XrInstanceProperties ip = {XR_TYPE_INSTANCE_PROPERTIES};
		if (get_instance_props(inst, &ip) == XR_SUCCESS) {
			append(out, sizeof(out), &off, "runtime=%s\n", ip.runtimeName);
		}
	}
	proc(inst, "xrGetSystem", (PFN_xrVoidFunction *)&get_system);
	proc(inst, "xrCreateSession", (PFN_xrVoidFunction *)&create_session);
	proc(inst, "xrDestroySession", (PFN_xrVoidFunction *)&destroy_session);
	proc(inst, "xrBeginSession", (PFN_xrVoidFunction *)&begin_session);
	proc(inst, "xrEndSession", (PFN_xrVoidFunction *)&end_session);
	proc(inst, "xrCreateReferenceSpace", (PFN_xrVoidFunction *)&create_space);
	proc(inst, "xrDestroySpace", (PFN_xrVoidFunction *)&destroy_space);
	proc(inst, "xrCreateHandTrackerEXT", (PFN_xrVoidFunction *)&create_hand);
	proc(inst, "xrDestroyHandTrackerEXT", (PFN_xrVoidFunction *)&destroy_hand);
	proc(inst, "xrLocateHandJointsEXT", (PFN_xrVoidFunction *)&locate_hand);
	proc(inst, "xrConvertTimespecTimeToTimeKHR", (PFN_xrVoidFunction *)&convert_time);

	XrSystemId system = XR_NULL_SYSTEM_ID;
	XrSession session = XR_NULL_HANDLE;
	XrSpace space = XR_NULL_HANDLE;
	XrHandTrackerEXT hands[2] = {XR_NULL_HANDLE, XR_NULL_HANDLE};
	int active[2] = {0, 0};
	int valid_joints[2] = {0, 0};
	XrResult last[2] = {XR_SUCCESS, XR_SUCCESS};
	if (get_system != NULL) {
		XrSystemGetInfo sgi = {XR_TYPE_SYSTEM_GET_INFO};
		sgi.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
		r = get_system(inst, &sgi, &system);
		append(out, sizeof(out), &off, "stage=xrGetSystem result=%d\n", r);
	} else {
		r = XR_ERROR_FUNCTION_UNSUPPORTED;
	}
	if (XR_SUCCEEDED(r) && create_session != NULL) {
		XrSessionCreateInfo sci = {XR_TYPE_SESSION_CREATE_INFO};
		sci.systemId = system;
		r = create_session(inst, &sci, &session);
		append(out, sizeof(out), &off, "stage=xrCreateSession result=%d\n", r);
	}
	if (XR_SUCCEEDED(r) && begin_session != NULL) {
		XrSessionBeginInfo bi = {XR_TYPE_SESSION_BEGIN_INFO};
		bi.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_MONO;
		r = begin_session(session, &bi);
		append(out, sizeof(out), &off, "stage=xrBeginSession result=%d\n", r);
	}
	if (XR_SUCCEEDED(r) && create_space != NULL) {
		XrReferenceSpaceCreateInfo sp = {XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
		sp.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_VIEW;
		sp.poseInReferenceSpace.orientation.w = 1.0f;
		r = create_space(session, &sp, &space);
		append(out, sizeof(out), &off, "stage=xrCreateReferenceSpace result=%d\n", r);
	}
	if (XR_SUCCEEDED(r) && create_hand != NULL && locate_hand != NULL) {
		for (int h = 0; h < 2; h++) {
			XrHandTrackerCreateInfoEXT hi = {XR_TYPE_HAND_TRACKER_CREATE_INFO_EXT};
			hi.hand = h == 0 ? XR_HAND_LEFT_EXT : XR_HAND_RIGHT_EXT;
			hi.handJointSet = XR_HAND_JOINT_SET_DEFAULT_EXT;
			XrResult cr = create_hand(session, &hi, &hands[h]);
			append(out, sizeof(out), &off, "stage=xrCreateHandTrackerEXT hand=%d result=%d\n", h, cr);
			if (XR_FAILED(cr)) hands[h] = XR_NULL_HANDLE;
		}
		for (int sample = 0; sample < 20; sample++) {
			struct timespec ts;
			clock_gettime(CLOCK_MONOTONIC, &ts);
			XrTime now = (XrTime)ts.tv_sec * 1000000000LL + ts.tv_nsec;
			if (convert_time != NULL) {
				convert_time(inst, &ts, &now);
			}
			for (int h = 0; h < 2; h++) {
				if (hands[h] == XR_NULL_HANDLE) continue;
				XrHandJointLocationEXT joints[XR_HAND_JOINT_COUNT_EXT];
				XrHandJointLocationsEXT loc = {XR_TYPE_HAND_JOINT_LOCATIONS_EXT};
				loc.jointCount = XR_HAND_JOINT_COUNT_EXT;
				loc.jointLocations = joints;
				XrHandTrackingAimStateFB aim = {XR_TYPE_HAND_TRACKING_AIM_STATE_FB};
				XrHandTrackingCapsulesStateFB caps = {XR_TYPE_HAND_TRACKING_CAPSULES_STATE_FB};
				void *chain = NULL;
				if (have_aim) { aim.next = chain; chain = &aim; }
				if (have_caps) { caps.next = chain; chain = &caps; }
				loc.next = chain;
				XrHandJointsLocateInfoEXT li = {XR_TYPE_HAND_JOINTS_LOCATE_INFO_EXT};
				li.baseSpace = space;
				li.time = now;
				last[h] = locate_hand(hands[h], &li, &loc);
				if (XR_SUCCEEDED(last[h]) && loc.isActive) {
					active[h]++;
					int n = 0;
					for (uint32_t i = 0; i < loc.jointCount; i++) {
						if ((joints[i].locationFlags & XR_SPACE_LOCATION_POSITION_VALID_BIT) != 0) n++;
					}
					if (n > valid_joints[h]) valid_joints[h] = n;
				}
			}
			struct timespec delay = {0, 50 * 1000 * 1000};
			nanosleep(&delay, NULL);
		}
		append(out, sizeof(out), &off, "active_left=%d active_right=%d joints_left=%d joints_right=%d locate_left=%d locate_right=%d\n",
		       active[0], active[1], valid_joints[0], valid_joints[1], last[0], last[1]);
	} else {
		append(out, sizeof(out), &off, "stage=hand_procs result=missing_or_session_failed");
	}

	for (int h = 0; h < 2; h++) {
		if (hands[h] != XR_NULL_HANDLE && destroy_hand != NULL) destroy_hand(hands[h]);
	}
	if (space != XR_NULL_HANDLE && destroy_space != NULL) destroy_space(space);
	if (session != XR_NULL_HANDLE) {
		if (end_session != NULL) end_session(session);
		if (destroy_session != NULL) destroy_session(session);
	}
	destroy_instance(inst);
	return (*env)->NewStringUTF(env, out);
}

#endif // XRT_OS_ANDROID
