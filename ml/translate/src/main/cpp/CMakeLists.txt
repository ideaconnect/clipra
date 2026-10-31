cmake_minimum_required(VERSION 3.22.1)
project(clipra_translate C CXX)

# Marian NMT (browsermt/marian-dev, MIT), fetched with the submodules it needs and checked with
# SHA-256 by build.gradle.kts, built CPU only as a static library (ADR 0007).
if(NOT MARIAN_SOURCE)
  message(FATAL_ERROR "Set MARIAN_SOURCE to the Marian source tree")
endif()

set(COMPILE_CPU ON CACHE BOOL "" FORCE)
set(COMPILE_CUDA OFF CACHE BOOL "" FORCE)
set(COMPILE_LIBRARY_ONLY ON CACHE BOOL "" FORCE)
set(USE_MKL OFF CACHE BOOL "" FORCE)
set(USE_NCCL OFF CACHE BOOL "" FORCE)
set(USE_DOXYGEN OFF CACHE BOOL "" FORCE)
set(USE_SENTENCEPIECE ON CACHE BOOL "" FORCE)
set(USE_STATIC_LIBS ON CACHE BOOL "" FORCE)
set(SPM_ENABLE_TCMALLOC OFF CACHE BOOL "" FORCE)
# Ruy multiplies the float matrices on both ABIs; the int8 products use ruy on arm64 and
# intgemm on x86_64 (Marian picks them by architecture).
set(USE_RUY ON CACHE BOOL "" FORCE)
set(USE_RUY_SGEMM ON CACHE BOOL "" FORCE)
set(CMAKE_HAVE_THREADS_LIBRARY 1)
set(CMAKE_USE_PTHREADS_INIT 1)
set(THREADS_PREFER_PTHREAD_FLAG ON)

if(ANDROID_ABI STREQUAL "x86_64")
  set(BUILD_ARCH x86-64 CACHE STRING "" FORCE)
  # faiss in Marian uses SSE intrinsics without including their header.
  add_compile_options(-include immintrin.h)
else()
  set(BUILD_ARCH armv8-a CACHE STRING "" FORCE)
endif()

add_subdirectory(${MARIAN_SOURCE} marian EXCLUDE_FROM_ALL)

add_library(clipra_translate SHARED marian_jni.cpp)
# The bridge is compiled exactly as Marian's own sources are (its definitions, such as ARM and
# USE_INTGEMM, and its flags), so Marian's headers mean the same in both.
get_directory_property(MARIAN_DEFINITIONS DIRECTORY ${MARIAN_SOURCE}/src COMPILE_DEFINITIONS)
get_directory_property(MARIAN_OPTIONS DIRECTORY ${MARIAN_SOURCE}/src COMPILE_OPTIONS)
get_directory_property(MARIAN_CXX_FLAGS DIRECTORY ${MARIAN_SOURCE}/src DEFINITION CMAKE_CXX_FLAGS)
separate_arguments(MARIAN_CXX_FLAGS)
target_compile_definitions(clipra_translate PRIVATE ${MARIAN_DEFINITIONS})
target_compile_options(clipra_translate PRIVATE ${MARIAN_OPTIONS} ${MARIAN_CXX_FLAGS})
target_include_directories(clipra_translate PRIVATE
  ${MARIAN_SOURCE}/src
  ${MARIAN_SOURCE}/src/3rd_party
  ${CMAKE_BINARY_DIR}/marian/src)
target_link_libraries(clipra_translate PRIVATE marian log)
# Android 15 and later run on devices with 16 KB memory pages (ADR 0003).
target_link_options(clipra_translate PRIVATE "-Wl,-z,max-page-size=16384")
