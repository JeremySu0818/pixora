package org.pixora.app.inference

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import org.pixora.app.data.InputImage
import org.pixora.app.data.OutputFormat
import org.pixora.app.data.UpscaleOptions
import java.io.File
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
        val source = context.contentResolver.openInputStream(input.uri)?.use(BitmapFactory::decodeStream)
            ?: error("Could not decode ${input.name}")
        check(source.width.toLong() * options.scale * source.height * options.scale <= 160_000_000L) {
            "Output is too large for this device"
        }
        val software = source.copy(Bitmap.Config.ARGB_8888, false)
        if (software !== source) source.recycle()
        val output = Bitmap.createBitmap(software.width * options.scale, software.height * options.scale, Bitmap.Config.ARGB_8888)
        val processingJob = coroutineContext[Job]
        try {
            val id = options.modelId
            val error = NativeUpscaler.upscale(
                software,
                output,
                File(modelDirectory, "$id.param").absolutePath,
                File(modelDirectory, "$id.bin").absolutePath,
                options.scale,
                options.tileSize,
                hasVulkan,
                ProgressCallback { fraction ->
                    if (processingJob?.isActive != true) {
                        false
                    } else {
                        onProgress(fraction)
                        true
                    }
                },
            )
            check(error == null) { error ?: "Inference failed" }
            saveOutput(output, input.name, options)
        } finally {
            software.recycle()
            output.recycle()
        }
    }

    private fun saveOutput(bitmap: Bitmap, sourceName: String, options: UpscaleOptions): Uri {
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

        context.contentResolver.openOutputStream(target, "w")!!.use { stream ->
            val format = when (options.format) {
                OutputFormat.PNG -> Bitmap.CompressFormat.PNG
                OutputFormat.JPEG -> Bitmap.CompressFormat.JPEG
                OutputFormat.WEBP -> if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP
            }
            check(bitmap.compress(format, 95, stream)) { "Could not encode output" }
        }
        return target
    }

}

fun Context.displayName(uri: Uri): String {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) return cursor.getString(0)
    }
    return uri.lastPathSegment ?: "image"
}
