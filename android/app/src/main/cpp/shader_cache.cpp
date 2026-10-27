#include "shader_cache.h"
#include "crash_handler.h"

#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <unordered_map>
#include <vector>

#include "gpu.h"
#include "option.h"
#include "platform.h"

// Mangled name of ncnn::compile_spirv_module(int, const ncnn::Option&, std::vector<uint32_t>&).
// It differs between libc++ (Android NDK, inline namespace __ndk1) and libstdc++ (host tests);
// CMake passes the same string to -Wl,--wrap.
#if defined(__ANDROID__)
#define FF_COMPILE_SYM "_ZN4ncnn20compile_spirv_moduleEiRKNS_6OptionERNSt6__ndk16vectorIjNS3_9allocatorIjEEEE"
#else
#define FF_COMPILE_SYM "_ZN4ncnn20compile_spirv_moduleEiRKNS_6OptionERSt6vectorIjSaIjEE"
#endif

// The linker routes ncnn's calls to __wrap_<sym> and keeps the original as __real_<sym>.
extern int ff_real_compile(int index, const ncnn::Option& opt, std::vector<uint32_t>& spirv)
    __asm__("__real_" FF_COMPILE_SYM);
int ff_wrap_compile(int index, const ncnn::Option& opt, std::vector<uint32_t>& spirv)
    __asm__("__wrap_" FF_COMPILE_SYM);

namespace flowframes {

namespace {

constexpr uint32_t kSpirvMagic = 0x43535046;    // "FPSC"
constexpr uint32_t kPipelineMagic = 0x43505046; // "FPPC"
constexpr uint32_t kFormat = 1;

std::mutex g_lock;
std::string g_dir;
bool g_spirvLoaded = false;
std::unordered_map<uint64_t, std::vector<uint32_t>> g_spirv;
std::atomic<bool> g_spirvDirty{false};

std::atomic<long long> g_spirvCompiled{0}, g_spirvHits{0}, g_spirvNs{0};
std::atomic<long long> g_pipelines{0}, g_pipelineNs{0};
std::atomic<long long> g_spirvFileBytes{0}, g_pipelineFileBytes{0};
std::atomic<bool> g_pipelineDirty{false};
bool g_pipelineFromDisk = false;
std::atomic<bool> g_pipelineDisk{true}; // off after a crash during a GPU build

PFN_vkCreateComputePipelines g_origCreate = nullptr;
PFN_vkCreatePipelineCache g_origCreateCache = nullptr;
PFN_vkDestroyPipelineCache g_origDestroyCache = nullptr;
// Every VkPipelineCache ncnn uses (it creates its own and passes them to every pipeline build).
struct LiveCache {
    VkDevice device;
    VkPipelineCache cache;
};
std::vector<LiveCache> g_caches;
VkPipelineCache g_ownCache = VK_NULL_HANDLE; // only if ncnn ever builds a pipeline without one

long long nowNs() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
               std::chrono::steady_clock::now().time_since_epoch()).count();
}

struct Fnv {
    uint64_t h = 1469598103934665603ULL;
    void bytes(const void* p, size_t n) {
        const unsigned char* c = static_cast<const unsigned char*>(p);
        for (size_t i = 0; i < n; i++) {
            h ^= c[i];
            h *= 1099511628211ULL;
        }
    }
    template <typename T> void add(const T& v) { bytes(&v, sizeof(v)); }
};

