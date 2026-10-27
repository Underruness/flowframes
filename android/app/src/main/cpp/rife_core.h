// RIFE v4 frame interpolation on ncnn (Vulkan GPU or ARM/x86 CPU).
//
// Frames are exchanged as planar RGB float in [0,1] (CHW), exactly like the ONNX Runtime engine
// of the Kotlin side, so both engines are interchangeable and can be compared pixel for pixel.
#ifndef FLOWFRAMES_RIFE_CORE_H
#define FLOWFRAMES_RIFE_CORE_H

#include <atomic>
#include <string>

#include "net.h"

namespace flowframes {

struct RifeConfig {
    std::string modelDir;   // directory holding flownet.param / flownet.bin
    int gpuId = 0;          // -1 = CPU
    int cpuThreads = 4;     // CPU path, and CPU-side layers of the GPU path
    int padding = 32;       // 32 for most v4 models, 64 for v4.25/v4.26, 128 for v4.25-lite
    bool fp16Arithmetic = false; // GPU: fp16 math in the convolutions (storage is always fp16)
    // VK_KHR_cooperative_matrix kernels (fp16 only). ncnn 20261020 re-enabled them on Adreno,
    // where they hang or corrupt on some drivers: off unless explicitly asked for.
    bool cooperativeMatrix = false;
};

class RifeCore {
public:
    RifeCore();
    ~RifeCore();

    // Returns 0 on success, a negative code otherwise; [error] gets a readable reason.
    int load(const RifeConfig& config, std::string& error);

    // Thread-safe: several threads may interpolate concurrently (one extractor per call).
    int interpolate(const float* frame0, const float* frame1, int width, int height, float timestep,
                    float* out) const;

    bool usesGpu() const { return vkdev != nullptr; }
    std::string deviceName() const;
    const RifeConfig& config() const { return cfg; }

    static int gpuCount();
    static std::string gpuName(int index);
    // One-line capability summary (driver, fp16, cooperative matrix…) for the job log.
    static std::string gpuInfo(int index);

private:
    void fillPadded(const float* planar, int width, int height, ncnn::Mat& dst) const;

    RifeConfig cfg;
    ncnn::VulkanDevice* vkdev = nullptr;
    ncnn::Net flownet;
    bool loaded = false;
};

// ncnn's GPU instance is process-wide; it is created on first use and destroyed at unload.
void ensureGpuInstance();

} // namespace flowframes

#endif
