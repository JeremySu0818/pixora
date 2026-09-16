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

add_library(pixora_ncnn SHARED pixora_ncnn.cpp)
target_include_directories(pixora_ncnn PRIVATE "${PIXORA_NCNN_ROOT}/include/ncnn")
target_link_libraries(pixora_ncnn ncnn android jnigraphics log)
