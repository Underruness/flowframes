#include "rife_core.h"

#include <algorithm>
#include <cstring>
#include <mutex>

#include "cpu.h"
#include "gpu.h"
#include "layer.h"
#include "rife_ops.h"

DEFINE_LAYER_CREATOR(Warp)

namespace flowframes {

static std::once_flag g_gpuOnce;

void ensureGpuInstance() {
    std::call_once(g_gpuOnce, [] { ncnn::create_gpu_instance(); });
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
    if (flownet.load_param(param.c_str()) != 0) {
        error = "cannot load " + param;
        return -2;
    }
    if (flownet.load_model(bin.c_str()) != 0) {
        error = "cannot load " + bin;
        return -3;
    }
    loaded = true;
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
    ncnn::Mat ts(wp, hp, 1);
    if (in0.empty() || in1.empty() || ts.empty()) return -100;
    fillPadded(frame0, width, height, in0);
    fillPadded(frame1, width, height, in1);
    ts.fill(timestep);

    ncnn::Mat result;
    {
        ncnn::Extractor ex = flownet.create_extractor();
        if (ex.input("in0", in0) != 0) return -2;
        if (ex.input("in1", in1) != 0) return -2;
        if (ex.input("in2", ts) != 0) return -2;
        int ret = ex.extract("out0", result);
        if (ret != 0) return -3;
    }
    if (result.empty() || result.c != 3 || result.w < width || result.h < height) return -4;
    if (result.elemsize != 4 || result.elempack != 1) return -5;

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
