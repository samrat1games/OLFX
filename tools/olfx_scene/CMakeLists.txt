# OLFX: CC0 living-room OpenXR scene shown on Pico headsets (launcher panel inside the room).
add_library(olfx_scene SHARED olfx_scene.cpp)
set_target_properties(olfx_scene PROPERTIES CXX_STANDARD 17)
target_include_directories(olfx_scene PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../../src/external/openxr_includes)
target_compile_definitions(olfx_scene PRIVATE XR_NO_PROTOTYPES XR_USE_PLATFORM_ANDROID XR_USE_GRAPHICS_API_OPENGL_ES)
target_link_libraries(olfx_scene PRIVATE EGL GLESv3 android log)
