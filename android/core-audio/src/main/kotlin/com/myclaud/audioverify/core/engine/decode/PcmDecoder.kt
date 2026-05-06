package com.myclaud.audioverify.core.engine.decode

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer

/**
 * Decodes an entire compressed audio file (MP3/AAC/MP4) to PCM in memory using
 * MediaCodec in synchronous mode. Used to feed PCM-only AudioTrack/AAudio paths
 * without touching the audio thread for decoding.
 */
object PcmDecoder {
    fun decodeToPcm(file: File): PcmData {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        var trackIndex = -1
        var inputFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIndex = i
                inputFormat = f
                break
            }
        }
        check(trackIndex >= 0 && inputFormat != null) { "No audio track in $file" }
        extractor.selectTrack(trackIndex)

        val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(inputFormat, null, null, 0)
        codec.start()

        val out = java.io.ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var sawInputEos = false
        var sawOutputEos = false
        var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        while (!sawOutputEos) {
            if (!sawInputEos) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val inBuf: ByteBuffer = codec.getInputBuffer(inIdx)!!
                    val size = extractor.readSampleData(inBuf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEos = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outIdx >= 0 -> {
                    val outBuf = codec.getOutputBuffer(outIdx)!!
                    if (info.size > 0) {
                        val chunk = ByteArray(info.size)
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        outBuf.get(chunk)
                        out.write(chunk)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFmt = codec.outputFormat
                    sampleRate = newFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = newFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
        }

        codec.stop()
        codec.release()
        extractor.release()

        return PcmData(sampleRate, channels, bitsPerSample = 16, pcm = out.toByteArray())
    }
}
