# Native RIFE engine: ncnn (Vulkan GPU + ARM CPU) behind a small JNI surface.
cmake_minimum_required(VERSION 3.18)
project(flowframes_rife CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ncnn prebuilt release (static, Vulkan through simplevk: libvulkan is loaded at runtime, so the
# library also loads on phones without Vulkan and falls back to the CPU).
set(NCNN_VERSION 20261020)
set(NCNN_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/ncnn-android CACHE PATH "Unpacked ncnn-<ver>-android-vulkan")
if(NOT EXISTS ${NCNN_ROOT}/${ANDROID_ABI}/lib/cmake/ncnn/ncnnConfig.cmake)
    set(NCNN_ZIP ${CMAKE_CURRENT_SOURCE_DIR}/ncnn-${NCNN_VERSION}-android-vulkan.zip)
    if(NOT EXISTS ${NCNN_ZIP})
        message(STATUS "Downloading ncnn ${NCNN_VERSION} (android-vulkan)")
        file(DOWNLOAD
            https://github.com/Tencent/ncnn/releases/download/${NCNN_VERSION}/ncnn-${NCNN_VERSION}-android-vulkan.zip
            ${NCNN_ZIP} SHOW_PROGRESS STATUS dl_status)
        list(GET dl_status 0 dl_code)
        if(NOT dl_code EQUAL 0)
            file(REMOVE ${NCNN_ZIP})
            message(FATAL_ERROR "ncnn download failed: ${dl_status}. Download it manually into ${NCNN_ZIP}")
        endif()
    endif()
    file(ARCHIVE_EXTRACT INPUT ${NCNN_ZIP} DESTINATION ${CMAKE_CURRENT_SOURCE_DIR})
    file(RENAME ${CMAKE_CURRENT_SOURCE_DIR}/ncnn-${NCNN_VERSION}-android-vulkan ${NCNN_ROOT})
endif()
set(ncnn_DIR ${NCNN_ROOT}/${ANDROID_ABI}/lib/cmake/ncnn)
find_package(ncnn REQUIRED)

include(${CMAKE_CURRENT_SOURCE_DIR}/shaders.cmake)

add_library(flowframes_rife SHARED rife_jni.cpp rife_core.cpp warp.cpp ${RIFE_SHADER_HEADERS})
target_include_directories(flowframes_rife PRIVATE ${CMAKE_CURRENT_SOURCE_DIR} ${CMAKE_CURRENT_BINARY_DIR})
target_compile_options(flowframes_rife PRIVATE -O3 -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(flowframes_rife PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL
    # Android 15+ devices may use 16 KB pages.
    -Wl,-z,max-page-size=16384)
target_link_libraries(flowframes_rife ncnn android log)
