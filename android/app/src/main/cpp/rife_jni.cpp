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
#include "shader_cache.h"
#include "crash_handler.h"

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
Java_com_flowframes_android_core_NcnnRifeEngine_nativeGpuInfo(JNIEnv* env, jclass, jint index) {
    return env->NewStringUTF(RifeCore::gpuInfo(index).c_str());
}

JNIEXPORT jstring JNICALL
Java_com_flowframes_android_core_NcnnRifeEngine_nativeLastError(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(g_errorLock);
    return env->NewStringUTF(g_lastError.c_str());
}

JNIEXPORT jlong JNICALL
Java_com_flowframes_android_core_NcnnRifeEngine_nativeCreate(JNIEnv* env, jclass, jstring modelDir,
                                                              jint gpuId, jint threads, jint padding,
                                                              jboolean fp16, jboolean coopMatrix) {
    RifeConfig cfg;
    cfg.modelDir = jstr(env, modelDir);
    cfg.gpuId = gpuId;
    cfg.cpuThreads = threads;
    cfg.padding = padding;
    cfg.fp16Arithmetic = fp16 == JNI_TRUE;
    cfg.cooperativeMatrix = coopMatrix == JNI_TRUE;
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
    // Lazily built utility pipelines (layout casts) exist after the first frame: save once then.
    // Not after every frame: merging the driver caches while another session builds pipelines is
    // exactly the kind of concurrency a mobile driver may not survive.
    if (ret == 0 && !core->cacheFlushed.exchange(true)) {
        flowframes::setStage("saving the GPU shader cache (first frame)");
        flowframes::flushShaderCaches();
        flowframes::setStage("idle");
    }
    return ret;
}

JNIEXPORT jint JNICALL
Java_com_flowframes_android_core_NcnnRifeEngine_nativeInterpolateArgb(JNIEnv* env, jclass, jlong handle,
                                                                       jintArray frame0, jintArray frame1,
                                                                       jint width, jint height, jfloat t,
                                                                       jintArray out) {
    auto* core = reinterpret_cast<RifeCore*>(handle);
    if (!core) return -1;
    const jsize n = (jsize)width * height;
    if (env->GetArrayLength(frame0) < n || env->GetArrayLength(frame1) < n || env->GetArrayLength(out) < n) {
        return -10;
    }
    // 4 bytes per pixel: a 4K frame is 33 MB here instead of 100 MB of floats.
    thread_local std::vector<uint32_t> a, b, o;
    a.resize(n);
    b.resize(n);
    o.resize(n);
    env->GetIntArrayRegion(frame0, 0, n, reinterpret_cast<jint*>(a.data()));
    env->GetIntArrayRegion(frame1, 0, n, reinterpret_cast<jint*>(b.data()));
    int ret = core->interpolateArgb(a.data(), b.data(), width, height, t, o.data());
    if (ret == 0) env->SetIntArrayRegion(out, 0, n, reinterpret_cast<const jint*>(o.data()));
    // Lazily built utility pipelines (layout casts) exist after the first frame: save once then.
    // Not after every frame: merging the driver caches while another session builds pipelines is
    // exactly the kind of concurrency a mobile driver may not survive.
    if (ret == 0 && !core->cacheFlushed.exchange(true)) {
        flowframes::setStage("saving the GPU shader cache (first frame)");
        flowframes::flushShaderCaches();
        flowframes::setStage("idle");
    }
    return ret;
}

JNIEXPORT void JNICALL
Java_com_flowframes_android_core_NcnnRifeEngine_nativeSetShaderCacheDir(JNIEnv* env, jclass, jstring dir) {
    flowframes::setShaderCacheDir(jstr(env, dir));
}

JNIEXPORT jstring JNICALL
Java_com_flowframes_android_core_NcnnRifeEngine_nativeShaderCacheStats(JNIEnv* env, jclass) {
    return env->NewStringUTF(flowframes::shaderCacheStats().c_str());
}

