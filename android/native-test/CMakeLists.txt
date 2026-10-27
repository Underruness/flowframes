cmake_minimum_required(VERSION 3.18)
project(rife_host_test CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_BUILD_TYPE Release)
set(ncnn_DIR $ENV{NCNN_HOST}/lib/cmake/ncnn)
find_package(ncnn REQUIRED)
set(SRC ${CMAKE_CURRENT_SOURCE_DIR}/../app/src/main/cpp)
include(${SRC}/shaders.cmake)
add_executable(host_test host_test.cpp ${SRC}/rife_core.cpp ${SRC}/warp.cpp ${SRC}/shader_cache.cpp ${SRC}/crash_handler.cpp ${RIFE_SHADER_HEADERS})
target_include_directories(host_test PRIVATE ${SRC} ${CMAKE_CURRENT_BINARY_DIR})
target_link_libraries(host_test ncnn pthread)

# Same JNI library as the APK, built for the host JVM so the unit tests can drive the real
# Kotlin <-> native path: ./gradlew testDebugUnitTest -PnativeTestLib=<dir of libflowframes_rife.so>
find_package(JNI REQUIRED COMPONENTS JVM)
add_library(flowframes_rife SHARED ${SRC}/rife_jni.cpp ${SRC}/rife_core.cpp ${SRC}/warp.cpp ${SRC}/shader_cache.cpp ${SRC}/crash_handler.cpp ${RIFE_SHADER_HEADERS})
target_include_directories(flowframes_rife PRIVATE ${SRC} ${CMAKE_CURRENT_BINARY_DIR} ${JNI_INCLUDE_DIRS})
target_link_libraries(flowframes_rife ncnn pthread)

# Same SPIR-V cache wrap as the APK, with the libstdc++ mangling of the host ncnn build.
set(FF_WRAP -Wl,--wrap=_ZN4ncnn20compile_spirv_moduleEiRKNS_6OptionERSt6vectorIjSaIjEE)
target_link_options(host_test PRIVATE ${FF_WRAP})
target_link_options(flowframes_rife PRIVATE ${FF_WRAP})
