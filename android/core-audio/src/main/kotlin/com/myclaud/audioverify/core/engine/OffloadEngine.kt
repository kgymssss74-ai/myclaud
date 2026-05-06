package com.myclaud.audioverify.core.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Offloaded compressed playback. Feeds raw MP3/AAC bitstream frames to AudioTrack
 * with the offload flag enabled. WAV cases must be filtered out before reaching
 * this engine (the matrix prunes them).
 */
class OffloadEngine(
    private val context: Context,
    private val encodedFile: File,
    private val format: AudioFormat,
) : PlaybackEngine {

    private var track: AudioTrack? = null
    private var thread: Thread? = null
    private val running = AtomicBoolean(false)
    private var openError: String? = null
    private var lastRoutedDeviceId: Int? = null
    private var lastRoutedDeviceType: Int? = null

    override fun open(config: PlaybackConfig) {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val encoding = when (format) {
            AudioFormat.MP3 -> AndroidAudioFormat.ENCODING_MP3
            AudioFormat.AAC, AudioFormat.MP4 -> AndroidAudioFormat.ENCODING_AAC_LC
            AudioFormat.WAV -> {
                openError = "OffloadEngine does not accept WAV (PCM)."
                throw IllegalStateException(openError)
            }
        }

        val af = AndroidAudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(48_000)
            .setChannelMask(AndroidAudioFormat.CHANNEL_OUT_STEREO)
            .build()

        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (!AudioManager.isOffloadedPlaybackSupported(af, attrs)) {
            openError = "Offload not supported for ${format.name} on this device."
            throw IllegalStateException(openError)
        }

        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(af)
                .setOffloadedPlayback(true)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            openError = "Offload AudioTrack build failed: ${e.message}"
            throw IllegalStateException(openError, e)
        }

        if (t.state != AudioTrack.STATE_INITIALIZED) {
            openError = "Offload AudioTrack not initialised."
            t.release()
            throw IllegalStateException(openError)
        }

        config.preferredDevice?.let {
            val ok = t.setPreferredDevice(it)
            if (!ok) {
                openError = "setPreferredDevice rejected device id=${it.id}"
                t.release()
                throw IllegalStateException(openError)
            }
        }

        track = t
    }

    override fun start() {
        val t = checkNotNull(track) { "open() not called" }
        running.set(true)
        t.play()
        thread = Thread({
            val bytes = encodedFile.readBytes()
            var idx = 0
            val chunk = 8 * 1024
            while (running.get() && idx < bytes.size) {
                val n = minOf(chunk, bytes.size - idx)
                val written = t.write(bytes, idx, n)
                if (written < 0) {
                    openError = "Offload write returned $written"
                    break
                }
                idx += written
                if (idx >= bytes.size) idx = 0
            }
        }, "OffloadEngine")
        thread?.start()
    }

    override fun snapshot(): PlaybackMetrics {
        val t = track
        val routed = t?.routedDevice
        if (routed != null) {
            lastRoutedDeviceId = routed.id
            lastRoutedDeviceType = routed.type
        }
        return PlaybackMetrics(
            opened = t != null && t.state == AudioTrack.STATE_INITIALIZED,
            performanceMode = PerformanceMode.NA,
            sharingMode = SharingMode.NA,
            isMmap = false,
            routedDeviceId = lastRoutedDeviceId,
            routedDeviceType = lastRoutedDeviceType,
            underrunCount = t?.underrunCount ?: 0,
            xRunCount = 0,
            framesPerBurst = null,
            sampleRate = 48_000,
            errorMessage = openError,
        )
    }

    override fun stop() {
        running.set(false)
        thread?.join(1000)
        thread = null
        track?.runCatching { stop() }
        track?.release()
        track = null
    }
}