// Everything that can change the generated SPIR-V: the shader, the ncnn build, the precision and
// layout switches of the Option, and the device the defines are derived from. Pointers (the
// allocators) and thread counts are left out on purpose.
uint64_t keyOf(int index, const ncnn::Option& o) {
    Fnv f;
    const char* ver = NCNN_VERSION_STRING;
    f.bytes(ver, strlen(ver));
    f.add(index);
    const bool flags[] = {
        o.lightmode, o.use_subgroup_ops, o.use_winograd_convolution, o.use_sgemm_convolution,
        o.use_int8_inference, o.use_vulkan_compute, o.use_bf16_storage, o.use_fp16_packed,
        o.use_fp16_storage, o.use_fp16_arithmetic, o.use_int8_packed, o.use_int8_storage,
        o.use_int8_arithmetic, o.use_packing_layout, o.use_bf16_packed, o.use_tensor_storage,
        o.use_weights_in_host_memory, o.use_mapped_model_loading, o.use_local_pool_allocator,
        o.use_shader_local_memory, o.use_cooperative_matrix, o.use_winograd23_convolution,
        o.use_winograd43_convolution, o.use_winograd63_convolution, o.use_a53_a55_optimized_kernel,
        o.use_fp16_uniform, o.use_int8_uniform, o.use_int16_packed, o.use_int16_storage,
        o.use_reserved_m0, o.use_reserved_0, o.use_reserved_1p, o.use_reserved_2f,
        o.use_reserved_3f, o.use_reserved_11};
    f.bytes(flags, sizeof(flags));
    f.add(o.vulkan_device_index);
    const int gpu = o.vulkan_device_index;
    if (gpu >= 0 && gpu < ncnn::get_gpu_count()) {
        const ncnn::GpuInfo& g = ncnn::get_gpu_info(gpu);
        f.add(g.vendor_id());
        f.add(g.device_id());
        f.add(g.driver_version());
        f.add(g.api_version());
        f.add(g.subgroup_size());
    }
    return f.h;
}

std::string spirvPath() { return g_dir + "/spirv.cache"; }
std::string pipelinePath() { return g_dir + "/vk_pipelines.cache"; }

bool readFile(const std::string& path, std::vector<unsigned char>& out) {
    FILE* fp = fopen(path.c_str(), "rb");
    if (!fp) return false;
    fseek(fp, 0, SEEK_END);
    long size = ftell(fp);
    fseek(fp, 0, SEEK_SET);
    if (size <= 0 || size > 512L * 1024 * 1024) {
        fclose(fp);
        return false;
    }
    out.resize((size_t)size);
    bool ok = fread(out.data(), 1, out.size(), fp) == out.size();
    fclose(fp);
    return ok;
}

// Written next to the target and renamed over it, so a crash never leaves a truncated cache.
bool writeFileAtomic(const std::string& path, const std::vector<unsigned char>& data) {
    std::string tmp = path + ".tmp";
    FILE* fp = fopen(tmp.c_str(), "wb");
    if (!fp) return false;
    bool ok = fwrite(data.data(), 1, data.size(), fp) == data.size();
    ok = (fclose(fp) == 0) && ok;
    if (!ok || rename(tmp.c_str(), path.c_str()) != 0) {
        remove(tmp.c_str());
        return false;
    }
    return true;
}

template <typename T> void put(std::vector<unsigned char>& b, const T& v) {
    const unsigned char* p = reinterpret_cast<const unsigned char*>(&v);
    b.insert(b.end(), p, p + sizeof(T));
}

template <typename T> bool get(const std::vector<unsigned char>& b, size_t& pos, T& v) {
    if (pos + sizeof(T) > b.size()) return false;
    memcpy(&v, b.data() + pos, sizeof(T));
    pos += sizeof(T);
    return true;
}

void loadSpirvLocked() {
    if (g_spirvLoaded || g_dir.empty()) return;
    g_spirvLoaded = true;
    std::vector<unsigned char> b;
    if (!readFile(spirvPath(), b)) return;
    size_t pos = 0;
    uint32_t magic = 0, format = 0, count = 0;
    if (!get(b, pos, magic) || !get(b, pos, format) || !get(b, pos, count)) return;
    if (magic != kSpirvMagic || format != kFormat) return;
    std::unordered_map<uint64_t, std::vector<uint32_t>> loaded;
    for (uint32_t i = 0; i < count; i++) {
        uint64_t key = 0;
        uint32_t words = 0;
        if (!get(b, pos, key) || !get(b, pos, words)) return;
        if (pos + (size_t)words * 4 > b.size()) return; // truncated: ignore the whole file
        std::vector<uint32_t> code(words);
        memcpy(code.data(), b.data() + pos, (size_t)words * 4);
        pos += (size_t)words * 4;
        // SPIR-V starts with its own magic number; anything else is not a module.
        if (words < 5 || code[0] != 0x07230203) return;
        loaded.emplace(key, std::move(code));
    }
    g_spirv.swap(loaded);
    g_spirvFileBytes = (long long)b.size();
}

