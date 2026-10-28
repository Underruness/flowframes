// JNI bridge to stable-diffusion.cpp: text-to-image, image-to-image and model loading.
//
// One context is kept alive between runs (loading a checkpoint takes tens of seconds), progress
// comes back through a Kotlin callback, and cancellation is a flag the engine polls itself.

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <mutex>
#include <string>
#include <vector>

#include "stable-diffusion.h"

#define LOG_TAG "CapriDiffusion"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Handle {
    sd_ctx_t* ctx = nullptr;
    std::atomic<bool> running{false};
};

std::mutex g_lock;
std::string g_last_error;
JavaVM* g_vm = nullptr;
jobject g_progress = nullptr;  // global ref while a generation runs
jmethodID g_on_step = nullptr;

void set_last_error(const std::string& message) {
    std::lock_guard<std::mutex> guard(g_lock);
    g_last_error = message;
}

/** Last error reported by the engine (or by the bridge), for the UI. */
std::string take_last_error() {
    std::lock_guard<std::mutex> guard(g_lock);
    std::string message = g_last_error.empty() ? "generation failed" : g_last_error;
    g_last_error.clear();
    return message;
}

void log_callback(enum sd_log_level_t level, const char* text, void* /*data*/) {
    if (text == nullptr) return;
    switch (level) {
        case SD_LOG_ERROR:
            LOGE("%s", text);
            set_last_error(text);
            break;
        case SD_LOG_WARN:
            LOGI("warn: %s", text);
            break;
        default:
            break;
    }
}

JNIEnv* attach_env(bool* detach) {
    JNIEnv* env = nullptr;
    *detach = false;
    if (g_vm == nullptr) return nullptr;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
        *detach = true;
    }
    return env;
}

void progress_callback(int step, int steps, float /*time*/, void* /*data*/) {
    if (g_progress == nullptr || g_on_step == nullptr) return;
    bool detach = false;
    JNIEnv* env = attach_env(&detach);
    if (env == nullptr) return;
    env->CallVoidMethod(g_progress, g_on_step, step, steps);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (detach) g_vm->DetachCurrentThread();
}

std::string to_string(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string out = chars == nullptr ? "" : chars;
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return out;
}

