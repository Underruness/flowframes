// JNI bridge for com.flowframes.android.core.NcnnRifeEngine.
#include <jni.h>

#ifdef __ANDROID__
#include <android/log.h>
#endif
#include <mutex>
#include <string>
#include <vector>

#include "gpu.h"
#include "rife_core.h"

#define LOG_TAG "FlowFramesRife"
#ifdef __ANDROID__
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#else
#define LOGI(...) ((void)0) // host build (JVM unit tests)
#endif

using flowframes::RifeConfig;
using flowframes::RifeCore;

static std::mutex g_errorLock;
static std::string g_lastError;

static void setError(const std::string& e) {
    std::lock_guard<std::mutex> lock(g_errorLock);
    g_lastError = e;
}

static std::string jstr(JNIEnv* env, jstring s) {
    if (!s) return "";
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return r;
}

extern "C" {

JNIEXPORT jint JNICALL
Java_com_flowframes_android_core_NcnnRifeEngine_nativeGpuCount(JNIEnv*, jclass) {
    return RifeCore::gpuCount();
}

JNIEXPORT jstring JNICALL
Java_com_flowframes_android_core_NcnnRifeEngine_nativeGpuName(JNIEnv* env, jclass, jint index) {
    return env->NewStringUTF(RifeCore::gpuName(index).c_str());
}

JNIEXPORT jstring JNICALL
Java_com_flowframes_android_core_NcnnRifeEngine_nativeLastError(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(g_errorLock);
    return env->NewStringUTF(g_lastError.c_str());
}

JNIEXPORT jlong JNICALL
Java_com_flowframes_android_core_NcnnRifeEngine_nativeCreate(JNIEnv* env, jclass, jstring modelDir,
                                                              jint gpuId, jint threads, jint padding,
                                                              jboolean fp16) {
    RifeConfig cfg;
    cfg.modelDir = jstr(env, modelDir);
    cfg.gpuId = gpuId;
    cfg.cpuThreads = threads;
    cfg.padding = padding;
    cfg.fp16Arithmetic = fp16 == JNI_TRUE;
    auto* core = new RifeCore();
    std::string error;
    int ret = core->load(cfg, error);
    if (ret != 0) {
        delete core;
        setError(error.empty() ? ("load failed (" + std::to_string(ret) + ")") : error);
        return 0;
    }
    LOGI("RIFE ncnn loaded: %s on %s", cfg.modelDir.c_str(), core->deviceName().c_str());
    return reinterpret_cast<jlong>(core);
}

JNIEXPORT jstring JNICALL
Java_com_flowframes_android_core_NcnnRifeEngine_nativeDeviceName(JNIEnv* env, jclass, jlong handle) {
    auto* core = reinterpret_cast<RifeCore*>(handle);
    return env->NewStringUTF(core ? core->deviceName().c_str() : "");
}

JNIEXPORT jint JNICALL
Java_com_flowframes_android_core_NcnnRifeEngine_nativeInterpolate(JNIEnv* env, jclass, jlong handle,
                                                                   jfloatArray frame0, jfloatArray frame1,
                                                                   jint width, jint height, jfloat t,
                                                                   jfloatArray out) {
    auto* core = reinterpret_cast<RifeCore*>(handle);
    if (!core) return -1;
    const jsize n = (jsize)width * height * 3;
    if (env->GetArrayLength(frame0) < n || env->GetArrayLength(frame1) < n || env->GetArrayLength(out) < n) {
        return -10;
    }
    // Plain copies, not GetPrimitiveArrayCritical: an inference lasts tens of milliseconds and
    // must not hold the collector; three memcpy of a 540p frame cost about a millisecond.
    thread_local std::vector<float> a, b, o;
    a.resize(n);
    b.resize(n);
    o.resize(n);
    env->GetFloatArrayRegion(frame0, 0, n, a.data());
    env->GetFloatArrayRegion(frame1, 0, n, b.data());
    int ret = core->interpolate(a.data(), b.data(), width, height, t, o.data());
    if (ret == 0) env->SetFloatArrayRegion(out, 0, n, o.data());
    return ret;
}

JNIEXPORT void JNICALL
Java_com_flowframes_android_core_NcnnRifeEngine_nativeDestroy(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<RifeCore*>(handle);
}

} // extern "C"
