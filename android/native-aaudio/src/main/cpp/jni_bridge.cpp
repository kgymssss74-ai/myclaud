#include <jni.h>
#include <cstdint>
#include <memory>

#include "aaudio_engine.h"

using audioverify::AAudioEngine;

namespace {

jclass g_snapshot_cls = nullptr;
jmethodID g_snapshot_ctor = nullptr;

void ensure_snapshot_class(JNIEnv* env) {
    if (g_snapshot_cls != nullptr) return;
    jclass local = env->FindClass("com/myclaud/audioverify/native_aaudio/AAudioSnapshot");
    g_snapshot_cls = static_cast<jclass>(env->NewGlobalRef(local));
    g_snapshot_ctor = env->GetMethodID(g_snapshot_cls, "<init>", "(ZZZIIII)V");
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_myclaud_audioverify_native_1aaudio_NativeAAudio_openExclusiveLowLatency(
        JNIEnv* env, jclass /*clazz*/,
        jint sample_rate, jint channels, jint preferred_device_id, jbyteArray pcm16) {
    auto engine = std::make_unique<AAudioEngine>();
    jsize len = env->GetArrayLength(pcm16);
    jbyte* bytes = env->GetByteArrayElements(pcm16, nullptr);
    bool ok = engine->open(sample_rate, channels, preferred_device_id,
                           reinterpret_cast<const uint8_t*>(bytes),
                           static_cast<size_t>(len));
    env->ReleaseByteArrayElements(pcm16, bytes, JNI_ABORT);
    if (!ok) return 0;
    return reinterpret_cast<jlong>(engine.release());
}

extern "C" JNIEXPORT void JNICALL
Java_com_myclaud_audioverify_native_1aaudio_NativeAAudio_start(
        JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    if (handle == 0) return;
    reinterpret_cast<AAudioEngine*>(handle)->start();
}

extern "C" JNIEXPORT void JNICALL
Java_com_myclaud_audioverify_native_1aaudio_NativeAAudio_stop(
        JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    if (handle == 0) return;
    auto* engine = reinterpret_cast<AAudioEngine*>(handle);
    engine->stop();
    delete engine;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_myclaud_audioverify_native_1aaudio_NativeAAudio_snapshot(
        JNIEnv* env, jclass /*clazz*/, jlong handle) {
    ensure_snapshot_class(env);
    if (handle == 0) {
        return env->NewObject(g_snapshot_cls, g_snapshot_ctor,
                              JNI_FALSE, JNI_FALSE, JNI_FALSE, 0, 0, 0, 0);
    }
    auto* engine = reinterpret_cast<AAudioEngine*>(handle);
    auto s = engine->snapshot();
    return env->NewObject(g_snapshot_cls, g_snapshot_ctor,
                          s.performance_mode_low_latency ? JNI_TRUE : JNI_FALSE,
                          s.exclusive ? JNI_TRUE : JNI_FALSE,
                          s.mmap ? JNI_TRUE : JNI_FALSE,
                          static_cast<jint>(s.device_id),
                          static_cast<jint>(s.xrun_count),
                          static_cast<jint>(s.frames_per_burst),
                          static_cast<jint>(s.sample_rate));
}