void saveSpirvLocked() {
    if (g_dir.empty() || !g_spirvDirty.exchange(false)) return;
    std::vector<unsigned char> b;
    put(b, kSpirvMagic);
    put(b, kFormat);
    put(b, (uint32_t)g_spirv.size());
    for (const auto& e : g_spirv) {
        put(b, e.first);
        put(b, (uint32_t)e.second.size());
        const unsigned char* p = reinterpret_cast<const unsigned char*>(e.second.data());
        b.insert(b.end(), p, p + e.second.size() * 4);
    }
    if (writeFileAtomic(spirvPath(), b)) g_spirvFileBytes = (long long)b.size();
}

// Our header in front of the driver's blob: the driver validates its own header too, but some
// drivers are known to crash on foreign data, so a mismatch never reaches it.
struct PipelineHeader {
    uint32_t magic, format, vendor, device, driver;
    uint8_t uuid[16];
    uint64_t size;
};

void fillHeader(PipelineHeader& h, int gpu) {
    memset(&h, 0, sizeof(h));
    const ncnn::GpuInfo& g = ncnn::get_gpu_info(gpu);
    h.magic = kPipelineMagic;
    h.format = kFormat;
    h.vendor = g.vendor_id();
    h.device = g.device_id();
    h.driver = g.driver_version();
    const uint8_t* uuid = g.pipeline_cache_uuid();
    if (uuid) memcpy(h.uuid, uuid, 16);
}

// Reads the saved blob if it was written by this GPU and driver. Caller holds g_lock.
bool loadPipelineBlobLocked(std::vector<unsigned char>& file, const void*& data, size_t& size) {
    data = nullptr;
    size = 0;
    if (g_dir.empty() || !g_pipelineDisk || ncnn::get_gpu_count() == 0) return false;
    if (!readFile(pipelinePath(), file) || file.size() <= sizeof(PipelineHeader)) return false;
    PipelineHeader want, have;
    fillHeader(want, 0);
    memcpy(&have, file.data(), sizeof(have));
    want.size = have.size;
    if (memcmp(&want, &have, sizeof(want)) != 0 || have.size != file.size() - sizeof(have)) return false;
    data = file.data() + sizeof(have);
    size = (size_t)have.size;
    return true;
}

void trackLocked(VkDevice device, VkPipelineCache cache) {
    if (cache == VK_NULL_HANDLE) return;
    for (const auto& c : g_caches) {
        if (c.cache == cache) return;
    }
    g_caches.push_back({device, cache});
}

// Merges every live cache into a scratch one and writes it out. Caller holds g_lock.
void savePipelinesLocked() {
    if (g_dir.empty() || !g_pipelineDisk || g_caches.empty() || !g_pipelineDirty.exchange(false)) return;
    const VkDevice device = g_caches[0].device;
    std::vector<VkPipelineCache> sources;
    for (const auto& c : g_caches) {
        if (c.device == device) sources.push_back(c.cache);
    }
    VkPipelineCacheCreateInfo info;
    memset(&info, 0, sizeof(info));
    info.sType = VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO;
    VkPipelineCache merged = VK_NULL_HANDLE;
    setStage("GPU driver: merging pipeline caches for the disk cache");
    if (g_origCreateCache(device, &info, nullptr, &merged) != VK_SUCCESS) return;
    size_t size = 0;
    if (ncnn::vkMergePipelineCaches(device, merged, (uint32_t)sources.size(), sources.data()) == VK_SUCCESS &&
        ncnn::vkGetPipelineCacheData(device, merged, &size, nullptr) == VK_SUCCESS && size > 0) {
        std::vector<unsigned char> b(sizeof(PipelineHeader) + size);
        if (ncnn::vkGetPipelineCacheData(device, merged, &size, b.data() + sizeof(PipelineHeader)) == VK_SUCCESS) {
            b.resize(sizeof(PipelineHeader) + size);
            PipelineHeader h;
            fillHeader(h, 0);
            h.size = size;
            memcpy(b.data(), &h, sizeof(h));
            if (writeFileAtomic(pipelinePath(), b)) g_pipelineFileBytes = (long long)b.size();
        }
    }
    g_origDestroyCache(device, merged, nullptr);
    setStage("idle");
}

