# ONNX Runtime (JNI bindings are resolved at runtime)
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Keep MediaCodec/Compose entry points referenced from the manifest
-keep class com.flowframes.android.MainActivity { *; }
