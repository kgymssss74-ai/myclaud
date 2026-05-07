package com.myclaud.audioverify.core.engine

import com.myclaud.audioverify.core.engine.decode.PcmData
import com.myclaud.audioverify.native_aaudio.NativeAAudio

/**
 * Kotlin wrapper around the native AAudio engine. Negotiates LOW_LATENCY +
 * EXCLUSIVE (MMAP) and exposes xRun count + actual mode achieved. If the device
 * grants something weaker, [snapshot] reflects the truth — the runner decides
 * whether to surface a relax prompt.
 */
class AAudioEngine(private val pcm: PcmData) : PlaybackEngine {

    private var handle: Long = 0L
    private var openError: String? = null
    private var requestedDeviceId: Int = 0

    override fun open(config: PlaybackConfig) {
        requestedDeviceId = config.preferredDevice?.id ?: 0
        handle = NativeAAudio.openExclusiveLowLatency(
            sampleRate = pcm.sampleRate,
            channels = if (pcm.channels >= 2) 2 else 1,
            preferredDeviceId = requestedDeviceId,
            pcm16 = pcm.pcm,
        )
        if (handle == 0L) {
            openError = "AAudio open failed (driver returned null)"
            throw IllegalStateException(openError)
        }
    }

    override fun start() {
        if (handle != 0L) NativeAAudio.start(handle)
    }

    override fun snapshot(): PlaybackMetrics {
        if (handle == 0L) {
            return PlaybackMetrics(
                opened = false,
                performanceMode = PerformanceMode.NA,
                sharingMode = SharingMode.NA,
                isMmap = false,
                routedDeviceId = null,
                routedDeviceType = null,
                underrunCount = 0,
                xRunCount = 0,
                framesPerBurst = null,
                sampleRate = pcm.sampleRate,
                errorMessage = openError,
            )
        }
        val s = NativeAAudio.snapshot(handle)
        return PlaybackMetrics(
            opened = true,
            performanceMode = if (s.performanceModeLowLatency) PerformanceMode.LOW_LATENCY else PerformanceMode.NONE,
            sharingMode = if (s.exclusive) SharingMode.EXCLUSIVE else SharingMode.SHARED,
            isMmap = s.mmap,
            routedDeviceId = if (s.deviceId == 0) null else s.deviceId,
            routedDeviceType = null,
            underrunCount = 0,
            xRunCount = s.xRunCount,
            framesPerBurst = s.framesPerBurst,
            sampleRate = s.sampleRate,
            errorMessage = openError,
        )
    }

    override fun stop() {
        if (handle != 0L) {
            NativeAAudio.stop(handle)
            handle = 0L
        }
    }
}
