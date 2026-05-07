package com.myclaud.audioverify.core.engine.decode

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class PcmData(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val pcm: ByteArray,
)

/** Minimal RIFF/WAVE reader. Supports PCM 16-bit and 24-bit. */
object WavReader {
    fun read(input: InputStream): PcmData = input.use { stream ->
        val all = stream.readBytes()
        val buf = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN)
        require(all.size >= 44) { "WAV too short" }
        require(String(all, 0, 4) == "RIFF") { "Not a RIFF file" }
        require(String(all, 8, 4) == "WAVE") { "Not a WAVE file" }

        var pos = 12
        var sampleRate = 0
        var channels = 0
        var bitsPerSample = 0
        var dataOffset = -1
        var dataSize = 0

        while (pos + 8 <= all.size) {
            val chunkId = String(all, pos, 4)
            val chunkSize = buf.getInt(pos + 4)
            val payloadStart = pos + 8
            when (chunkId) {
                "fmt " -> {
                    channels = buf.getShort(payloadStart + 2).toInt() and 0xFFFF
                    sampleRate = buf.getInt(payloadStart + 4)
                    bitsPerSample = buf.getShort(payloadStart + 14).toInt() and 0xFFFF
                }
                "data" -> {
                    dataOffset = payloadStart
                    dataSize = chunkSize
                }
            }
            pos = payloadStart + chunkSize + (chunkSize and 1)
            if (dataOffset >= 0) break
        }

        require(dataOffset >= 0) { "No data chunk" }
        val pcm = all.copyOfRange(dataOffset, dataOffset + dataSize)
        PcmData(sampleRate, channels, bitsPerSample, pcm)
    }
}
