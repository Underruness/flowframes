// Host harness for rife_core: runs the ncnn engine on raw planar float frames and times it.
// usage: host_test <modeldir> <gpu|-1> <fp16 0|1> <w> <h> <frame0.f32> <frame1.f32> <t> <out.f32> [runs] [threads] [padding]
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <thread>
#include <vector>
#include "rife_core.h"
#include "shader_cache.h"
#include "gpu.h"

static std::vector<float> readf(const char* p, size_t n) {
    std::vector<float> v(n); FILE* f = fopen(p, "rb"); if (!f) { perror(p); exit(1); }
    size_t r = fread(v.data(), 4, n, f); fclose(f); if (r != n) { fprintf(stderr, "short read %s\n", p); exit(1); } return v;
}
int main(int argc, char** argv) {
    if (argc < 10) { fprintf(stderr, "bad args\n"); return 1; }
    flowframes::RifeConfig cfg; cfg.modelDir = argv[1]; cfg.gpuId = atoi(argv[2]); cfg.fp16Arithmetic = atoi(argv[3]);
    int w = atoi(argv[4]), h = atoi(argv[5]); float t = atof(argv[8]);
    int runs = argc > 10 ? atoi(argv[10]) : 1; cfg.cpuThreads = argc > 11 ? atoi(argv[11]) : 2;
    int par = argc > 13 ? atoi(argv[13]) : 1;
    if (argc > 12) cfg.padding = atoi(argv[12]);
    auto a = readf(argv[6], (size_t)w*h*3), b = readf(argv[7], (size_t)w*h*3);
    std::vector<float> o((size_t)w*h*3);
    if (getenv("FF_SHADER_CACHE")) flowframes::setShaderCacheDir(getenv("FF_SHADER_CACHE"));
    if (getenv("FF_COOP")) cfg.cooperativeMatrix = atoi(getenv("FF_COOP")) != 0;
    flowframes::RifeCore core; std::string err;
    auto l0 = std::chrono::steady_clock::now();
    if (core.load(cfg, err)) { fprintf(stderr, "load failed: %s\n", err.c_str()); return 2; }
    fprintf(stderr, "device: %s\n", core.deviceName().c_str());
    int ret = core.interpolate(a.data(), b.data(), w, h, t, o.data());
    if (ret) { fprintf(stderr, "interp failed %d\n", ret); return 3; }
    flowframes::flushShaderCaches();
    fprintf(stderr, "load + first inference: %.0f ms · %s\n",
            std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - l0).count(),
            flowframes::shaderCacheStats().c_str());
    auto t0 = std::chrono::steady_clock::now();
    std::vector<std::thread> th;
    for (int p = 0; p < par; p++) th.emplace_back([&, p] { std::vector<float> o2((size_t)w*h*3); for (int i = 0; i < runs; i++) core.interpolate(a.data(), b.data(), w, h, t, p ? o2.data() : o.data()); });
    for (auto& x : th) x.join();
    double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    fprintf(stderr, "%d x %d runs: %.1f ms per inference (throughput)\n", par, runs, ms / (runs * par));
    FILE* f = fopen(argv[9], "wb"); fwrite(o.data(), 4, o.size(), f); fclose(f);
    ncnn::destroy_gpu_instance();
    return 0;
}
