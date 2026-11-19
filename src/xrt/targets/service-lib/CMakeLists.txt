# Copyright 2020, Collabora, Ltd.
# SPDX-License-Identifier: BSL-1.0

add_library(monado-service MODULE service_target.cpp)

target_link_libraries(
	monado-service
	PRIVATE
		aux_util
		st_prober
		ipc_server
		comp_main
		target_lists
		target_instance
		xrt-external-jni-wrap
	)

# OLFX: real OpenXR hand-delivery probe (JNI). Compiled into the shared service library so the
# JNI symbol is exported even though no C++ code references it.
if(ANDROID)
	target_sources(monado-service PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../../auxiliary/android/olfx_openxr_probe.c)
	target_link_libraries(monado-service PRIVATE xrt-external-openxr)
endif()
