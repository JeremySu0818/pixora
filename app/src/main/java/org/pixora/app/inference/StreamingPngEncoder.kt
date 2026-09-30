package org.pixora.app.inference

import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlinx.coroutines.CancellationException

/** Writes an RGBA image from a disk-backed pixel file using bounded row buffers. */
internal object StreamingPngEncoder {
    private const val CHUNK_BUFFER_SIZE = 64 * 1024

    fun encode(
        rawRgba: File,
        width: Int,
        height: Int,
        output: OutputStream,
        onProgress: (Float) -> Boolean,
    ) {
        require(width > 0 && height > 0)
        val bytesPerRow = width.toLong() * 4L
        require(rawRgba.length() >= bytesPerRow * height) { "Temporary image data is incomplete" }

        val data = DataOutputStream(output)
        data.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        val header = byteArrayOf(
            (width ushr 24).toByte(), (width ushr 16).toByte(), (width ushr 8).toByte(), width.toByte(),
            (height ushr 24).toByte(), (height ushr 16).toByte(), (height ushr 8).toByte(), height.toByte(),
            8, 6, 0, 0, 0,
        )
        writeChunk(data, "IHDR", header)

        val idat = IdatChunks(data)
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        try {
            val compressed = DeflaterOutputStream(idat, deflater, CHUNK_BUFFER_SIZE)
            val buffer = ByteArray(CHUNK_BUFFER_SIZE)
            val progressInterval = maxOf(1, height / 100)
            RandomAccessFile(rawRgba, "r").use { raw ->
                for (row in 0 until height) {
                    raw.seek(row.toLong() * bytesPerRow)
                    compressed.write(0) // PNG filter type 0: None.
                    var remaining = bytesPerRow
                    while (remaining > 0) {
                        val count = minOf(buffer.size.toLong(), remaining).toInt()
                        raw.readFully(buffer, 0, count)
                        unpremultiplyRgba(buffer, count)
                        compressed.write(buffer, 0, count)
                        remaining -= count
                    }
                    if ((row + 1) % progressInterval == 0 || row + 1 == height) {
                        if (!onProgress((row + 1).toFloat() / height)) throw CancellationException()
                    }
                }
            }
            compressed.finish()
            idat.finish()
        } finally {
            deflater.end()
        }

        writeChunk(data, "IEND", ByteArray(0))
        data.flush()
    }

    private fun writeChunk(output: DataOutputStream, type: String, bytes: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply {
            update(typeBytes)
            update(bytes)
        }
        output.writeInt(bytes.size)
        output.write(typeBytes)
        output.write(bytes)
        output.writeInt(crc.value.toInt())
    }

    private fun unpremultiplyRgba(bytes: ByteArray, length: Int) {
        for (offset in 0 until length step 4) {
            val alpha = bytes[offset + 3].toInt() and 0xff
            if (alpha == 0) {
                bytes[offset] = 0
                bytes[offset + 1] = 0
                bytes[offset + 2] = 0
            } else if (alpha < 255) {
                bytes[offset] = ((bytes[offset].toInt() and 0xff) * 255 / alpha).coerceAtMost(255).toByte()
                bytes[offset + 1] = ((bytes[offset + 1].toInt() and 0xff) * 255 / alpha).coerceAtMost(255).toByte()
                bytes[offset + 2] = ((bytes[offset + 2].toInt() and 0xff) * 255 / alpha).coerceAtMost(255).toByte()
            }
        }
    }

    private class IdatChunks(private val output: DataOutputStream) : OutputStream() {
        private val bytes = ByteArray(CHUNK_BUFFER_SIZE)
        private var size = 0
        private val type = "IDAT".toByteArray(Charsets.US_ASCII)

        override fun write(value: Int) {
            bytes[size++] = value.toByte()
            if (size == bytes.size) flushChunk()
        }

        override fun write(source: ByteArray, offset: Int, length: Int) {
            var sourceOffset = offset
            var remaining = length
            while (remaining > 0) {
                val count = minOf(bytes.size - size, remaining)
                source.copyInto(bytes, size, sourceOffset, sourceOffset + count)
                size += count
                sourceOffset += count
                remaining -= count
                if (size == bytes.size) flushChunk()
            }
        }

        fun finish() {
            if (size > 0) flushChunk()
        }

        private fun flushChunk() {
            val crc = CRC32().apply {
                update(type)
                update(bytes, 0, size)
            }
            output.writeInt(size)
            output.write(type)
            output.write(bytes, 0, size)
            output.writeInt(crc.value.toInt())
            size = 0
        }
    }
}
