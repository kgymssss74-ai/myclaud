package com.myclaud.audioverify.native_aaudio

data class AAudioSnapshot(
    val performanceModeLowLatency: Boolean,
    val exclusive: Boolean,
    val mmap: Boolean,
    val deviceId: Int,
    val xRunCount: Int,
    val framesPerBurst: Int,
    val sampleRate: Int,
)

/**
 * JNI surface for the native AAudio engine. Loaded once at process startup.
 *
 * The native side requests LOW_LATENCY + EXCLUSIVE (MMAP). If the device only
 * grants something weaker (common on some Tab S10 firmwares) the stream is
 * still opened but [AAudioSnapshot] reflects the actual mode — callers are
 * responsible for prompting the operator rather than auto-relaxing.
 */
object NativeAAudio {
    init {
        System.loadLibrary("aaudio_engine")
    }

    @JvmStatic
    external fun openExclusiveLowLatency(
        sampleRate: Int,
        channels: Int,
        preferredDeviceId: Int,
        pcm16: ByteArray,
    ): Long

    @JvmStatic
    external fun start(handle: Long)

    @JvmStatic
    external fun stop(handle: Long)

    @JvmStatic
    external fun snapshot(handle: Long): AAudioSnapshot
}
