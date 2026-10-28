// Per-frame pixel conversions shared by the RIFE engine and by the host benchmark
// (android/tools/bench_convert.cpp).
//
// On the GPU path these three passes are the only CPU work left per generated frame: two ARGB frames
// in at 8 bits per channel, one ARGB frame out. They run per row so the engine can spread them over
// the same threads the rest of the CPU work uses, and so the benchmark can time exactly the code the
// phone runs instead of a copy of it.
//
// No namespace of its own: rife_core.cpp includes this header inside `namespace flowframes`, while
// the benchmark includes it at global scope. Both see the functions unqualified, which is all the
// two call sites need.
#ifndef FLOWFRAMES_FRAME_CONVERT_H
#define FLOWFRAMES_FRAME_CONVERT_H

#include <cstdint>
#include <cstring>

/// 0xAARRGGBB pixels -> three planar float rows in [0,1] (alpha is dropped, as the network ignores it).
inline void argbRowToPlanar(const uint32_t* src, float* r, float* g, float* b, int width) {
    const float inv = 1.f / 255.f;
    for (int x = 0; x < width; x++) {
        const uint32_t c = src[x];
        r[x] = (float)((c >> 16) & 0xFF) * inv;
        g[x] = (float)((c >> 8) & 0xFF) * inv;
        b[x] = (float)(c & 0xFF) * inv;
    }
}

/// Three planar float rows -> 0xFFRRGGBB, rounded half up and clamped, exactly like
/// FrameMath.planarToArgb on the ONNX Runtime side.
inline void planarRowToArgb(const float* r, const float* g, const float* b, uint32_t* dst, int width) {
    for (int x = 0; x < width; x++) {
        int rr = (int)(r[x] * 255.f + 0.5f);
        int gg = (int)(g[x] * 255.f + 0.5f);
        int bb = (int)(b[x] * 255.f + 0.5f);
        rr = rr < 0 ? 0 : (rr > 255 ? 255 : rr);
        gg = gg < 0 ? 0 : (gg > 255 ? 255 : gg);
        bb = bb < 0 ? 0 : (bb > 255 ? 255 : bb);
        dst[x] = 0xFF000000u | ((uint32_t)rr << 16) | ((uint32_t)gg << 8) | (uint32_t)bb;
    }
}

/// Planar float row -> planar float row clamped to [0,1] (the float pipeline's output pass).
inline void planarRowClamp(const float* src, float* dst, int width) {
    for (int x = 0; x < width; x++) {
        const float v = src[x];
        dst[x] = v < 0.f ? 0.f : (v > 1.f ? 1.f : v);
    }
}

/// Rows of a frame small enough that waking the threads would cost more than the work itself.
inline bool rowsAreWorthThreading(long long pixels) {
    return pixels >= 120000;  // about 400x300
}

#endif  // FLOWFRAMES_FRAME_CONVERT_H
