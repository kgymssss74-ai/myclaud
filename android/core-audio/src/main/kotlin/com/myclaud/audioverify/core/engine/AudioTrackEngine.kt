package com.myclaud.audioverify.core.engine

import android.media.AudioAttributes
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.myclaud.audioverify.core.engine.decode.PcmData
import com.myclaud.audioverify.core.util.AppLogger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AudioTrack-based engine covering Ringtone, Deep Buffer, Fast/LL and Fast Others.
 * Reads pre-decoded PCM (passed via [pcm]) and writes blocking on a dedicated
 * thread. Underrun count is exposed via [snapshot].
 */
class AudioTrackEngine(
    private val pcm: PcmData,
    private val streamType: StreamType,
) : PlaybackEngine {

    private var track: AudioTrack? = null
    private var thread: Thread? = null
    private val running = AtomicBoolean(false)
    private var openError: String? = null
    private var requestedDeviceId: Int = 0
    private var lastRoutedDeviceId: Int? = null
    private var lastRoutedDeviceType: Int? = null

    override fun open(config: PlaybackConfig) {
        AppLogger.i(
            "AudioTrackEngine",
            "open stream=$streamType pcmBytes=${pcm.pcm.size} sr=${pcm.sampleRate} ch=${pcm.channels} bits=${pcm.bitsPerSample}",
        )
        val attrs = buildAttributes(streamType)
        val encoding = AndroidAudioFormat.ENCODING_PCM_16BIT
        val channelMask = if (pcm.channels >= 2) AndroidAudioFormat.CHANNEL_OUT_STEREO
        else AndroidAudioFormat.CHANNEL_OUT_MONO
        val format = AndroidAudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(pcm.sampleRate)
            .setChannelMask(channelMask)
            .build()

        val minBuf = AudioTrack.getMinBufferSize(pcm.sampleRate, channelMask, encoding)
        val bufferSize = when (streamType) {
            StreamType.DEEP_BUFFER -> minBuf * 4
            else -> minBuf
        }

        val builder = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)

        when (streamType) {
            StreamType.FAST_LL,
            StreamType.FAST_OTHERS,
            StreamType.ULL -> builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            StreamType.DEEP_BUFFER -> builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
            else -> {}
        }

        val t = try {
            builder.build()
        } catch (e: Exception) {
            openError = "AudioTrack build failed: ${e.message}"
            throw IllegalStateException(openError, e)
        }

        if (t.state != AudioTrack.STATE_INITIALIZED) {
            openError = "AudioTrack not initialised (state=${t.state})"
            t.release()
            throw IllegalStateException(openError)
        }

        config.preferredDevice?.let {
            val ok = t.setPreferredDevice(it)
            if (!ok) {
                openError = "setPreferredDevice rejected device id=${it.id} type=${it.type}"
                t.release()
                throw IllegalStateException(openError)
            }
            requestedDeviceId = it.id
        }

        track = t
    }

    override fun start() {
        val t = checkNotNull(track) { "open() not called" }
        running.set(true)
        t.play()
        thread = Thread({
            val buf = pcm.pcm
            var idx = 0
            val chunk = 4096
            while (running.get() && idx < buf.size) {
                val n = minOf(chunk, buf.size - idx)
                val written = t.write(buf, idx, n)
                if (written < 0) {
                    openError = "AudioTrack.write returned $written"
                    break
                }
                idx += written
                if (idx >= buf.size) idx = 0  // loop the clip until stop
            }
        }, "AudioTrackEngine-${streamType.name}")
        thread?.start()
    }

    override fun snapshot(): PlaybackMetrics {
        val t = track
        val routed = t?.routedDevice
        if (routed != null) {
            lastRoutedDeviceId = routed.id
            lastRoutedDeviceType = routed.type
        }
        val perfMode = when (streamType) {
            StreamType.FAST_LL, StreamType.FAST_OTHERS, StreamType.ULL -> PerformanceMode.LOW_LATENCY
            StreamType.DEEP_BUFFER -> PerformanceMode.NONE
            else -> PerformanceMode.NA
        }
        return PlaybackMetrics(
            opened = t != null && t.state == AudioTrack.STATE_INITIALIZED,
            performanceMode = perfMode,
            sharingMode = SharingMode.NA,
            isMmap = false,
            routedDeviceId = lastRoutedDeviceId,
            routedDeviceType = lastRoutedDeviceType,
            underrunCount = t?.underrunCount ?: 0,
            xRunCount = 0,
            framesPerBurst = null,
            sampleRate = pcm.sampleRate,
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

    private fun buildAttributes(streamType: StreamType): AudioAttributes {
        val b = AudioAttributes.Builder()
        when (streamType) {
            StreamType.RINGTONE -> b
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setLegacyStreamType(AudioManager.STREAM_RING)
            StreamType.DEEP_BUFFER,
            StreamType.FAST_LL,
            StreamType.ULL -> b
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            StreamType.FAST_OTHERS -> b
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            StreamType.OFFLOAD -> b
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        }
        return b.build()
    }
}
