# Pixora — AI Upscaler for Android

Pixora is a free, ad-free, open-source Android image upscaler. It runs locally with ncnn and uses Vulkan compute when the device supports it. Images are never uploaded.

## Features

- Single-image and batch upscaling with 2×, 3× and 4× output
- All seven current Upscayl desktop models, downloaded only when selected
- PNG, JPEG and lossless WebP output through Android's Storage Access Framework
- Remembered model, scale, format, output folder and appearance preferences
- Material 3 Expressive theme, motion scheme, dynamic color, light/dark/system modes
- Before/after comparison slider with pinch zoom and pan
- Compact navigation bar and adaptive navigation rail layouts
- Arabic, Czech, German, English, Spanish, French, Hindi, Hungarian, Indonesian, Italian, Japanese, Korean, Dutch, Polish, Brazilian Portuguese, Russian, Turkish, Vietnamese, Simplified Chinese and Traditional Chinese
- arm64-v8a and x86_64 builds

## Runtime

The JNI layer uses the official ncnn `20260526` Android Vulkan static libraries. ncnn detects Vulkan at runtime and falls back to its optimized CPU path when the GPU or driver is unavailable. Inference is tiled to control peak GPU and system memory.

Models are fetched from Upscayl's public model directory as matching `.param` and `.bin` files and stored in the app's private data directory. The APK contains no AI model weights.

## Build

Requirements: Android Studio with JDK 17+, Android SDK 37, Android NDK 28.2.13676358, CMake 3.28.3, curl and unzip.

Fetch the ignored ncnn prebuilt dependency once in a fresh checkout:

```bash
./scripts/fetch_ncnn.sh
```

```bash
./gradlew :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## License

Pixora is licensed under AGPL-3.0-or-later. See `LICENSE` and `THIRD_PARTY_NOTICES.md`.
