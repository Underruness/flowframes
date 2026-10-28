// Times the three per-frame conversions of the RIFE pipeline on this machine, serial against
// threaded, using the very functions the engine calls (app/src/main/cpp/frame_convert.h).
//
// The phone's GPU cannot be measured from here; what this measures is the CPU work sitting on the
// critical path of every generated frame on every device: two ARGB input conversions, one ARGB
// output conversion.
//
//   g++ -O3 -fopenmp -std=c++17 android/tools/bench_convert.cpp -o /tmp/bench_convert && /tmp/bench_convert [threads]
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <vector>

#include "../app/src/main/cpp/frame_convert.h"

using namespace flowframes;
using Clock = std::chrono::steady_clock;

static double millisSince(Clock::time_point start) {
    return std::chrono::duration<double, std::milli>(Clock::now() - start).count();
}

struct Frame {
    int width = 0;
    int height = 0;
    std::vector<uint32_t> argb;
    std::vector<float> r, g, b, outR, outG, outB;
};

static Frame allocate(int width, int height) {
    Frame f;
    f.width = width;
    f.height = height;
    f.argb.assign((size_t)width * height, 0xFF204060u);
    f.r.assign((size_t)width * height, 0.f);
    f.g.assign((size_t)width * height, 0.f);
    f.b.assign((size_t)width * height, 0.f);
    f.outR.assign((size_t)width * height, 0.3f);
    f.outG.assign((size_t)width * height, 0.6f);
    f.outB.assign((size_t)width * height, 0.9f);
    return f;
}

/// Two frames in: ARGB -> planar float, per row.
static double toPlanar(Frame& f, int reps, int threads) {
    const auto start = Clock::now();
    for (int i = 0; i < reps; i++) {
#pragma omp parallel for schedule(static) num_threads(threads)
        for (int y = 0; y < f.height; y++) {
            const size_t o = (size_t)y * f.width;
            argbRowToPlanar(f.argb.data() + o, f.r.data() + o, f.g.data() + o, f.b.data() + o, f.width);
        }
    }
    return millisSince(start) / reps;
}

/// One frame out: planar float -> ARGB, per row.
static double toArgb(Frame& f, int reps, int threads) {
    const auto start = Clock::now();
    for (int i = 0; i < reps; i++) {
#pragma omp parallel for schedule(static) num_threads(threads)
        for (int y = 0; y < f.height; y++) {
            const size_t o = (size_t)y * f.width;
            planarRowToArgb(f.outR.data() + o, f.outG.data() + o, f.outB.data() + o, f.argb.data() + o, f.width);
        }
    }
    return millisSince(start) / reps;
}

int main(int argc, char** argv) {
    const int threads = argc > 1 ? atoi(argv[1]) : 6;
    printf("Per-frame pixel conversions, %d thread(s) for the threaded numbers\n\n", threads);
    printf("%-11s %9s %9s %9s %9s %10s %10s %8s\n",
           "size", "in(1) ms", "in(N) ms", "out(1) ms", "out(N) ms", "sim. serial", "sim. thread", "gain");
    const int sizes[][2] = {{1280, 720}, {1920, 1080}, {2560, 1440}, {3840, 2160}};
    for (const auto& size : sizes) {
        Frame f = allocate(size[0], size[1]);
        const int reps = (size[0] * size[1]) > 4000000 ? 20 : 60;
        toPlanar(f, 1, 1);      // warm-up: page faults and thread start-up must not land in the numbers
        toArgb(f, 1, threads);

        const double inSerial = toPlanar(f, reps, 1);
        const double inThreaded = toPlanar(f, reps, threads);
        const double outSerial = toArgb(f, reps, 1);
        const double outThreaded = toArgb(f, reps, threads);

        // What one generated frame costs: two input conversions bring the pair in, one takes the
        // result back out.
        const double serial = inSerial * 2 + outSerial;
        const double threaded = inThreaded * 2 + outThreaded;
        char label[16];
        snprintf(label, sizeof(label), "%dx%d", size[0], size[1]);
        printf("%-11s %9.3f %9.3f %9.3f %9.3f %10.3f %10.3f %7.1f%%\n", label, inSerial, inThreaded,
               outSerial, outThreaded, serial, threaded, 100.0 * (serial - threaded) / serial);
    }
    return 0;
}
