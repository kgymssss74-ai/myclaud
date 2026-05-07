package com.myclaud.audioverify.core.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioManager

data class OffloadCaps(
    val supported: Map<AudioFormat, Boolean>,
    val perFormatSampleRate: Map<AudioFormat, Int?>,
    val probedAt: Long,
)

/**
 * Asks the platform whether OFFLOAD playback is supported for each compressed
 * format at common sample rates. WAV is always reported as unsupported (Offload
 * is a compressed-bitstream path).
 *
 * Caches nothing — call sites should remember the result. Probe is cheap
 * (no stream is opened, only the policy query).
 */
object OffloadCapabilityProbe {

    private val SAMPLE_RATES = intArrayOf(48_000, 44_100)
    private val COMPRESSED = listOf(AudioFormat.MP3, AudioFormat.AAC, AudioFormat.MP4)

    fun probe(context: Context): OffloadCaps {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val supported = mutableMapOf<AudioFormat, Boolean>()
        val firstHitRate = mutableMapOf<AudioFormat, Int?>()

        for (fmt in COMPRESSED) {
            var hit: Int? = null
            for (sr in SAMPLE_RATES) {
                val af = AndroidAudioFormat.Builder()
                    .setEncoding(encodingFor(fmt))
                    .setSampleRate(sr)
                    .setChannelMask(AndroidAudioFormat.CHANNEL_OUT_STEREO)
                    .build()
                if (AudioManager.isOffloadedPlaybackSupported(af, attrs)) {
                    hit = sr
                    break
                }
            }
            supported[fmt] = hit != null
            firstHitRate[fmt] = hit
        }

        // WAV is never offloadable.
        supported[AudioFormat.WAV] = false
        firstHitRate[AudioFormat.WAV] = null

        return OffloadCaps(
            supported = supported.toMap(),
            perFormatSampleRate = firstHitRate.toMap(),
            probedAt = System.currentTimeMillis(),
        )
    }

    private fun encodingFor(format: AudioFormat): Int = when (format) {
        AudioFormat.MP3 -> AndroidAudioFormat.ENCODING_MP3
        AudioFormat.AAC, AudioFormat.MP4 -> AndroidAudioFormat.ENCODING_AAC_LC
        AudioFormat.WAV -> AndroidAudioFormat.ENCODING_PCM_16BIT
    }
}
