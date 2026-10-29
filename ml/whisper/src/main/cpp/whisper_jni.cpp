// Clipra's bridge to whisper.cpp (#7): load a ggml model, transcribe 16 kHz mono samples, and
// read the result token by token. Token text crosses as bytes: a multi-byte character (Polish
// letters, for example) can be split between two tokens.
#include <jni.h>
#include <android/log.h>
#include <cstring>
#include "whisper.h"

static whisper_context *context(jlong handle) {
    return reinterpret_cast<whisper_context *>(handle);
}

// whisper.cpp and ggml log to logcat (tag whisper.cpp) instead of stderr.
static void log_to_logcat(ggml_log_level level, const char *text, void *) {
    int priority = level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR
                 : level == GGML_LOG_LEVEL_WARN ? ANDROID_LOG_WARN
                 : ANDROID_LOG_INFO;
    __android_log_write(priority, "whisper.cpp", text);
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_tech_idct_clipra_ml_whisper_Whisper_load(JNIEnv *env, jclass, jstring path, jint aheads) {
    whisper_log_set(log_to_logcat, nullptr);
    const char *file = env->GetStringUTFChars(path, nullptr);
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    if (aheads >= 0) {
        // DTW timestamps need attention weights, which flash attention doesn't keep.
        params.dtw_token_timestamps = true;
        params.dtw_aheads_preset = static_cast<whisper_alignment_heads_preset>(aheads);
        params.flash_attn = false;
    }
    whisper_context *ctx = whisper_init_from_file_with_params(file, params);
    env->ReleaseStringUTFChars(path, file);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_tech_idct_clipra_ml_whisper_Whisper_free(JNIEnv *, jclass, jlong handle) {
    whisper_free(context(handle));
}

JNIEXPORT jint JNICALL
Java_tech_idct_clipra_ml_whisper_Whisper_transcribe(JNIEnv *env, jclass, jlong handle,
                                                    jfloatArray samples, jstring language,
                                                    jint threads) {
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    const char *lang = env->GetStringUTFChars(language, nullptr);
    params.language = lang;
    params.detect_language = false;
    params.n_threads = threads;
    params.translate = false;
    params.token_timestamps = true;
    params.no_context = true;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;
    jsize count = env->GetArrayLength(samples);
    jfloat *pcm = env->GetFloatArrayElements(samples, nullptr);
    int result = whisper_full(context(handle), params, pcm, count);
    whisper_print_timings(context(handle));
    env->ReleaseFloatArrayElements(samples, pcm, JNI_ABORT);
    env->ReleaseStringUTFChars(language, lang);
    return result;
}

JNIEXPORT jint JNICALL
Java_tech_idct_clipra_ml_whisper_Whisper_segmentCount(JNIEnv *, jclass, jlong handle) {
    return whisper_full_n_segments(context(handle));
}

JNIEXPORT jint JNICALL
Java_tech_idct_clipra_ml_whisper_Whisper_tokenCount(JNIEnv *, jclass, jlong handle, jint segment) {
    return whisper_full_n_tokens(context(handle), segment);
}

JNIEXPORT jboolean JNICALL
Java_tech_idct_clipra_ml_whisper_Whisper_isSpecial(JNIEnv *, jclass, jlong handle, jint segment,
                                                   jint token) {
    whisper_context *ctx = context(handle);
    return whisper_full_get_token_id(ctx, segment, token) >= whisper_token_eot(ctx);
}

JNIEXPORT jbyteArray JNICALL
Java_tech_idct_clipra_ml_whisper_Whisper_tokenBytes(JNIEnv *env, jclass, jlong handle,
                                                    jint segment, jint token) {
    const char *text = whisper_full_get_token_text(context(handle), segment, token);
    jsize length = static_cast<jsize>(strlen(text));
    jbyteArray bytes = env->NewByteArray(length);
    env->SetByteArrayRegion(bytes, 0, length, reinterpret_cast<const jbyte *>(text));
    return bytes;
}

// [t0, t1, t_dtw] in hundredths of a second; t_dtw is -1 without DTW timestamps.
JNIEXPORT jlongArray JNICALL
Java_tech_idct_clipra_ml_whisper_Whisper_tokenTimes(JNIEnv *env, jclass, jlong handle,
                                                    jint segment, jint token) {
    whisper_token_data data = whisper_full_get_token_data(context(handle), segment, token);
    jlong times[3] = {data.t0, data.t1, data.t_dtw};
    jlongArray result = env->NewLongArray(3);
    env->SetLongArrayRegion(result, 0, 3, times);
    return result;
}

JNIEXPORT jfloat JNICALL
Java_tech_idct_clipra_ml_whisper_Whisper_tokenProbability(JNIEnv *, jclass, jlong handle,
                                                          jint segment, jint token) {
    return whisper_full_get_token_p(context(handle), segment, token);
}

JNIEXPORT jstring JNICALL
Java_tech_idct_clipra_ml_whisper_Whisper_nativeSystemInfo(JNIEnv *env, jclass) {
    return env->NewStringUTF(whisper_print_system_info());
}

}
