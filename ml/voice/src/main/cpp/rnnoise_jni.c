// Clipra's bridge to RNNoise (#39): one denoiser state per object, frames of 480 samples at
// 48 kHz in the 16-bit range RNNoise expects.
#include <jni.h>
#include "rnnoise.h"

JNIEXPORT jlong JNICALL
Java_tech_idct_clipra_ml_voice_RnNoise_create(JNIEnv *env, jclass clazz) {
    return (jlong) rnnoise_create(NULL);
}

JNIEXPORT void JNICALL
Java_tech_idct_clipra_ml_voice_RnNoise_destroy(JNIEnv *env, jclass clazz, jlong state) {
    rnnoise_destroy((DenoiseState *) state);
}

JNIEXPORT jint JNICALL
Java_tech_idct_clipra_ml_voice_RnNoise_frameSize(JNIEnv *env, jclass clazz) {
    return rnnoise_get_frame_size();
}

// Processes `frames` frames from `input` into `output`, both `frames * 480` samples long.
JNIEXPORT void JNICALL
Java_tech_idct_clipra_ml_voice_RnNoise_process(JNIEnv *env, jclass clazz, jlong state,
                                                jfloatArray input, jfloatArray output,
                                                jint frames) {
    const int size = rnnoise_get_frame_size();
    jfloat *in = (*env)->GetFloatArrayElements(env, input, NULL);
    jfloat *out = (*env)->GetFloatArrayElements(env, output, NULL);
    for (int f = 0; f < frames; f++) {
        rnnoise_process_frame((DenoiseState *) state, out + f * size, in + f * size);
    }
    (*env)->ReleaseFloatArrayElements(env, input, in, JNI_ABORT);
    (*env)->ReleaseFloatArrayElements(env, output, out, 0);
}
