// SPDX-License-Identifier: BSL-1.0
// Compatibility placeholder for a script-injection library loaded before Unity.
// OLFX supplies its own Platform/VR adaptation; the third-party startup hook is optional.
#include <jni.h>
JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)vm; (void)reserved;
    return JNI_VERSION_1_6;
}
