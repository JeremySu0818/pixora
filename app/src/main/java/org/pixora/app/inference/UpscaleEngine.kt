package org.pixora.app.inference

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.system.Os
import android.system.OsConstants
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.pixora.app.data.InputImage
import org.pixora.app.data.OutputFormat
import org.pixora.app.data.UpscaleOptions
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.coroutines.coroutineContext

class UpscaleEngine(private val context: Context) {
    val hasVulkan: Boolean
        get() = NativeUpscaler.available && runCatching { NativeUpscaler.hasVulkan() }.getOrDefault(false)

    suspend fun process(
        input: InputImage,
        options: UpscaleOptions,
        modelDirectory: File,
        onProgress: (Float) -> Boolean,
    ): Uri = withContext(Dispatchers.IO) {
        check(NativeUpscaler.available) { "Native inference runtime is unavailable" }
        val rawImage = withRegionDecoder(input) { decoder ->
            upscaleToDisk(input, decoder, options, modelDirectory, onProgress)
        }
        val processingJob = coroutineContext[Job]
        try {
            val output = saveOutput(rawImage, input.name, options) { fraction ->
                if (processingJob?.isActive != true) false
                else onProgress(.9f + .1f * fraction.coerceIn(0f, 1f))
            }
            coroutineContext.ensureActive()
            if (!onProgress(1f)) throw CancellationException()
            output
        } finally {
            rawImage.file.delete()
        }
    }

    private suspend fun upscaleToDisk(
        input: InputImage,
        decoder: BitmapRegionDecoder,
        options: UpscaleOptions,
        modelDirectory: File,
        onProgress: (Float) -> Boolean,
    ): RawImage {
        val sourceWidth = decoder.width
        val sourceHeight = decoder.height
        check(sourceWidth > 0 && sourceHeight > 0) { "Could not read image dimensions" }

        val width = Math.multiplyExact(sourceWidth.toLong(), options.scale.toLong())
        val height = Math.multiplyExact(sourceHeight.toLong(), options.scale.toLong())
        when (options.format) {
            OutputFormat.PNG -> check(width <= Int.MAX_VALUE && height <= Int.MAX_VALUE) {
                "PNG dimensions cannot exceed 2,147,483,647 pixels"
            }
            OutputFormat.JPEG -> check(width <= 65535 && height <= 65535) {
                "JPEG dimensions cannot exceed 65,535 pixels; choose PNG"
            }
            OutputFormat.WEBP -> check(width <= 16383 && height <= 16383) {
                "WebP dimensions cannot exceed 16,383 pixels; choose PNG or JPEG"
            }
        }
        val rawBytes = Math.multiplyExact(Math.multiplyExact(width, height), 4L)
        val directory = tempDirectory()
        check(StatFs(directory.absolutePath).availableBytes >= rawBytes) {
            "Not enough free storage for the temporary full-resolution image"
        }
        val rawFile = File.createTempFile("pixora_${UUID.randomUUID()}_", ".rgba", directory)
        try {
            RandomAccessFile(rawFile, "rw").use { it.setLength(rawBytes) }

            val useVulkan = hasVulkan
            val coreTileSize = (options.tileSize.takeIf { it > 0 } ?: if (useVulkan) 128 else 64).coerceIn(32, 512)
            val tilesX = (sourceWidth.toLong() + coreTileSize - 1) / coreTileSize
            val tilesY = (sourceHeight.toLong() + coreTileSize - 1) / coreTileSize
            val totalTiles = Math.multiplyExact(tilesX, tilesY)
            val sessionHandle = longArrayOf(0L)
            val modelId = options.modelId
            val createError = NativeUpscaler.createSession(
                File(modelDirectory, "$modelId.param").absolutePath,
                File(modelDirectory, "$modelId.bin").absolutePath,
                useVulkan,
                sessionHandle,
            )
            check(createError == null) { createError ?: "Could not open the selected model" }
            check(sessionHandle[0] != 0L) { "Could not open the selected model" }

            val processingJob = coroutineContext[Job]
            try {
                RandomAccessFile(rawFile, "rw").use { raw ->
                    var completedTiles = 0L
                    var top = 0
                    while (top < sourceHeight) {
                        val bottom = minOf(sourceHeight.toLong(), top.toLong() + coreTileSize).toInt()
                        var left = 0
                        while (left < sourceWidth) {
                            coroutineContext.ensureActive()
                            val right = minOf(sourceWidth.toLong(), left.toLong() + coreTileSize).toInt()
                            val region = Rect(
                                maxOf(0, left - TILE_PADDING),
                                maxOf(0, top - TILE_PADDING),
                                minOf(sourceWidth, right + TILE_PADDING),
                                minOf(sourceHeight, bottom + TILE_PADDING),
                            )
                            val inputTile = decoder.decodeRegion(
                                region,
                                BitmapFactory.Options().apply {
                                    inPreferredConfig = Bitmap.Config.ARGB_8888
                                    inMutable = false
                                },
                            ) ?: error("Could not decode image tile for ${input.name}")
                            var outputTile: Bitmap? = null
                            try {
                                outputTile = Bitmap.createBitmap(
                                    Math.multiplyExact(inputTile.width, options.scale),
                                    Math.multiplyExact(inputTile.height, options.scale),
                                    Bitmap.Config.ARGB_8888,
                                )
                                val nativeError = NativeUpscaler.upscaleTile(
                                    sessionHandle[0],
                                    inputTile,
                                    outputTile,
                                    options.scale,
                                    coreTileSize,
                                    ProgressCallback { tileFraction ->
                                        if (processingJob?.isActive != true) {
                                            false
                                        } else {
                                            val fraction = .9f * ((completedTiles + tileFraction.toDouble()) / totalTiles).toFloat()
                                            onProgress(fraction)
                                        }
                                    },
                                )
                                coroutineContext.ensureActive()
                                check(nativeError == null) { nativeError ?: "Inference failed" }
                                writeCoreTile(
                                    outputTile,
                                    region,
                                    left,
                                    top,
                                    right - left,
                                    bottom - top,
                                    options.scale,
                                    width,
                                    raw,
                                )
                            } finally {
                                outputTile?.recycle()
                                inputTile.recycle()
                            }
                            completedTiles += 1
                            if (!onProgress(.9f * (completedTiles.toDouble() / totalTiles).toFloat())) {
                                throw CancellationException()
                            }
                            left = right
                        }
                        top = bottom
                    }
                }
            } finally {
                NativeUpscaler.destroySession(sessionHandle[0])
            }
            return RawImage(rawFile, width.toInt(), height.toInt())
        } catch (error: Throwable) {
            rawFile.delete()
            throw error
        }
    }

