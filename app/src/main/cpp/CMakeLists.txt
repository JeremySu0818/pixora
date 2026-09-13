cmake_minimum_required(VERSION 3.22.1)
project(pixora_ncnn)

set(PIXORA_NCNN_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/third_party/ncnn-20260526-android-vulkan/${ANDROID_ABI}")
set(ncnn_DIR "${PIXORA_NCNN_ROOT}/lib/cmake/ncnn")
find_package(ncnn REQUIRED)

add_library(pixora_ncnn SHARED pixora_ncnn.cpp)
target_include_directories(pixora_ncnn PRIVATE "${PIXORA_NCNN_ROOT}/include/ncnn")
target_link_libraries(pixora_ncnn ncnn android jnigraphics log)