/** Copies an RGB sd_image_t into an ARGB int array (Android bitmaps are ARGB_8888). */
jintArray to_argb(JNIEnv* env, const sd_image_t& image) {
    if (image.data == nullptr || image.width == 0 || image.height == 0) return nullptr;
    const size_t pixels = static_cast<size_t>(image.width) * image.height;
    jintArray out = env->NewIntArray(static_cast<jsize>(pixels));
    if (out == nullptr) return nullptr;
    std::vector<jint> argb(pixels);
    const bool rgb = image.channel == 3;
    for (size_t i = 0; i < pixels; ++i) {
        const uint8_t* px = image.data + i * image.channel;
        jint r, g, b;
        if (rgb) {
            r = px[0];
            g = px[1];
            b = px[2];
        } else {
            // Single-channel models come back greyscale.
            r = g = b = px[0];
        }
        argb[i] = static_cast<jint>(0xFF000000u | (r << 16) | (g << 8) | b);
    }
    env->SetIntArrayRegion(out, 0, static_cast<jsize>(pixels), argb.data());
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    g_vm = vm;
    sd_set_log_callback(log_callback, nullptr);
    return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL
Java_com_flowframes_android_gen_NativeDiffusion_loadModel(JNIEnv* env, jobject /*thiz*/,
                                                          jstring path, jint threads, jint weight_type) {
    Handle* handle = new Handle();
    sd_ctx_params_t params;
    sd_ctx_params_init(&params);
    const std::string model_path = to_string(env, path);
    params.model_path = model_path.c_str();
    params.n_threads = threads;
    params.enable_mmap = true;
    if (weight_type >= 0) {
        params.wtype = static_cast<sd_type_t>(weight_type);
    }
    LOGI("loading %s (threads=%d, wtype=%d)", model_path.c_str(), threads, weight_type);
    handle->ctx = new_sd_ctx(&params);
    if (handle->ctx == nullptr) {
        LOGE("new_sd_ctx returned null");
        delete handle;
        return 0;
    }
    LOGI("model loaded: %s (%s)", sd_get_model_version_name(handle->ctx),
         sd_get_system_info());
    return reinterpret_cast<jlong>(handle);
}

JNIEXPORT void JNICALL
Java_com_flowframes_android_gen_NativeDiffusion_freeModel(JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr) {
    auto* handle = reinterpret_cast<Handle*>(ptr);
    if (handle == nullptr) return;
    if (handle->ctx != nullptr) free_sd_ctx(handle->ctx);
    delete handle;
}

JNIEXPORT jstring JNICALL
Java_com_flowframes_android_gen_NativeDiffusion_modelName(JNIEnv* env, jobject /*thiz*/, jlong ptr) {
    auto* handle = reinterpret_cast<Handle*>(ptr);
    const char* name = handle != nullptr && handle->ctx != nullptr
                           ? sd_get_model_version_name(handle->ctx)
                           : "unknown";
    return env->NewStringUTF(name == nullptr ? "unknown" : name);
}

JNIEXPORT jintArray JNICALL
Java_com_flowframes_android_gen_NativeDiffusion_generate(
        JNIEnv* env, jobject /*thiz*/, jlong ptr,
        jstring prompt, jstring negative, jint width, jint height,
        jint steps, jfloat cfg, jlong seed, jint sample_method, jint scheduler,
        jintArray init_argb, jint init_width, jint init_height, jfloat strength,
        jobject progress) {
    auto* handle = reinterpret_cast<Handle*>(ptr);
    if (handle == nullptr || handle->ctx == nullptr) {
        set_last_error("no model loaded");
        return nullptr;
    }
    if (handle->running.exchange(true)) {
        set_last_error("a generation is already running");
        return nullptr;
    }
    sd_set_progress_callback(progress_callback, nullptr);
    if (progress != nullptr) {
        std::lock_guard<std::mutex> guard(g_lock);
        g_progress = env->NewGlobalRef(progress);
        jclass cls = env->GetObjectClass(progress);
        g_on_step = env->GetMethodID(cls, "onStep", "(II)V");
        env->DeleteLocalRef(cls);
    }

    sd_img_gen_params_t params;
    sd_img_gen_params_init(&params);
    const std::string prompt_text = to_string(env, prompt);
    const std::string negative_text = to_string(env, negative);
    params.prompt = prompt_text.c_str();
    params.negative_prompt = negative_text.c_str();
    params.width = width;
    params.height = height;
    params.seed = seed;
    params.batch_count = 1;
    params.strength = strength;
    params.sample_params.sample_steps = steps;
    params.sample_params.guidance.txt_cfg = cfg;
    // Negative values ask the model what it was trained with.
    params.sample_params.sample_method = sample_method < 0
                                             ? sd_get_default_sample_method(handle->ctx)
                                             : static_cast<sample_method_t>(sample_method);
    params.sample_params.scheduler = scheduler < 0
                                         ? sd_get_default_scheduler(handle->ctx,
                                                                    params.sample_params.sample_method)
                                         : static_cast<scheduler_t>(scheduler);

    std::vector<uint8_t> init_pixels;
    if (init_argb != nullptr && init_width > 0 && init_height > 0) {
        const jsize count = env->GetArrayLength(init_argb);
        if (count >= init_width * init_height) {
            std::vector<jint> argb(static_cast<size_t>(init_width) * init_height);
            env->GetIntArrayRegion(init_argb, 0, static_cast<jsize>(argb.size()), argb.data());
            init_pixels.resize(argb.size() * 3);
            for (size_t i = 0; i < argb.size(); ++i) {
                const jint px = argb[i];
                init_pixels[i * 3 + 0] = static_cast<uint8_t>((px >> 16) & 0xFF);
                init_pixels[i * 3 + 1] = static_cast<uint8_t>((px >> 8) & 0xFF);
                init_pixels[i * 3 + 2] = static_cast<uint8_t>(px & 0xFF);
            }
            params.init_image = sd_image_t{static_cast<uint32_t>(init_width),
                                           static_cast<uint32_t>(init_height), 3,
                                           init_pixels.data()};
        }
    }

    LOGI("generating %dx%d, %d steps, cfg %.1f, seed %lld", width, height, steps, cfg,
         static_cast<long long>(seed));
    sd_image_generation_result_t* result = nullptr;
    const bool ok = generate_image(handle->ctx, &params, &result);

    {
        std::lock_guard<std::mutex> guard(g_lock);
        if (g_progress != nullptr) {
            env->DeleteGlobalRef(g_progress);
            g_progress = nullptr;
        }
        g_on_step = nullptr;
    }
    handle->running.store(false);

    jintArray out = nullptr;
    if (ok && result != nullptr && result->count > 0 && result->items != nullptr) {
        out = to_argb(env, result->items[0].image);
    } else if (ok) {
        set_last_error("the engine returned no image");
    }
    if (result != nullptr) free_sd_image_generation_result(result);
    return out;
}

JNIEXPORT void JNICALL
Java_com_flowframes_android_gen_NativeDiffusion_cancel(JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr) {
    auto* handle = reinterpret_cast<Handle*>(ptr);
    if (handle == nullptr || handle->ctx == nullptr) return;
    sd_cancel_generation(handle->ctx, SD_CANCEL_ALL);
}

JNIEXPORT jstring JNICALL
Java_com_flowframes_android_gen_NativeDiffusion_lastError(JNIEnv* env, jobject /*thiz*/) {
    const std::string message = take_last_error();
    return env->NewStringUTF(message.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_flowframes_android_gen_NativeDiffusion_systemInfo(JNIEnv* env, jobject /*thiz*/) {
    const char* info = sd_get_system_info();
    return env->NewStringUTF(info == nullptr ? "" : info);
}

}  // extern "C"
