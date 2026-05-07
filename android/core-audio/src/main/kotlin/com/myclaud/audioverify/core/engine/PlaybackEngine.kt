package com.myclaud.audioverify.core.engine

import android.media.AudioDeviceInfo

enum class StreamType { RINGTONE, DEEP_BUFFER, FAST_LL, ULL, OFFLOAD, FAST_OTHERS }
enum class AudioFormat { WAV, MP3, AAC, MP4 }
enum class SharingMode { SHARED, EXCLUSIVE, NA }
enum class PerformanceMode { NONE, LOW_LATENCY, POWER_SAVING, NA }

data class PlaybackConfig(
    val streamType: StreamType,
    val format: AudioFormat,
    val assetPath: String,
    val preferredDevice: AudioDeviceInfo?,
    val durationSec: Int,
)

data class PlaybackMetrics(
    val opened: Boolean,
    val performanceMode: PerformanceMode,
    val sharingMode: SharingMode,
    val isMmap: Boolean,
    val routedDeviceId: Int?,
    val routedDeviceType: Int?,
    val underrunCount: Int,
    val xRunCount: Int,
    val framesPerBurst: Int?,
    val sampleRate: Int?,
    val errorMessage: String?,
)

interface PlaybackEngine {
    /**
     * Opens the underlying stream/track in the configured mode. Throws
     * [IllegalStateException] if the requested mode cannot be honoured —
     * callers (the runner) are responsible for surfacing a user prompt rather
     * than auto-relaxing.
     */
    fun open(config: PlaybackConfig)

    /** Begins feeding audio. Non-blocking; the engine spawns its own thread. */
    fun start()

    /** Returns a snapshot of current metrics (safe to call while playing). */
    fun snapshot(): PlaybackMetrics

    /** Stops playback and releases resources. */
    fun stop()
}