// ncnn creates its caches empty; they get the blob saved by the previous run instead.
VKAPI_ATTR VkResult VKAPI_CALL hookedCreatePipelineCache(
    VkDevice device, const VkPipelineCacheCreateInfo* info, const VkAllocationCallbacks* allocator,
    VkPipelineCache* out) {
    std::lock_guard<std::mutex> lock(g_lock);
    VkResult r = VK_ERROR_INITIALIZATION_FAILED;
    std::vector<unsigned char> file;
    const void* data = nullptr;
    size_t size = 0;
    if (info && info->initialDataSize == 0 && loadPipelineBlobLocked(file, data, size)) {
        VkPipelineCacheCreateInfo seeded = *info;
        seeded.initialDataSize = size;
        seeded.pInitialData = data;
        const char* before = currentStage();
        setStage("GPU driver: loading the saved pipeline cache");
        r = g_origCreateCache(device, &seeded, allocator, out);
        setStage(before);
        if (r == VK_SUCCESS) {
            g_pipelineFromDisk = true;
            g_pipelineFileBytes = (long long)file.size();
        }
    }
    // No blob, or the driver refused it: an empty cache, as ncnn asked.
    if (r != VK_SUCCESS) r = g_origCreateCache(device, info, allocator, out);
    if (r == VK_SUCCESS) trackLocked(device, *out);
    return r;
}

VKAPI_ATTR void VKAPI_CALL hookedDestroyPipelineCache(VkDevice device, VkPipelineCache cache,
                                                      const VkAllocationCallbacks* allocator) {
    {
        std::lock_guard<std::mutex> lock(g_lock);
        savePipelinesLocked(); // last chance for what this cache learnt
        for (size_t i = 0; i < g_caches.size(); i++) {
            if (g_caches[i].cache == cache) {
                g_caches.erase(g_caches.begin() + (long)i);
                break;
            }
        }
        if (cache == g_ownCache) g_ownCache = VK_NULL_HANDLE;
    }
    g_origDestroyCache(device, cache, allocator);
}

VkPipelineCache ownCacheFor(VkDevice device) {
    std::lock_guard<std::mutex> lock(g_lock);
    if (g_ownCache != VK_NULL_HANDLE || g_dir.empty()) return g_ownCache;
    VkPipelineCacheCreateInfo info;
    memset(&info, 0, sizeof(info));
    info.sType = VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO;
    std::vector<unsigned char> file;
    const void* data = nullptr;
    size_t size = 0;
    if (loadPipelineBlobLocked(file, data, size)) {
        info.initialDataSize = size;
        info.pInitialData = data;
    }
    if (g_origCreateCache(device, &info, nullptr, &g_ownCache) != VK_SUCCESS) {
        info.initialDataSize = 0;
        info.pInitialData = nullptr;
        if (g_origCreateCache(device, &info, nullptr, &g_ownCache) != VK_SUCCESS) g_ownCache = VK_NULL_HANDLE;
    }
    trackLocked(device, g_ownCache);
    return g_ownCache;
}

VKAPI_ATTR VkResult VKAPI_CALL hookedCreateComputePipelines(
    VkDevice device, VkPipelineCache cache, uint32_t count, const VkComputePipelineCreateInfo* infos,
    const VkAllocationCallbacks* allocator, VkPipeline* pipelines) {
    if (cache == VK_NULL_HANDLE) cache = ownCacheFor(device);
    const char* before = currentStage();
    setStage("GPU driver: building compute pipelines");
    const long long t0 = nowNs();
    VkResult r = g_origCreate(device, cache, count, infos, allocator, pipelines);
    setStage(before);
    g_pipelineNs += nowNs() - t0;
    g_pipelines += count;
    if (r == VK_SUCCESS && cache != VK_NULL_HANDLE) {
        g_pipelineDirty = true;
        std::lock_guard<std::mutex> lock(g_lock);
        trackLocked(device, cache); // a cache created before the hook was installed
    }
    return r;
}

