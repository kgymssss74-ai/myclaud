#pragma once

#include <aaudio/AAudio.h>
#include <atomic>
#include <thread>
#include <vector>
#include <cstdint>

namespace audioverify {

struct EngineSnapshot {
    bool performance_mode_low_latency;
    bool exclusive;
    bool mmap;
    int32_t device_id;
    int32_t xrun_count;
    int32_t frames_per_burst;
    int32_t sample_rate;
};

class AAudioEngine {
public:
    AAudioEngine() = default;
    ~AAudioEngine();

    // Returns true on successful open. The stream is opened with LOW_LATENCY +
    // EXCLUSIVE requested; the device may grant something weaker. Snapshot()
    // reports what actually happened.
    bool open(int32_t sample_rate, int32_t channels, int32_t preferred_device_id,
              const uint8_t* pcm16, size_t pcm_bytes);

    void start();
    void stop();
    EngineSnapshot snapshot() const;

private:
    void writer_loop();

    AAudioStream* stream_ = nullptr;
    std::vector<int16_t> pcm_;
    std::thread writer_;
    std::atomic<bool> running_{false};
    std::atomic<int32_t> xrun_baseline_{0};
};

}  // namespace audioverify
