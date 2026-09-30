cmake_minimum_required(VERSION 3.22.1)
project(pixora_ncnn)

set(PIXORA_NCNN_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/third_party/ncnn-20260526-android-vulkan/${ANDROID_ABI}")
set(ncnn_DIR "${PIXORA_NCNN_ROOT}/lib/cmake/ncnn")

if(NOT EXISTS "${ncnn_DIR}/ncnnConfig.cmake")
    get_filename_component(PIXORA_PROJECT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
    set(FETCH_SCRIPT "${PIXORA_PROJECT_ROOT}/scripts/fetch_ncnn.sh")
    if(EXISTS "${FETCH_SCRIPT}")
        message(STATUS "Fetching ncnn prebuilts via ${FETCH_SCRIPT}...")
        execute_process(
            COMMAND bash "${FETCH_SCRIPT}"
            WORKING_DIRECTORY "${PIXORA_PROJECT_ROOT}"
            RESULT_VARIABLE FETCH_RESULT
        )
        if(NOT FETCH_RESULT EQUAL 0)
            message(FATAL_ERROR
                "\nFailed to download/install ncnn dependencies automatically.\n"
                "Please run '${FETCH_SCRIPT}' manually from the project root.\n"
            )
        endif()
    else()
        message(FATAL_ERROR
            "\nncnn dependencies not found at ${PIXORA_NCNN_ROOT} and '${FETCH_SCRIPT}' was not found.\n"
        )
    endif()
endif()

find_package(ncnn REQUIRED)

set(PIXORA_CODEC_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/third_party/image-codecs")
set(PIXORA_JPEG_ROOT "${PIXORA_CODEC_ROOT}/libjpeg-turbo-3.2.0")
set(PIXORA_WEBP_ROOT "${PIXORA_CODEC_ROOT}/libwebp-1.6.0")
if(NOT EXISTS "${PIXORA_JPEG_ROOT}/CMakeLists.txt" OR NOT EXISTS "${PIXORA_WEBP_ROOT}/CMakeLists.txt")
    get_filename_component(PIXORA_PROJECT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
    execute_process(
        COMMAND bash "${PIXORA_PROJECT_ROOT}/scripts/fetch_image_codecs.sh"
        WORKING_DIRECTORY "${PIXORA_PROJECT_ROOT}"
        RESULT_VARIABLE CODEC_FETCH_RESULT
    )
    if(NOT CODEC_FETCH_RESULT EQUAL 0)
        message(FATAL_ERROR "Failed to download image codec sources via scripts/fetch_image_codecs.sh")
    endif()
endif()

include(ExternalProject)
set(PIXORA_JPEG_BUILD "${CMAKE_BINARY_DIR}/image-codecs/libjpeg-turbo")
set(PIXORA_JPEG_INSTALL "${CMAKE_BINARY_DIR}/image-codecs/libjpeg-turbo-install")
ExternalProject_Add(pixora_jpeg_external
    SOURCE_DIR "${PIXORA_JPEG_ROOT}"
    BINARY_DIR "${PIXORA_JPEG_BUILD}"
    INSTALL_DIR "${PIXORA_JPEG_INSTALL}"
    CMAKE_GENERATOR "${CMAKE_GENERATOR}"
    CMAKE_ARGS
        "-DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}"
        "-DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}"
        "-DANDROID_ABI=${ANDROID_ABI}"
        "-DANDROID_PLATFORM=${ANDROID_PLATFORM}"
        "-DANDROID_NDK=${ANDROID_NDK}"
        "-DCMAKE_ANDROID_NDK=${CMAKE_ANDROID_NDK}"
        "-DCMAKE_SYSTEM_VERSION=${CMAKE_SYSTEM_VERSION}"
        "-DCMAKE_BUILD_TYPE=${CMAKE_BUILD_TYPE}"
        "-DCMAKE_INSTALL_PREFIX=<INSTALL_DIR>"
        "-DCMAKE_INSTALL_LIBDIR=lib"
        "-DENABLE_SHARED=OFF"
        "-DENABLE_STATIC=ON"
        "-DWITH_TURBOJPEG=OFF"
        "-DWITH_TOOLS=OFF"
        "-DWITH_TESTS=OFF"
    BUILD_BYPRODUCTS "${PIXORA_JPEG_INSTALL}/lib/libjpeg.a"
)
add_library(pixora_jpeg STATIC IMPORTED GLOBAL)
set_target_properties(pixora_jpeg PROPERTIES
    IMPORTED_LOCATION "${PIXORA_JPEG_INSTALL}/lib/libjpeg.a"
    INTERFACE_INCLUDE_DIRECTORIES "${PIXORA_JPEG_ROOT}/src;${PIXORA_JPEG_BUILD}"
)

set(WEBP_LINK_STATIC ON CACHE BOOL "" FORCE)
set(WEBP_BUILD_ANIM_UTILS OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_CWEBP OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_DWEBP OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_GIF2WEBP OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_IMG2WEBP OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_VWEBP OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_WEBPINFO OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_LIBWEBPMUX OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_WEBPMUX OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_EXTRAS OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_FUZZTEST OFF CACHE BOOL "" FORCE)
set(WEBP_USE_THREAD OFF CACHE BOOL "" FORCE)
add_subdirectory("${PIXORA_WEBP_ROOT}" "${CMAKE_BINARY_DIR}/libwebp" EXCLUDE_FROM_ALL)

add_library(pixora_ncnn SHARED pixora_ncnn.cpp)
target_include_directories(pixora_ncnn PRIVATE "${PIXORA_NCNN_ROOT}/include/ncnn")
add_dependencies(pixora_ncnn pixora_jpeg_external)
target_link_libraries(pixora_ncnn ncnn pixora_jpeg webp android jnigraphics log)