    private fun writeCoreTile(
        bitmap: Bitmap,
        decodedRegion: Rect,
        coreLeft: Int,
        coreTop: Int,
        coreWidth: Int,
        coreHeight: Int,
        scale: Int,
        outputWidth: Long,
        raw: RandomAccessFile,
    ) {
        val bytes = ByteArray(bitmap.byteCount)
        bitmap.copyPixelsToBuffer(ByteBuffer.wrap(bytes))

        val sourceX = (coreLeft - decodedRegion.left) * scale
        val sourceY = (coreTop - decodedRegion.top) * scale
        val destinationX = coreLeft.toLong() * scale
        val destinationY = coreTop.toLong() * scale
        val rowBytes = Math.multiplyExact(Math.multiplyExact(coreWidth, scale), 4)
        val rows = Math.multiplyExact(coreHeight, scale)
        for (row in 0 until rows) {
            val sourceOffset = (sourceY + row) * bitmap.rowBytes + sourceX * 4
            val destinationOffset = ((destinationY + row) * outputWidth + destinationX) * 4L
            raw.seek(destinationOffset)
            raw.write(bytes, sourceOffset, rowBytes)
        }
    }

    private suspend fun <T> withRegionDecoder(input: InputImage, block: suspend (BitmapRegionDecoder) -> T): T {
        var descriptor: ParcelFileDescriptor? = null
        try {
            descriptor = context.contentResolver.openFileDescriptor(input.uri, "r")
            if (descriptor != null) {
                val decoder = try {
                    Os.lseek(descriptor.fileDescriptor, 0L, OsConstants.SEEK_SET)
                    if (Build.VERSION.SDK_INT >= 31) {
                        BitmapRegionDecoder.newInstance(descriptor)
                    } else {
                        @Suppress("DEPRECATION")
                        BitmapRegionDecoder.newInstance(descriptor.fileDescriptor, false)
                    }
                } catch (_: Exception) {
                    null
                }
                if (decoder != null) {
                    try {
                        return block(decoder)
                    } finally {
                        decoder.recycle()
                    }
                }
            }
        } finally {
            descriptor?.close()
        }

        val sourceCopy = File.createTempFile("pixora_source_", ".image", tempDirectory())
        try {
            val stream = context.contentResolver.openInputStream(input.uri)
                ?: error("Could not open ${input.name}")
            stream.use { source -> FileOutputStream(sourceCopy).use { source.copyTo(it) } }
            val decoder = regionDecoderFromFile(sourceCopy)
            try {
                return block(decoder)
            } finally {
                decoder.recycle()
            }
        } finally {
            sourceCopy.delete()
        }
    }

