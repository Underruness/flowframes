// Persistent GPU shader caches for ncnn.
//
// ncnn compiles its GLSL compute shaders to SPIR-V at run time (glslang, on the CPU) and the
// Vulkan driver then compiles every SPIR-V module into a pipeline. Both happen again at every app
// start: with fp16 math + cooperative matrix, an Adreno 840 spent 98 s there before the first
// frame. Two caches remove that cost after the first run:
//
//  1. SPIR-V: the link step wraps ncnn::compile_spirv_module(int, const Option&, spirv)
//     (-Wl,--wrap) so that a module compiled once is read back from disk afterwards.
//  2. Driver pipelines: ncnn passes no VkPipelineCache to vkCreateComputePipelines; the function
//     pointer it calls through (ncnn::vkCreateComputePipelines, simplevk) is swapped for one that
//     adds a VkPipelineCache loaded from and saved to disk.
//
// Both files carry the ncnn version and the GPU identity (vendor, device, driver, pipeline UUID),
// and anything that does not match is ignored, so a driver update simply rebuilds them.
#ifndef FLOWFRAMES_SHADER_CACHE_H
#define FLOWFRAMES_SHADER_CACHE_H

#include <string>

namespace flowframes {

// Directory for the cache files; empty disables both caches. Call before the first engine.
void setShaderCacheDir(const std::string& dir);

// Installs the vkCreateComputePipelines hook. Called right after ncnn::create_gpu_instance().
void installPipelineCacheHook();

// Writes whatever was compiled since the last call (cheap no-op when nothing is new).
void flushShaderCaches();

// One line for the job log: compile counts and time spent, cache hits, file sizes.
std::string shaderCacheStats();

// Deletes both cache files and the in-memory SPIR-V entries.
void clearShaderCaches();

} // namespace flowframes

#endif
