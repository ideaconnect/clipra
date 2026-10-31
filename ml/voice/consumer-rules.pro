# R8 rules for the apps that use ml:voice.

# ONNX Runtime's JNI code finds its Java classes, constructors and fields by name, and its AAR
# keeps only its telemetry classes. Keep the Java API, as ONNX Runtime's documentation advises;
# without it DeepFilterNet aborts the app in release builds.
-keep class ai.onnxruntime.** { *; }