std::string mb(long long bytes) {
    char buf[32];
    snprintf(buf, sizeof(buf), "%.1f MB", bytes / (1024.0 * 1024.0));
    return buf;
}

} // namespace

void setShaderCacheDir(const std::string& dir) {
    std::lock_guard<std::mutex> lock(g_lock);
    g_dir = dir;
}

void setPipelineDiskCache(bool enabled) {
    g_pipelineDisk = enabled;
}

void installPipelineCacheHook() {
    std::lock_guard<std::mutex> lock(g_lock);
    if (g_origCreate || !ncnn::vkCreateComputePipelines || !ncnn::vkCreatePipelineCache ||
        !ncnn::vkDestroyPipelineCache || !ncnn::vkMergePipelineCaches || !ncnn::vkGetPipelineCacheData) {
        return;
    }
    g_origCreate = ncnn::vkCreateComputePipelines;
    g_origCreateCache = ncnn::vkCreatePipelineCache;
    g_origDestroyCache = ncnn::vkDestroyPipelineCache;
    ncnn::vkCreateComputePipelines = hookedCreateComputePipelines;
    ncnn::vkCreatePipelineCache = hookedCreatePipelineCache;
    ncnn::vkDestroyPipelineCache = hookedDestroyPipelineCache;
}

void flushShaderCaches() {
    if (!g_spirvDirty && !g_pipelineDirty) return;
    std::lock_guard<std::mutex> lock(g_lock);
    saveSpirvLocked();
    savePipelinesLocked();
}

std::string shaderCacheStats() {
    char buf[384];
    snprintf(buf, sizeof(buf),
             "shaders: %lld compiled in %.1f s, %lld from the disk cache · GPU pipelines: %lld built in %.1f s "
             "(driver cache %s) · cache files %s + %s",
             (long long)g_spirvCompiled, g_spirvNs / 1e9, (long long)g_spirvHits, (long long)g_pipelines,
             g_pipelineNs / 1e9,
             g_dir.empty() || !g_pipelineDisk ? "off" : (g_pipelineFromDisk ? "loaded from disk" : "new"),
             mb(g_spirvFileBytes).c_str(), mb(g_pipelineFileBytes).c_str());
    return buf;
}

void clearShaderCaches() {
    std::lock_guard<std::mutex> lock(g_lock);
    g_spirv.clear();
    g_spirvDirty = false;
    g_pipelineDirty = false;
    if (!g_dir.empty()) {
        remove(spirvPath().c_str());
        remove(pipelinePath().c_str());
    }
    g_spirvFileBytes = 0;
    g_pipelineFileBytes = 0;
}

} // namespace flowframes

int ff_wrap_compile(int index, const ncnn::Option& opt, std::vector<uint32_t>& spirv) {
    using namespace flowframes;
    uint64_t key = 0;
    bool cacheable = false;
    {
        std::lock_guard<std::mutex> lock(g_lock);
        if (!g_dir.empty()) {
            loadSpirvLocked();
            key = keyOf(index, opt);
            cacheable = true;
            auto it = g_spirv.find(key);
            if (it != g_spirv.end()) {
                spirv = it->second;
                g_spirvHits++;
                return 0;
            }
        }
    }
    const long long t0 = nowNs();
    int ret = ff_real_compile(index, opt, spirv);
    g_spirvNs += nowNs() - t0;
    g_spirvCompiled++;
    if (ret == 0 && cacheable && !spirv.empty()) {
        std::lock_guard<std::mutex> lock(g_lock);
        g_spirv[key] = spirv;
        g_spirvDirty = true;
    }
    return ret;
}
