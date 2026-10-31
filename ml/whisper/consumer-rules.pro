# R8 rules for the apps that use ml:whisper.

# whisper_jni.cpp calls onProgress on the listener by name (GetMethodID); no Kotlin code calls it,
# so R8 would remove it and transcription would fail in release builds.
-keep interface tech.idct.clipra.ml.whisper.Whisper$ProgressListener {
    void onProgress(int);
}
-keepclassmembers class * implements tech.idct.clipra.ml.whisper.Whisper$ProgressListener {
    void onProgress(int);
}
