#include "aaudio_engine.h"

#include <android/log.h>
#include <cstring>

#define LOG_TAG "AAudioEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace audioverify {

AAudioEngine::~AAudioEngine() {
    stop();
    if (stream_) {
        AAudioStream_close(stream_);
        stream_ = nullptr;
    }
}

bool AAudioEngine::open(int32_t sample_rate, int32_t channels,
                        int32_t preferred_device_id,
                        const uint8_t* pcm16, size_t pcm_bytes) {
    pcm_.assign(reinterpret_cast<const int16_t*>(pcm16),
                reinterpret_cast<const int16_t*>(pcm16 + pcm_bytes));

    AAudioStreamBuilder* builder = nullptr;
    aaudio_result_t r = AAudio_createStreamBuilder(&builder);
    if (r != AAUDIO_OK) {
        LOGE("createStreamBuilder failed: %s", AAudio_convertResultToText(r));
        return false;
    }

    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_EXCLUSIVE);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setSampleRate(builder, sample_rate);
    AAudioStreamBuilder_setChannelCount(builder, channels);
    AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_MEDIA);
    AAudioStreamBuilder_setContentType(builder, AAUDIO_CONTENT_TYPE_MUSIC);
    if (preferred_device_id != 0) {
        AAudioStreamBuilder_setDeviceId(builder, preferred_device_id);
    }

    r = AAudioStreamBuilder_openStream(builder, &stream_);
    AAudioStreamBuilder_delete(builder);
    if (r != AAUDIO_OK) {
        LOGE("openStream EXCLUSIVE failed: %s — retrying SHARED", AAudio_convertResultToText(r));
        // Retry SHARED so the runner can still observe the stream and prompt
        // the operator with the relax dialog.
        AAudio_createStreamBuilder(&builder);
        AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
        AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
        AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
        AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
        AAudioStreamBuilder_setSampleRate(builder, sample_rate);
        AAudioStreamBuilder_setChannelCount(builder, channels);
        AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_MEDIA);
        AAudioStreamBuilder_setContentType(builder, AAUDIO_CONTENT_TYPE_MUSIC);
        if (preferred_device_id != 0) {
            AAudioStreamBuilder_setDeviceId(builder, preferred_device_id);
        }
        r = AAudioStreamBuilder_openStream(builder, &stream_);
        AAudioStreamBuilder_delete(builder);
        if (r != AAUDIO_OK) {
            LOGE("openStream SHARED also failed: %s", AAudio_convertResultToText(r));
            return false;
        }
    }

    xrun_baseline_.store(AAudioStream_getXRunCount(stream_));
    return true;
}

void AAudioEngine::start() {
    if (!stream_) return;
    aaudio_result_t r = AAudioStream_requestStart(stream_);
    if (r != AAUDIO_OK) {
        LOGE("requestStart failed: %s", AAudio_convertResultToText(r));
        return;
    }
    running_.store(true);
    writer_ = std::thread([this] { writer_loop(); });
}

void AAudioEngine::writer_loop() {
    if (!stream_) return;
    int32_t channels = AAudioStream_getChannelCount(stream_);
    size_t total_samples = pcm_.size();
    size_t pos = 0;
    while (running_.load()) {
        int32_t frames_to_write = AAudioStream_getFramesPerBurst(stream_);
        if (frames_to_write <= 0) frames_to_write = 192;
        int32_t samples_to_write = frames_to_write * channels;
        std::vector<int16_t> tmp(samples_to_write);
        for (int32_t i = 0; i < samples_to_write; ++i) {
            tmp[i] = pcm_[(pos + i) % total_samples];
        }
        aaudio_result_t r = AAudioStream_write(stream_, tmp.data(), frames_to_write,
                                               static_cast<int64_t>(100) * 1000 * 1000);
        if (r < 0) {
            LOGE("write failed: %s", AAudio_convertResultToText(r));
            break;
        }
        pos = (pos + samples_to_write) % total_samples;
    }
}

void AAudioEngine::stop() {
    if (!running_.exchange(false)) {
        return;
    }
    if (writer_.joinable()) writer_.join();
    if (stream_) {
        AAudioStream_requestStop(stream_);
    }
}

EngineSnapshot AAudioEngine::snapshot() const {
    EngineSnapshot s{};
    if (!stream_) return s;
    aaudio_performance_mode_t pm = AAudioStream_getPerformanceMode(stream_);
    aaudio_sharing_mode_t sm = AAudioStream_getSharingMode(stream_);
    s.performance_mode_low_latency = (pm == AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    s.exclusive = (sm == AAUDIO_SHARING_MODE_EXCLUSIVE);
    // AAudio does not expose isMMap directly via NDK — we infer it heuristically
    // from EXCLUSIVE + LOW_LATENCY which is how MMAP is granted on Pixel/Samsung
    // implementations. Operators verifying with `dumpsys audioflinger` can
    // cross-check.
    s.mmap = s.exclusive && s.performance_mode_low_latency;
    s.device_id = AAudioStream_getDeviceId(stream_);
    s.xrun_count = AAudioStream_getXRunCount(stream_) - xrun_baseline_.load();
    s.frames_per_burst = AAudioStream_getFramesPerBurst(stream_);
    s.sample_rate = AAudioStream_getSampleRate(stream_);
    return s;
}

}  // namespace audioverify
