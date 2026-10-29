# RNNoise (xiph/rnnoise v0.2, BSD-3-Clause; see rnnoise/COPYING), built in with its trained
# weights, and Clipra's JNI bridge to it.
cmake_minimum_required(VERSION 3.22.1)
project(clipra_rnnoise C)

set(RNNOISE ${CMAKE_CURRENT_SOURCE_DIR}/rnnoise)

add_library(clipra_rnnoise SHARED
    rnnoise_jni.c
    ${RNNOISE}/src/celt_lpc.c
    ${RNNOISE}/src/denoise.c
    ${RNNOISE}/src/kiss_fft.c
    ${RNNOISE}/src/nnet.c
    ${RNNOISE}/src/nnet_default.c
    ${RNNOISE}/src/parse_lpcnet_weights.c
    ${RNNOISE}/src/pitch.c
    ${RNNOISE}/src/rnn.c
    ${RNNOISE}/src/rnnoise_data.c
    ${RNNOISE}/src/rnnoise_tables.c
)

target_include_directories(clipra_rnnoise PRIVATE ${RNNOISE}/include ${RNNOISE}/src)
target_compile_options(clipra_rnnoise PRIVATE -O3 -fvisibility=hidden -Wno-unused-parameter)
target_link_libraries(clipra_rnnoise m)
# 16 KB memory pages, as Google Play requires (docs/architecture.md, section 1).
target_link_options(clipra_rnnoise PRIVATE "-Wl,-z,max-page-size=16384")
