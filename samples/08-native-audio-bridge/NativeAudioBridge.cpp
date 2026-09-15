// DEMONSTRATION SAMPLE — rewritten for this public repository.
//
// This is the JNI shim only: argument marshalling, lifetime ownership, and the real-time
// safety boundary. The production engine (JUCE audio graph, mixing, effects, synthesis,
// time-stretching) is proprietary and is NOT included. Every function below delegates to
// an `AudioEngine` whose implementation is omitted.
//
// ---------------------------------------------------------------------------------------
//
// What this file is responsible for, and what it is deliberately not.
//
// The shim's whole job is to be boring: convert types, check lifetimes, delegate, return.
// Any logic that creeps in here is logic that is untestable from either side — it is not
// covered by the Kotlin unit tests and not covered by the C++ engine tests. Keeping the
// shim thin is what keeps both suites meaningful.

#include <jni.h>
#include <atomic>
#include <memory>
#include <string>

#include "AudioEngine.h"   // proprietary — not published

namespace {

// The engine is owned here, by the shim, for exactly as long as Kotlin says it should
// exist. A raw global pointer is the traditional way to do this and the traditional way
// to get a use-after-free: something calls in during teardown, reads a freed pointer, and
// crashes in a stack frame that mentions none of your code.
//
// This cost me three separate Play Vitals crash signatures that all turned out to be one
// lifetime bug on a voice slot. `shared_ptr` + an atomic load means a call that arrives
// mid-teardown holds the engine alive for the duration of its own call, or sees null and
// does nothing. Both outcomes are safe; a dangling read is not.
std::shared_ptr<AudioEngine> g_engine;
std::atomic<bool> g_ready{false};

// Takes a strong reference so the engine cannot be freed underneath a call in flight.
std::shared_ptr<AudioEngine> acquire() {
    if (!g_ready.load(std::memory_order_acquire)) return nullptr;
    return std::atomic_load(&g_engine);
}

std::string toStdString(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};              // OOM already pending on the JVM
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);      // must run on every path
    return result;
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_nextsoundz_showcase_native_NativeAudioBridge_nativeInitEngine(
        JNIEnv*, jobject, jint sampleRate, jint framesPerBurst) {

    auto engine = std::make_shared<AudioEngine>();
    if (!engine->start(static_cast<int>(sampleRate), static_cast<int>(framesPerBurst))) {
        return JNI_FALSE;
    }

    std::atomic_store(&g_engine, engine);
    // Publish last: release ordering guarantees another thread that sees ready == true
    // also sees a fully constructed engine.
    g_ready.store(true, std::memory_order_release);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_nextsoundz_showcase_native_NativeAudioBridge_nativeReleaseEngine(JNIEnv*, jobject) {
    // Close the gate before touching the engine, so no new call can enter.
    g_ready.store(false, std::memory_order_release);

    auto engine = std::atomic_load(&g_engine);
    std::atomic_store(&g_engine, std::shared_ptr<AudioEngine>{});

    if (engine) {
        // Stops the audio callback and waits for the current buffer to finish. Freeing
        // while the real-time thread is mid-buffer is the use-after-free described above.
        engine->stop();
    }
    // Remaining in-flight callers hold their own strong refs; the engine is destroyed when
    // the last of them returns.
}

JNIEXPORT void JNICALL
Java_com_nextsoundz_showcase_native_NativeAudioBridge_nativePlay(JNIEnv*, jobject) {
    if (auto engine = acquire()) engine->transport().play();
}

JNIEXPORT void JNICALL
Java_com_nextsoundz_showcase_native_NativeAudioBridge_nativeStop(
        JNIEnv*, jobject, jboolean rewind) {
    if (auto engine = acquire()) engine->transport().stop(rewind == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_nextsoundz_showcase_native_NativeAudioBridge_nativeSeek(
        JNIEnv*, jobject, jlong positionMs) {
    if (auto engine = acquire()) engine->transport().seek(static_cast<int64_t>(positionMs));
}

JNIEXPORT void JNICALL
Java_com_nextsoundz_showcase_native_NativeAudioBridge_nativeSetTempo(
        JNIEnv*, jobject, jdouble bpm) {
    if (auto engine = acquire()) engine->transport().setTempo(static_cast<double>(bpm));
}

JNIEXPORT void JNICALL
Java_com_nextsoundz_showcase_native_NativeAudioBridge_nativePushProjectState(
        JNIEnv* env, jobject, jstring stateJson, jintArray changedTracks) {

    auto engine = acquire();
    if (!engine) return;

    const std::string json = toStdString(env, stateJson);

    std::vector<int> changed;
    if (changedTracks != nullptr) {
        const jsize length = env->GetArrayLength(changedTracks);
        changed.resize(static_cast<size_t>(length));
        env->GetIntArrayRegion(changedTracks, 0, length, changed.data());
    }

    // Parsing and graph rebuilding happen on the engine's own worker thread, never on the
    // audio thread. The new graph is swapped in atomically at a buffer boundary, so
    // playback is not interrupted by a state push.
    engine->scheduleStateUpdate(json, changed);
}

JNIEXPORT jint JNICALL
Java_com_nextsoundz_showcase_native_NativeAudioBridge_nativeDrainPositionUpdates(
        JNIEnv* env, jobject, jlongArray out) {

    auto engine = acquire();
    if (!engine || out == nullptr) return 0;

    const jsize capacity = env->GetArrayLength(out);
    if (capacity <= 0) return 0;

    // Drains the lock-free queue the audio thread writes into. The audio thread itself
    // never touches JNI — that is the entire reason this is a pull rather than a callback.
    std::vector<int64_t> scratch(static_cast<size_t>(capacity));
    const int count = engine->drainPositions(scratch.data(), static_cast<int>(capacity));

    if (count > 0) {
        env->SetLongArrayRegion(out, 0, count,
                                reinterpret_cast<const jlong*>(scratch.data()));
    }
    return static_cast<jint>(count);
}

} // extern "C"
