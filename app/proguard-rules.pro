# Add project specific ProGuard rules here.
# Keep MediaPipe classes
-keep class com.google.mediapipe.** { *; }
# Keep ExecuTorch classes
-keep class org.pytorch.executorch.** { *; }
-keep class com.facebook.executorch.** { *; }
# Keep TFLite classes
-keep class org.tensorflow.lite.** { *; }
# Keep Room entities
-keep class com.memorylens.app.storage.** { *; }
