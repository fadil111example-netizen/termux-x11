-dontobfuscate

# Terminal bawaan (pustaka Termux) dan pembuka .xz
-keep class com.termux.terminal.** { *; }
-keep class com.termux.view.** { *; }
-dontwarn org.tukaani.xz.**
