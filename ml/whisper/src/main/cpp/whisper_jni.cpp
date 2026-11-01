// Clipra's bridge to whisper.cpp (#7, #44): load a ggml model, transcribe 16 kHz mono samples
// with progress and cancel, and read the result token by token. Token text crosses as bytes: a
// multi-byte character (Polish letters, for example) can be split between two tokens.
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cstring>
#include <vector>
#include "whisper.h"

namespace {

// A loaded model and its cancel flag, which any thread may set.
struct Session {
    whisper_context *ctx = nullptr;
    std::atomic<bool> cancelled{false};
};

Session *session(jlong handle) {
    return reinterpret_cast<Session *>(handle);
}

// whisper.cpp and ggml log to logcat (tag whisper.cpp) instead of stderr.
void log_to_logcat(ggml_log_level level, const char *text, void *) {
    int priority = level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR
                 : level == GGML_LOG_LEVEL_WARN ? ANDROID_LOG_WARN
                 : ANDROID_LOG_INFO;
    __android_log_write(priority, "whisper.cpp", text);
}

// Progress goes to the listener's onProgress(int) on the transcribing thread.
struct Progress {
    JNIEnv *env;
    jobject listener;
    jmethodID method;
};

void on_progress(whisper_context *, whisper_state *, int progress, void *data) {
    auto *p = static_cast<Progress *>(data);
    if (p->listener != nullptr) p->env->CallVoidMethod(p->listener, p->method, progress);
}

bool on_abort(void *data) {
    return static_cast<Session *>(data)->cancelled.load();
}

}  // namespace

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
    if (ctx == nullptr) return 0;
    auto *s = new Session();
    s->ctx = ctx;
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL
Java_tech_idct_clipra_ml_whisper_Whisper_free(JNIEnv *, jclass, jlong handle) {
    Session *s = session(handle);
    whisper_free(s->ctx);
    delete s;
}

JNIEXPORT void JNICALL
Java_tech_idct_clipra_ml_whisper_Whisper_cancel(JNIEnv *, jclass, jlong handle) {
    session(handle)->cancelled.store(true);
}

// Transcribes in `language` ("auto" detects it); `translate` gives English. Returns 0 when
// done, -100 when cancelled, and whisper.cpp's code on failure.
JNIEXPORT jint JNICALL
Java_tech_idct_clipra_ml_whisper_Whisper_transcribe(JNIEnv *env, jclass, jlong handle,
                                                    jfloatArray samples, jstring language,
                                                    jboolean translate, jint threads,
                                                    jobject listener) {
    Session *s = session(handle);
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    const char *lang = env->GetStringUTFChars(language, nullptr);
    params.language = lang;
    params.detect_language = false;
    params.n_threads = threads;
    params.translate = translate;
    params.token_timestamps = true;
    params.no_context = true;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;
    Progress progress{env, listener, nullptr};
    if (listener != nullptr) {
        jclass type = env->GetObjectClass(listener);
        progress.method = env->GetMethodID(type, "onProgress", "(I)V");
        env->DeleteLocalRef(type);
    }
    params.progress_callback = on_progress;
    params.progress_callback_user_data = &progress;
    params.abort_callback = on_abort;
    params.abort_callback_user_data = s;
    jsize count = env->GetArrayLength(samples);
    jfloat *pcm = env->GetFloatArrayElements(samples, nullptr);
    int result = s->cancelled.load() ? -100 : whisper_full(s->ctx, params, pcm, count);
    whisper_print_timings(s->ctx);
    env->ReleaseFloatArrayElements(samples, pcm, JNI_ABORT);
    env->ReleaseStringUTFChars(language, lang);
    if (s->cancelled.load()) return -100;
    return result;
}

