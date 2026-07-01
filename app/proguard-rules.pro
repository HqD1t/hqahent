# Keep Vosk / JNA classes used via reflection
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**
-dontwarn org.vosk.**
