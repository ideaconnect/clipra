# whisper.cpp (ggml-org/whisper.cpp, MIT) on the CPU, and Clipra's JNI bridge to it. Gradle
# unpacks the pinned release into WHISPER_SOURCE.
cmake_minimum_required(VERSION 3.22.1)
project(clipra_whisper C CXX)

set(CMAKE_CXX_STANDARD 17)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
# x86_64 only runs on emulators, on development machines that all have AVX2.
if(ANDROID_ABI STREQUAL "x86_64")
    foreach(feature GGML_SSE42 GGML_AVX GGML_AVX2 GGML_FMA GGML_F16C GGML_BMI2)
        set(${feature} ON CACHE BOOL "" FORCE)
    endforeach()
endif()

add_subdirectory(${WHISPER_SOURCE} whisper)

add_library(clipra_whisper SHARED whisper_jni.cpp)
target_link_libraries(clipra_whisper whisper log)
target_compile_options(clipra_whisper PRIVATE -O3 -fvisibility=hidden)
# 16 KB memory pages, as Google Play requires (docs/architecture.md, section 1).
target_link_options(clipra_whisper PRIVATE "-Wl,-z,max-page-size=16384")