// How likely each of whisper.cpp's languages is to be the one spoken in the first 30 s of
// `samples`, by language id (#188); empty when the encoder fails.
JNIEXPORT jfloatArray JNICALL
Java_tech_idct_clipra_ml_whisper_Whisper_detectLanguage(JNIEnv *env, jclass, jlong handle,
                                                        jfloatArray samples, jint threads) {
    Session *s = session(handle);
    jsize count = env->GetArrayLength(samples);
    jfloat *pcm = env->GetFloatArrayElements(samples, nullptr);
    int mel = whisper_pcm_to_mel(s->ctx, pcm, count, threads);
    env->ReleaseFloatArrayElements(samples, pcm, JNI_ABORT);
    const int n = whisper_lang_max_id() + 1;
    std::vector<float> probs(n, 0.0f);
    int id = mel == 0 ? whisper_lang_auto_detect(s->ctx, 0, threads, probs.data()) : -1;
    jfloatArray result = env->NewFloatArray(id >= 0 ? n : 0);
    if (id >= 0) env->SetFloatArrayRegion(result, 0, n, probs.data());
    return result;
}

JNIEXPORT jstring JNICALL
Java_tech_idct_clipra_ml_whisper_Whisper_languageCode(JNIEnv *env, jclass, jint id) {
    return env->NewStringUTF(whisper_lang_str(id));
}

JNIEXPORT jstring JNICALL
Java_tech_idct_clipra_ml_whisper_Whisper_language(JNIEnv *env, jclass, jlong handle) {
    int id = whisper_full_lang_id(session(handle)->ctx);
    const char *code = id >= 0 ? whisper_lang_str(id) : "";
    return env->NewStringUTF(code);
}

JNIEXPORT jint JNICALL
Java_tech_idct_clipra_ml_whisper_Whisper_segmentCount(JNIEnv *, jclass, jlong handle) {
    return whisper_full_n_segments(session(handle)->ctx);
}

JNIEXPORT jint JNICALL
Java_tech_idct_clipra_ml_whisper_Whisper_tokenCount(JNIEnv *, jclass, jlong handle, jint segment) {
    return whisper_full_n_tokens(session(handle)->ctx, segment);
}

JNIEXPORT jboolean JNICALL
Java_tech_idct_clipra_ml_whisper_Whisper_isSpecial(JNIEnv *, jclass, jlong handle, jint segment,
                                                   jint token) {
    whisper_context *ctx = session(handle)->ctx;
    return whisper_full_get_token_id(ctx, segment, token) >= whisper_token_eot(ctx);
}

JNIEXPORT jbyteArray JNICALL
Java_tech_idct_clipra_ml_whisper_Whisper_tokenBytes(JNIEnv *env, jclass, jlong handle,
                                                    jint segment, jint token) {
    const char *text = whisper_full_get_token_text(session(handle)->ctx, segment, token);
    jsize length = static_cast<jsize>(strlen(text));
    jbyteArray bytes = env->NewByteArray(length);
    env->SetByteArrayRegion(bytes, 0, length, reinterpret_cast<const jbyte *>(text));
    return bytes;
}

// [t0, t1, t_dtw] in hundredths of a second; t_dtw is -1 without DTW timestamps.
JNIEXPORT jlongArray JNICALL
Java_tech_idct_clipra_ml_whisper_Whisper_tokenTimes(JNIEnv *env, jclass, jlong handle,
                                                    jint segment, jint token) {
    whisper_token_data data =
        whisper_full_get_token_data(session(handle)->ctx, segment, token);
    jlong times[3] = {data.t0, data.t1, data.t_dtw};
    jlongArray result = env->NewLongArray(3);
    env->SetLongArrayRegion(result, 0, 3, times);
    return result;
}

JNIEXPORT jfloat JNICALL
Java_tech_idct_clipra_ml_whisper_Whisper_tokenProbability(JNIEnv *, jclass, jlong handle,
                                                          jint segment, jint token) {
    return whisper_full_get_token_p(session(handle)->ctx, segment, token);
}

JNIEXPORT jstring JNICALL
Java_tech_idct_clipra_ml_whisper_Whisper_nativeSystemInfo(JNIEnv *env, jclass) {
    return env->NewStringUTF(whisper_print_system_info());
}

}
