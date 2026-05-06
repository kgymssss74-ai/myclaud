package com.myclaud.audioverify.core.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.File

/**
 * Convenience engine for ear-only sanity checks (Manual screen). Not used by
 * the matrix runner because MediaPlayer does not expose underrun counts.
 */
class MediaPlayerEngine(
    private val context: Context,
    private val file: File,
) : PlaybackEngine {

    private var mp: MediaPlayer? = null
    private var openError: String? = null
    private var lastRoutedDeviceId: Int? = null
    private var lastRoutedDeviceType: Int? = null

    override fun open(config: PlaybackConfig) {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val player = MediaPlayer().apply {
            setAudioAttributes(attrs)
            setDataSource(file.absolutePath)
            prepare()
            isLooping = true
        }
        config.preferredDevice?.let {
            val ok = player.setPreferredDevice(it)
            if (!ok) {
                openError = "setPreferredDevice rejected"
                player.release()
                throw IllegalStateException(openError)
            }
        }
        mp = player
    }

    override fun start() {
        mp?.start()
    }

    override fun snapshot(): PlaybackMetrics {
        val routed = mp?.routedDevice
        if (routed != null) {
            lastRoutedDeviceId = routed.id
            lastRoutedDeviceType = routed.type
        }
        return PlaybackMetrics(
            opened = mp != null,
            performanceMode = PerformanceMode.NA,
            sharingMode = SharingMode.NA,
            isMmap = false,
            routedDeviceId = lastRoutedDeviceId,
            routedDeviceType = lastRoutedDeviceType,
            underrunCount = 0,
            xRunCount = 0,
            framesPerBurst = null,
            sampleRate = null,
            errorMessage = openError,
        )
    }

    override fun stop() {
        mp?.runCatching { stop() }
        mp?.release()
        mp = null
    }
}
