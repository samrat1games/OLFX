// SPDX-License-Identifier: BSL-1.0
// Android Meta Platform compatibility loader. Local responses do not verify ownership.
#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <stdint.h>
#include "../../common/olfx_meta_stub.h"
#include "fwd_names.h"

__attribute__((visibility("hidden"))) void *guard_real_table[GUARD_FWD_COUNT];
// Unsupported platform calls return zero through the generated trampolines.
__attribute__((visibility("hidden"))) volatile int guard_platform_ok;
// The local Platform shim does not load the original Meta loader or run its constructors.

// Local compatibility responses do not verify purchases.
#define EXPORT __attribute__((visibility("default")))
// The original loader's JNI_OnLoad requires Meta services. The compatibility loader
// supplies its own Platform calls and must not initialize that loader in the VM.
EXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)vm; (void)reserved; return JNI_VERSION_1_6;
}

EXPORT int ovr_PlatformInitializeAndroid(const char *a, jobject b, JNIEnv *c) { (void)a; (void)b; (void)c; return 0; }
EXPORT int ovr_PlatformInitializeAndroidWithOptions(const char *a, jobject b, JNIEnv *c, void *d) { (void)d; return ovr_PlatformInitializeAndroid(a,b,c); }
EXPORT uint64_t ovr_PlatformInitializeAndroidAsynchronous(const char *a, jobject b, JNIEnv *c) { (void)a; (void)b; (void)c; return olfx_meta_init_async(); }
EXPORT int ovr_UnityInitWrapper(const char *a) { (void)a; return 1; }
EXPORT uint64_t ovr_UnityInitWrapperAsynchronous(const char *a) { (void)a; return olfx_meta_init_async(); }
EXPORT uint64_t ovr_Entitlement_GetIsViewerEntitled(void) { return olfx_meta_entitlement(); }
EXPORT void *ovr_PopMessage(void) { return olfx_meta_pop(); }
EXPORT void ovr_FreeMessage(void *m) { olfx_meta_free(m); }
EXPORT uint64_t ovr_Message_GetRequestID(const void *m) { return olfx_meta_id(m); }
EXPORT uint32_t ovr_Message_GetType(const void *m) { return olfx_meta_type(m); }
EXPORT int ovr_Message_IsError(const void *m) { return olfx_meta_error(m); }
EXPORT void *ovr_Message_GetPlatformInitialize(void *m) { return olfx_meta_init_result(m); }
EXPORT int ovr_PlatformInitialize_GetResult(const void *p) { return olfx_meta_result(p); }
