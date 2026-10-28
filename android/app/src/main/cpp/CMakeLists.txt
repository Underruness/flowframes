# Native engines behind two JNI surfaces:
#   * flowframes_rife: RIFE interpolation (ncnn Vulkan + ARM CPU, prebuilt below)
#   * capri_diffusion:  local image generation (stable-diffusion.cpp / ggml, CPU)
cmake_minimum_required(VERSION 3.18)
project(capri_native C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------------------------
# RIFE: ncnn prebuilt release (static, Vulkan through simplevk: libvulkan is loaded at runtime,
# so the library also loads on phones without Vulkan and falls back to the CPU).
# ---------------------------------------------------------------------------------------------
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

add_library(flowframes_rife SHARED rife_jni.cpp rife_core.cpp warp.cpp shader_cache.cpp crash_handler.cpp ${RIFE_SHADER_HEADERS})
target_include_directories(flowframes_rife PRIVATE ${CMAKE_CURRENT_SOURCE_DIR} ${CMAKE_CURRENT_BINARY_DIR})
target_compile_options(flowframes_rife PRIVATE -O3 -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(flowframes_rife PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL
    # Android 15+ devices may use 16 KB pages.
    -Wl,-z,max-page-size=16384
    # Persistent SPIR-V cache: ncnn's call to compile_spirv_module(int, Option, spirv) goes
    # through shader_cache.cpp (libc++ mangling, see FF_COMPILE_SYM there).
    -Wl,--wrap=_ZN4ncnn20compile_spirv_moduleEiRKNS_6OptionERNSt6__ndk16vectorIjNS3_9allocatorIjEEEE)
target_link_libraries(flowframes_rife ncnn android log)

# ---------------------------------------------------------------------------------------------
# Diffusion: stable-diffusion.cpp pinned to a tag, fetched at configure time (same idea as the
# ncnn prebuilt above: no third-party source in the repository). CPU backend only for now.
# ---------------------------------------------------------------------------------------------
include(FetchContent)

set(SD_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(SD_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(SD_WEBP OFF CACHE BOOL "" FORCE)
set(SD_WEBM OFF CACHE BOOL "" FORCE)
set(SD_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
# ggml targets phone cores: keep the dot-product / i8mm / fp16 kernels it detects at compile time.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "" CACHE STRING "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

FetchContent_Declare(
    sdcpp
    GIT_REPOSITORY https://github.com/leejet/stable-diffusion.cpp.git
    GIT_TAG        master-1024-77b72a2
    GIT_SHALLOW    TRUE
    GIT_SUBMODULES ggml
    GIT_PROGRESS   TRUE)
FetchContent_MakeAvailable(sdcpp)

add_library(capri_diffusion SHARED diffusion_jni.cpp)
target_include_directories(capri_diffusion PRIVATE ${sdcpp_SOURCE_DIR}/include)
target_compile_options(capri_diffusion PRIVATE -O3)
target_link_options(capri_diffusion PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
    -Wl,-z,max-page-size=16384)
target_link_libraries(capri_diffusion stable-diffusion android log)
