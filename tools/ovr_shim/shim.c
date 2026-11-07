// libOVRPlugin.so forwarding shim: loads libOVRPlugin_real.so and forwards every export, logging the first calls.
#include <android/log.h>
#include <dlfcn.h>
#include <stdint.h>
#include <string.h>
#include "names.h"

#define TAG "OLFX-OVR"
__attribute__((visibility("hidden"))) void *olfx_real_table[OLFX_COUNT];
static int olfx_calls[OLFX_COUNT];
static void *olfx_real;

__attribute__((constructor)) static void olfx_init(void)
{
	olfx_real = dlopen("libOVRPlugin_real.so", RTLD_NOW | RTLD_GLOBAL);
	if (!olfx_real) {
		__android_log_print(ANDROID_LOG_ERROR, TAG, "cannot load the real OVRPlugin: %s", dlerror());
		return;
	}
	for (int i = 0; i < OLFX_COUNT; i++) {
		olfx_real_table[i] = dlsym(olfx_real, olfx_names[i]);
	}
	__android_log_print(ANDROID_LOG_INFO, TAG, "shim up, %d functions forwarded", OLFX_COUNT);
}

// Called by every stub before forwarding (registers are preserved by the stub).
__attribute__((visibility("hidden"))) void olfx_trace(int idx)
{
	int n = ++olfx_calls[idx];
	if (n <= 2) {
		__android_log_print(ANDROID_LOG_INFO, TAG, "call %s (#%d)", olfx_names[idx], n);
	}
}
