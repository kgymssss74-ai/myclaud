package com.myclaud.audioverify.core.engine.decode

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.myclaud.audioverify.core.util.AppLogger
import java.io.File
import java.nio.ByteBuffer

/**
 * Decodes a compressed audio file (MP3/AAC/MP4) to PCM in memory using
 * MediaCodec. Caps decoded output at [maxBytes] to avoid OOM on large user
 * files; extra audio is silently truncated. Logs progress + failures via
 * [AppLogger].
 */
object PcmDecoder {
    private const val TAG = "PcmDecoder"
    private const val DEFAULT_MAX_BYTES: Long = 16L * 1024 * 1024  // 16 MB ≈ 90 s @ 48k stereo 16-bit

    fun decodeToPcm(file: File, maxBytes: Long = DEFAULT_MAX_BYTES): PcmData {
        AppLogger.i(TAG, "decodeToPcm start path=${file.absolutePath} size=${file.length()}")
        if (!file.exists()) {
            throw IllegalStateException("Source file does not exist: ${file.absolutePath}")
        }
        if (file.length() == 0L) {
            throw IllegalStateException("Source file is empty: ${file.absolutePath}")
        }

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
        } catch (e: Exception) {
            AppLogger.e(TAG, "MediaExtractor.setDataSource failed", e)
            extractor.release()
            throw IllegalStateException("Cannot read audio file (unsupported or corrupt): ${e.message}", e)
        }

        var trackIndex = -1
        var inputFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            AppLogger.i(TAG, "track[$i] mime=$mime")
            if (mime.startsWith("audio/")) {
                trackIndex = i
                inputFormat = f
                break
            }
        }
        if (trackIndex < 0 || inputFormat == null) {
            extractor.release()
            throw IllegalStateException("No audio track found in ${file.name}")
        }
        extractor.selectTrack(trackIndex)

        val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
        val codec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (e: Exception) {
            AppLogger.e(TAG, "createDecoderByType failed for $mime", e)
            extractor.release()
            throw IllegalStateException("No codec for $mime: ${e.message}", e)
        }
        try {
            codec.configure(inputFormat, null, null, 0)
            codec.start()
        } catch (e: Exception) {
            AppLogger.e(TAG, "codec.configure/start failed for $mime", e)
            codec.release()
            extractor.release()
            throw IllegalStateException("Codec configure failed for $mime: ${e.message}", e)
        }

        val out = java.io.ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var sawInputEos = false
        var sawOutputEos = false
        var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var truncated = false

        try {
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
                        if (info.size > 0 && out.size() < maxBytes) {
                            val take = minOf(info.size, (maxBytes - out.size()).toInt())
                            val chunk = ByteArray(take)
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + take)
                            outBuf.get(chunk)
                            out.write(chunk)
                            if (out.size() >= maxBytes && !truncated) {
                                truncated = true
                                AppLogger.w(TAG, "decoded output reached cap ($maxBytes bytes); truncating remainder")
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFmt = codec.outputFormat
                        sampleRate = newFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = newFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        AppLogger.i(TAG, "format changed: sr=$sampleRate ch=$channels")
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "decode loop failed", e)
            try { codec.stop() } catch (_: Throwable) {}
            codec.release()
            extractor.release()
            throw IllegalStateException("Decode failed: ${e.message}", e)
        }

        try { codec.stop() } catch (_: Throwable) {}
        codec.release()
        extractor.release()

        val pcm = out.toByteArray()
        if (pcm.isEmpty()) {
            throw IllegalStateException("Decoder produced 0 bytes for ${file.name}")
        }
        AppLogger.i(TAG, "decodeToPcm done bytes=${pcm.size} sr=$sampleRate ch=$channels truncated=$truncated")
        return PcmData(sampleRate, channels, bitsPerSample = 16, pcm = pcm)
    }
}