    private fun tempDirectory(): File {
        val candidates = (context.externalCacheDirs.toList() + context.cacheDir).distinct()
        val directory = candidates.maxByOrNull { directory ->
            runCatching { StatFs(directory.absolutePath).availableBytes }.getOrDefault(0L)
        } ?: context.cacheDir
        directory.mkdirs()
        return directory
    }

    private fun regionDecoderFromFile(file: File): BitmapRegionDecoder =
        if (Build.VERSION.SDK_INT >= 31) {
            BitmapRegionDecoder.newInstance(file.absolutePath)
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(file.absolutePath, false)
        }

    private suspend fun saveOutput(
        image: RawImage,
        sourceName: String,
        options: UpscaleOptions,
        onEncodingProgress: (Float) -> Boolean,
    ): Uri {
        val processingJob = coroutineContext[Job]
        val progressCallback = ProgressCallback { fraction ->
            if (processingJob?.isActive != true) false else onEncodingProgress(fraction)
        }
        val stem = sourceName.substringBeforeLast('.').ifBlank { "image" }
        val displayName = "${stem}_pixora_${options.scale}x.${options.format.extension}"
        val mime = when (options.format) {
            OutputFormat.PNG -> "image/png"
            OutputFormat.JPEG -> "image/jpeg"
            OutputFormat.WEBP -> "image/webp"
        }
        val target = options.outputFolder?.let { folderUri ->
            DocumentFile.fromTreeUri(context, folderUri)?.createFile(mime, displayName)?.uri
        } ?: run {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, mime)
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Pixora")
            }
            context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        } ?: error("Could not create output")

        try {
            when (options.format) {
                OutputFormat.PNG -> {
                    context.contentResolver.openOutputStream(target, "w")!!.use { stream ->
                        StreamingPngEncoder.encode(image.file, image.width, image.height, stream, onEncodingProgress)
                    }
                }
                OutputFormat.JPEG, OutputFormat.WEBP -> {
                    context.contentResolver.openFileDescriptor(target, "w")!!.use { descriptor ->
                        val error = when (options.format) {
                            OutputFormat.JPEG -> NativeUpscaler.encodeJpeg(
                                image.file.absolutePath,
                                descriptor.fd,
                                image.width,
                                image.height,
                                95,
                                progressCallback,
                            )
                            OutputFormat.WEBP -> NativeUpscaler.encodeWebp(
                                image.file.absolutePath,
                                descriptor.fd,
                                image.width,
                                image.height,
                                95,
                                Build.VERSION.SDK_INT >= 30,
                                progressCallback,
                            )
                            else -> error("Unsupported image format")
                        }
                        coroutineContext.ensureActive()
                        if (error == "Cancelled") throw CancellationException()
                        check(error == null) { error ?: "Could not encode output" }
                    }
                }
            }
        } catch (error: Throwable) {
            context.contentResolver.delete(target, null, null)
            throw error
        }
        return target
    }

    private data class RawImage(val file: File, val width: Int, val height: Int)

    private companion object {
        const val TILE_PADDING = 10
    }
}

fun Context.displayName(uri: Uri): String {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) return cursor.getString(0)
    }
    return uri.lastPathSegment ?: "image"
}
