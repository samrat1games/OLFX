# Copyright 2026 OLFX contributors
# SPDX-License-Identifier: BSL-1.0
# Experimental OVRPlugin(VrApi/OculusXR-era) -> OpenXR compatibility library.
# Built only for the OLFX Android APK; the patcher copies it into a game only when
# the user explicitly selects the compatibility runtime for that game.
set(OLFX_OVR_UPSTREAM "${CMAKE_CURRENT_SOURCE_DIR}/../../third_party/ovrplugin-openxr-shim/shim")
add_library(ovrplugin_compat MODULE
    ${OLFX_OVR_UPSTREAM}/src/stubs.c
    ${OLFX_OVR_UPSTREAM}/src/core.c
    ${OLFX_OVR_UPSTREAM}/src/xr_runtime.c
    ${OLFX_OVR_UPSTREAM}/src/vk_session.c
    ${OLFX_OVR_UPSTREAM}/src/layers.c
    ${OLFX_OVR_UPSTREAM}/src/android_init.c
    ${OLFX_OVR_UPSTREAM}/src/xr_input.c
    ${OLFX_OVR_UPSTREAM}/src/passthru.c
    ${OLFX_OVR_UPSTREAM}/src/olfx_hands.c
    ${OLFX_OVR_UPSTREAM}/src/joycon.c
    ${OLFX_OVR_UPSTREAM}/src/joycon_imu.c
    ${OLFX_OVR_UPSTREAM}/src/olfx_device_profile.c
    ${OLFX_OVR_UPSTREAM}/src/gl_session.c
    ${OLFX_OVR_UPSTREAM}/src/olfx_extra_stubs.c
)
set_target_properties(ovrplugin_compat PROPERTIES OUTPUT_NAME OVRPlugin C_STANDARD 11)
target_include_directories(ovrplugin_compat PRIVATE ${OLFX_OVR_UPSTREAM}/include)
target_link_libraries(ovrplugin_compat PRIVATE xrt-external-openxr ${ANDROID_LOG_LIBRARY} EGL)
target_compile_definitions(ovrplugin_compat PRIVATE XR_EXTENSION_PROTOTYPES)
target_compile_options(ovrplugin_compat PRIVATE -fvisibility=hidden -Wno-unused-parameter)
# Record DT_NEEDED libopenxr_loader.so from the bundled Khronos loader's SONAME.
target_link_libraries(ovrplugin_compat PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/../../olfx/src/main/assets/khronos/libopenxr_loader_arm64.so")
