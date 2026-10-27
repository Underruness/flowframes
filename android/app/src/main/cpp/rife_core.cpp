#include "rife_core.h"

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <condition_variable>
#include <mutex>
#include <thread>

#include "cpu.h"
#include "gpu.h"
#include "layer.h"
#include "rife_ops.h"
#include "shader_cache.h"
#include "crash_handler.h"

DEFINE_LAYER_CREATOR(Warp)

namespace flowframes {

static std::once_flag g_gpuOnce;

void ensureGpuInstance() {
    std::call_once(g_gpuOnce, [] {
        ncnn::create_gpu_instance();
        installPipelineCacheHook();
    });
}

int RifeCore::gpuCount() {
    ensureGpuInstance();
    return ncnn::get_gpu_count();
}

std::string RifeCore::gpuName(int index) {
    ensureGpuInstance();
    if (index < 0 || index >= ncnn::get_gpu_count()) return "";
    return ncnn::get_gpu_info(index).device_name();
}

std::string RifeCore::gpuInfo(int index) {
    ensureGpuInstance();
    if (index < 0 || index >= ncnn::get_gpu_count()) return "";
    const ncnn::GpuInfo& g = ncnn::get_gpu_info(index);
    uint32_t api = g.api_version();
    char buf[512];
    snprintf(buf, sizeof(buf),
             "%s · Vulkan %u.%u.%u · driver 0x%x · vendor 0x%x device 0x%x · fp16 storage %d arith %d · "
             "coopmat %d · subgroup %u · implicit-fp16-bug %d",
             g.device_name(), VK_VERSION_MAJOR(api), VK_VERSION_MINOR(api), VK_VERSION_PATCH(api),
             g.driver_version(), g.vendor_id(), g.device_id(), (int)g.support_fp16_storage(),
             (int)g.support_fp16_arithmetic(), (int)g.support_cooperative_matrix(), g.subgroup_size(),
             (int)g.bug_implicit_fp16_arithmetic());
    return buf;
}

RifeCore::RifeCore() = default;

RifeCore::~RifeCore() {
    flownet.clear();
}

std::string RifeCore::deviceName() const {
    if (vkdev) return vkdev->info.device_name();
    return "CPU";
}

int RifeCore::load(const RifeConfig& config, std::string& error) {
    cfg = config;
    if (cfg.padding <= 0) cfg.padding = 32;
    pinOpenMpRuntime();

    vkdev = nullptr;
    if (cfg.gpuId >= 0) {
        ensureGpuInstance();
        int count = ncnn::get_gpu_count();
        if (cfg.gpuId >= count) {
            error = count == 0 ? "no Vulkan device" : "Vulkan device index out of range";
            return -1;
        }
        vkdev = ncnn::get_gpu_device(cfg.gpuId);
        if (!vkdev) {
            error = "Vulkan device unavailable";
            return -1;
        }
    }

    ncnn::Option opt;
    opt.num_threads = std::max(1, cfg.cpuThreads);
    opt.use_vulkan_compute = vkdev != nullptr;
    opt.use_packing_layout = true;
    if (vkdev) {
        opt.use_fp16_packed = true;
        opt.use_fp16_storage = true;
        // fp16 arithmetic doubles ALU throughput on Adreno/Mali; the custom warp keeps its
        // sampling coordinates in fp32 so it stays sharp either way.
        opt.use_fp16_arithmetic = cfg.fp16Arithmetic;
        // fp16 math hung a Snapdragon 8 Elite; the Adreno coopmat kernels (re-enabled by ncnn
        // 20261020) are the suspect, so they follow their own switch there. Without fp16 math
        // ncnn's default is kept: it is the configuration measured at 6 fps on that phone.
        if (cfg.fp16Arithmetic) opt.use_cooperative_matrix = cfg.cooperativeMatrix;
    } else {
        // ARM CPU: ncnn's ARMv8.2 fp16 kernels are opt-in (same switch as the GPU), bf16 is off.
        opt.use_fp16_packed = cfg.fp16Arithmetic;
        opt.use_fp16_storage = cfg.fp16Arithmetic;
        opt.use_fp16_arithmetic = cfg.fp16Arithmetic;
        opt.use_bf16_storage = false;
    }
    opt.use_int8_storage = false;
    opt.use_int8_inference = false;

    flownet.opt = opt;
    if (vkdev) flownet.set_vulkan_device(vkdev);
    flownet.register_custom_layer("rife.Warp", Warp_layer_creator);

    std::string param = cfg.modelDir + "/flownet.param";
    std::string bin = cfg.modelDir + "/flownet.bin";
    setStage("ncnn load_param");
    if (flownet.load_param(param.c_str()) != 0) {
        error = "cannot load " + param;
        return -2;
    }
    setStage(vkdev ? "ncnn load_model (GPU shader + pipeline build)" : "ncnn load_model (CPU)");
    if (flownet.load_model(bin.c_str()) != 0) {
        error = "cannot load " + bin;
        return -3;
    }
    loaded = true;
    // ncnn builds every layer's pipeline in load_model: persist them before the first frame.
    if (vkdev) {
        setStage("saving the GPU shader cache");
        flushShaderCaches();
    }
    setStage("idle");
    return 0;
}

// Copies a planar frame into a zero-padded ncnn Mat (rife_preproc pads with zeros, so does this).
void RifeCore::fillPadded(const float* planar, int width, int height, ncnn::Mat& dst) const {
    const int plane = width * height;
    for (int c = 0; c < 3; c++) {
        float* outc = dst.channel(c);
        const float* inc = planar + (size_t)c * plane;
        if (dst.w == width && dst.h == height && dst.cstep == (size_t)plane) {
            memcpy(outc, inc, sizeof(float) * plane);
            continue;
        }
        for (int y = 0; y < dst.h; y++) {
            float* row = outc + (size_t)y * dst.w;
            if (y < height) {
                memcpy(row, inc + (size_t)y * width, sizeof(float) * width);
                if (dst.w > width) memset(row + width, 0, sizeof(float) * (dst.w - width));
            } else {
                memset(row, 0, sizeof(float) * dst.w);
            }
        }
    }
}

// Android ARGB ints (0xAARRGGBB) straight into the padded planar float input: the Java side
// keeps 8-bit frames (4 bytes per pixel instead of 12), which is what makes 1440p and 4K fit.
void RifeCore::fillPaddedArgb(const uint32_t* argb, int width, int height, ncnn::Mat& dst) const {
    const float inv = 1.f / 255.f;
    float* r = dst.channel(0);
    float* g = dst.channel(1);
    float* b = dst.channel(2);
    for (int y = 0; y < dst.h; y++) {
        float* rr = r + (size_t)y * dst.w;
        float* gg = g + (size_t)y * dst.w;
        float* bb = b + (size_t)y * dst.w;
        if (y >= height) {
            memset(rr, 0, sizeof(float) * dst.w);
            memset(gg, 0, sizeof(float) * dst.w);
            memset(bb, 0, sizeof(float) * dst.w);
            continue;
        }
        const uint32_t* src = argb + (size_t)y * width;
        for (int x = 0; x < width; x++) {
            const uint32_t c = src[x];
            rr[x] = (float)((c >> 16) & 0xFF) * inv;
            gg[x] = (float)((c >> 8) & 0xFF) * inv;
            bb[x] = (float)(c & 0xFF) * inv;
        }
        for (int x = width; x < dst.w; x++) rr[x] = gg[x] = bb[x] = 0.f;
    }
}

int RifeCore::run(const ncnn::Mat& in0, const ncnn::Mat& in1, int width, int height, float timestep,
                  ncnn::Mat& result) const {
    ncnn::Mat ts(in0.w, in0.h, 1);
    if (ts.empty()) return -100;
    ts.fill(timestep);
    {
        ncnn::Extractor ex = flownet.create_extractor();
        if (ex.input("in0", in0) != 0) return -2;
        if (ex.input("in1", in1) != 0) return -2;
        if (ex.input("in2", ts) != 0) return -2;
        setStage(vkdev ? "ncnn extract (GPU inference)" : "ncnn extract (CPU inference)");
        int ret = ex.extract("out0", result);
        setStage("idle");
        if (ret != 0) return -3;
    }
    if (result.empty() || result.c != 3 || result.w < width || result.h < height) return -4;
    if (result.elemsize != 4 || result.elempack != 1) return -5;
    return 0;
}

int RifeCore::interpolateArgb(const uint32_t* frame0, const uint32_t* frame1, int width, int height,
                              float timestep, uint32_t* out) const {
    if (!loaded) return -1;
    const size_t plane = (size_t)width * height;
    if (timestep <= 0.f) {
        memcpy(out, frame0, sizeof(uint32_t) * plane);
        return 0;
    }
    if (timestep >= 1.f) {
        memcpy(out, frame1, sizeof(uint32_t) * plane);
        return 0;
    }
    const int pad = cfg.padding;
    const int wp = (width + pad - 1) / pad * pad;
    const int hp = (height + pad - 1) / pad * pad;
    ncnn::Mat in0(wp, hp, 3);
    ncnn::Mat in1(wp, hp, 3);
    if (in0.empty() || in1.empty()) return -100;
    fillPaddedArgb(frame0, width, height, in0);
    fillPaddedArgb(frame1, width, height, in1);
    ncnn::Mat result;
    int ret = run(in0, in1, width, height, timestep, result);
    if (ret != 0) return ret;
    const float* r = result.channel(0);
    const float* g = result.channel(1);
    const float* b = result.channel(2);
    for (int y = 0; y < height; y++) {
        const size_t row = (size_t)y * result.w;
        uint32_t* d = out + (size_t)y * width;
        for (int x = 0; x < width; x++) {
            // Same rounding as FrameMath.planarToArgb: round half up, clamp to [0, 255].
            int rr = (int)(r[row + x] * 255.f + 0.5f);
            int gg = (int)(g[row + x] * 255.f + 0.5f);
            int bb = (int)(b[row + x] * 255.f + 0.5f);
            rr = rr < 0 ? 0 : (rr > 255 ? 255 : rr);
            gg = gg < 0 ? 0 : (gg > 255 ? 255 : gg);
            bb = bb < 0 ? 0 : (bb > 255 ? 255 : bb);
            d[x] = 0xFF000000u | ((uint32_t)rr << 16) | ((uint32_t)gg << 8) | (uint32_t)bb;
        }
    }
    return 0;
}

int RifeCore::interpolate(const float* frame0, const float* frame1, int width, int height,
                          float timestep, float* out) const {
    if (!loaded) return -1;
    const size_t plane = (size_t)width * height;
    if (timestep <= 0.f) {
        memcpy(out, frame0, sizeof(float) * plane * 3);
        return 0;
    }
    if (timestep >= 1.f) {
        memcpy(out, frame1, sizeof(float) * plane * 3);
        return 0;
    }

    const int pad = cfg.padding;
    const int wp = (width + pad - 1) / pad * pad;
    const int hp = (height + pad - 1) / pad * pad;

    ncnn::Mat in0(wp, hp, 3);
    ncnn::Mat in1(wp, hp, 3);
    if (in0.empty() || in1.empty()) return -100;
    fillPadded(frame0, width, height, in0);
    fillPadded(frame1, width, height, in1);
    ncnn::Mat result;
    int ret = run(in0, in1, width, height, timestep, result);
    if (ret != 0) return ret;

    for (int c = 0; c < 3; c++) {
        const float* src = result.channel(c);
        float* dst = out + (size_t)c * plane;
        for (int y = 0; y < height; y++) {
            const float* r = src + (size_t)y * result.w;
            float* d = dst + (size_t)y * width;
            for (int x = 0; x < width; x++) {
                float v = r[x];
                d[x] = v < 0.f ? 0.f : (v > 1.f ? 1.f : v);
            }
        }
    }
    return 0;
}

} // namespace flowframes

namespace flowframes {

// See crash_handler.cpp (ffOpenMpEnv): one OpenMP root thread for the life of the process.
void pinOpenMpRuntime() {
    static std::once_flag once;
    std::call_once(once, [] {
        std::mutex m;
        std::condition_variable cv;
        bool ready = false;
        std::thread([&] {
            // ncnn's fp16 -> fp32 conversion is the parallel region that aborted: run it once.
            unsigned short half[64] = {0};
            ncnn::Mat warm = ncnn::Mat::from_float16(half, 64);
            (void)warm;
            {
                // Notified under the lock: the waiter (and m / cv) cannot go away before.
                std::lock_guard<std::mutex> lk(m);
                ready = true;
                cv.notify_all();
            }
            // Never returns: keeps libomp initialised.
            std::mutex forever;
            std::unique_lock<std::mutex> lk(forever);
            std::condition_variable never;
            never.wait(lk, [] { return false; });
        }).detach();
        std::unique_lock<std::mutex> lk(m);
        cv.wait(lk, [&] { return ready; });
    });
}

} // namespace flowframes
