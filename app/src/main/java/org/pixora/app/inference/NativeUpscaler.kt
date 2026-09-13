package org.pixora.app.inference

import android.graphics.Bitmap

object NativeUpscaler {
    private val loaded = runCatching { System.loadLibrary("pixora_ncnn") }.isSuccess

    val available: Boolean get() = loaded

    external fun hasVulkan(): Boolean

    external fun upscale(
        input: Bitmap,
        output: Bitmap,
        paramPath: String,
        modelPath: String,
        targetScale: Int,
        tileSize: Int,
        useVulkan: Boolean,
    ): String?
}
