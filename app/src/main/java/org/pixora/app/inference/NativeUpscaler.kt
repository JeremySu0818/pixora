package org.pixora.app.inference

import android.graphics.Bitmap

object NativeUpscaler {
    private val loaded = runCatching { System.loadLibrary("pixora_ncnn") }.isSuccess

    val available: Boolean get() = loaded

    external fun hasVulkan(): Boolean

    external fun createSession(
        paramPath: String,
        modelPath: String,
        useVulkan: Boolean,
        sessionHandle: LongArray,
    ): String?

    external fun upscaleTile(
        sessionHandle: Long,
        input: Bitmap,
        output: Bitmap,
        targetScale: Int,
        tileSize: Int,
        progressCallback: ProgressCallback,
    ): String?

    external fun destroySession(sessionHandle: Long)

    external fun encodeJpeg(
        rawRgbaPath: String,
        outputFd: Int,
        width: Int,
        height: Int,
        quality: Int,
        progressCallback: ProgressCallback,
    ): String?

    external fun encodeWebp(
        rawRgbaPath: String,
        outputFd: Int,
        width: Int,
        height: Int,
        quality: Int,
        lossless: Boolean,
        progressCallback: ProgressCallback,
    ): String?
}

fun interface ProgressCallback {
    /** Returns false when processing should stop at the next inference tile boundary. */
    fun onProgress(fraction: Float): Boolean
}