JNIEXPORT void JNICALL
Java_com_flowframes_android_core_NcnnRifeEngine_nativeInstallCrashHandler(JNIEnv* env, jclass, jstring path) {
    std::string p = jstr(env, path);
    flowframes::installCrashHandler(p.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_flowframes_android_core_NcnnRifeEngine_nativeStage(JNIEnv* env, jclass) {
    return env->NewStringUTF(flowframes::currentStage());
}

JNIEXPORT void JNICALL
Java_com_flowframes_android_core_NcnnRifeEngine_nativeSetPipelineDiskCache(JNIEnv*, jclass, jboolean on) {
    flowframes::setPipelineDiskCache(on == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_flowframes_android_core_NcnnRifeEngine_nativeClearShaderCaches(JNIEnv*, jclass) {
    flowframes::clearShaderCaches();
}

// ARGB -> YUV 4:2:0 straight into the encoder's input planes (direct ByteBuffers), with the same
// Q8 integer maths as YuvConverter.argbToYuv420. coeffs = eY[3], eU[3], eV[3], yOff, uvOff.
JNIEXPORT jint JNICALL
Java_com_flowframes_android_core_NativeFrames_nativeArgbToYuv420(JNIEnv* env, jclass, jintArray argbArr,
                                                                  jint width, jint height, jobject yBuf,
                                                                  jobject uBuf, jobject vBuf, jint yRowStride,
                                                                  jint uvRowStride, jint uvPixelStride,
                                                                  jintArray coeffArr) {
    if (env->GetArrayLength(argbArr) < width * height || env->GetArrayLength(coeffArr) < 11) return -10;
    auto* y = static_cast<uint8_t*>(env->GetDirectBufferAddress(yBuf));
    auto* u = static_cast<uint8_t*>(env->GetDirectBufferAddress(uBuf));
    auto* v = static_cast<uint8_t*>(env->GetDirectBufferAddress(vBuf));
    if (!y || !u || !v) return -11;
    const jlong yCap = env->GetDirectBufferCapacity(yBuf);
    const jlong uCap = env->GetDirectBufferCapacity(uBuf);
    const jlong vCap = env->GetDirectBufferCapacity(vBuf);
    const int cw = (width + 1) / 2, ch = (height + 1) / 2;
    if ((jlong)(height - 1) * yRowStride + width > yCap) return -12;
    const jlong uvNeed = (jlong)(ch - 1) * uvRowStride + (jlong)(cw - 1) * uvPixelStride + 1;
    if (uvNeed > uCap || uvNeed > vCap) return -12;
    jint k[11];
    env->GetIntArrayRegion(coeffArr, 0, 11, k);
    // Critical access is fine here: this loop takes a few milliseconds and does not call back.
    auto* argb = static_cast<const uint32_t*>(env->GetPrimitiveArrayCritical(argbArr, nullptr));
    if (!argb) return -13;
    auto clamp = [](int x) -> uint8_t { return (uint8_t)(x < 0 ? 0 : (x > 255 ? 255 : x)); };
    for (int row = 0; row < height; row++) {
        const uint32_t* src = argb + (size_t)row * width;
        uint8_t* dst = y + (size_t)row * yRowStride;
        for (int col = 0; col < width; col++) {
            const uint32_t c = src[col];
            const int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
            dst[col] = clamp(((k[0] * r + k[1] * g + k[2] * b + 128) >> 8) + k[9]);
        }
    }
    for (int cr = 0; cr < ch; cr++) {
        const int row = cr * 2;
        const bool two = row + 1 < height;
        const uint32_t* s0 = argb + (size_t)row * width;
        const uint32_t* s1 = two ? s0 + width : s0;
        uint8_t* ud = u + (size_t)cr * uvRowStride;
        uint8_t* vd = v + (size_t)cr * uvRowStride;
        for (int cc = 0; cc < cw; cc++) {
            const int col = cc * 2;
            const bool right = col + 1 < width;
            int rs = 0, gs = 0, bs = 0, n = 0;
            auto add = [&](uint32_t c) {
                rs += (c >> 16) & 0xFF;
                gs += (c >> 8) & 0xFF;
                bs += c & 0xFF;
                n++;
            };
            add(s0[col]);
            if (right) add(s0[col + 1]);
            if (two) {
                add(s1[col]);
                if (right) add(s1[col + 1]);
            }
            const int r = rs / n, g = gs / n, b = bs / n;
            const size_t idx = (size_t)cc * uvPixelStride;
            ud[idx] = clamp(((k[3] * r + k[4] * g + k[5] * b + 128) >> 8) + k[10]);
            vd[idx] = clamp(((k[6] * r + k[7] * g + k[8] * b + 128) >> 8) + k[10]);
        }
    }
    env->ReleasePrimitiveArrayCritical(argbArr, const_cast<uint32_t*>(argb), JNI_ABORT);
    return 0;
}

JNIEXPORT void JNICALL
Java_com_flowframes_android_core_NcnnRifeEngine_nativeDestroy(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<RifeCore*>(handle);
}

} // extern "C"
