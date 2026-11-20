cmake_minimum_required(VERSION 3.22)
project(olfx_go LANGUAGES CXX)
set(OLFX_GO_BUILD ON)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../tools/olfx_scene ${CMAKE_CURRENT_BINARY_DIR}/olfx_scene)
