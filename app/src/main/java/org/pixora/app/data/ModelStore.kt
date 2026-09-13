package org.pixora.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class ModelStore(private val context: Context) {
    val directory: File = File(context.filesDir, "models").apply { mkdirs() }

    fun isInstalled(id: String): Boolean =
        File(directory, "$id.param").isFile && File(directory, "$id.bin").isFile

    fun installedIds(): Set<String> = directory.listFiles().orEmpty()
        .filter { it.extension == "param" && File(directory, "${it.nameWithoutExtension}.bin").isFile }
        .mapTo(mutableSetOf()) { it.nameWithoutExtension }

    suspend fun download(model: UpscaleModel, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        directory.mkdirs()
        downloadFile(model.paramUrl, File(directory, "${model.id}.param"), 0f, .02f, onProgress)
        downloadFile(model.binUrl, File(directory, "${model.id}.bin"), .02f, .98f, onProgress)
    }

    private fun downloadFile(url: String, target: File, offset: Float, weight: Float, onProgress: (Float) -> Unit) {
        val pending = File(target.parentFile, "${target.name}.part")
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "Pixora-Android")
        try {
            connection.connect()
            check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            val total = connection.contentLengthLong.coerceAtLeast(1L)
            connection.inputStream.use { input ->
                pending.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        onProgress(offset + weight * copied.toFloat() / total)
                    }
                }
            }
            check(pending.length() > 0) { "Empty model download" }
            check(pending.renameTo(target)) { "Could not install model" }
        } catch (error: Throwable) {
            pending.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    fun delete(id: String) {
        File(directory, "$id.param").delete()
        File(directory, "$id.bin").delete()
    }
}
