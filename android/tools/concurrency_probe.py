"""Does running several RIFE inferences at once beat one inference on more threads?

The on-device benchmark showed almost no gain from 4 to 8 intra-op threads (857 vs 885 ms on a
Snapdragon 8 Elite), which is what a latency- or bandwidth-bound workload looks like: the extra
cores idle while every thread waits on memory. If that is the case, two independent inferences on
the same cores finish more work per second than one inference with twice the threads.

Run:  ~/venv/bin/python android/tools/concurrency_probe.py <model.onnx> [size] [inferences]
"""
import sys
import time
import threading

import numpy as np
import onnxruntime as ort

model = sys.argv[1]
size = int(sys.argv[2]) if len(sys.argv) > 2 else 576
total_inferences = int(sys.argv[3]) if len(sys.argv) > 3 else 6


def make_session(threads):
    so = ort.SessionOptions()
    so.intra_op_num_threads = threads
    so.inter_op_num_threads = 1
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    so.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    return ort.InferenceSession(model, so, providers=["CPUExecutionProvider"])


a = np.random.rand(1, 3, size, size).astype(np.float32)
b = np.random.rand(1, 3, size, size).astype(np.float32)
t = np.array([0.5], dtype=np.float32)


def run_stream(session, count):
    for _ in range(count):
        session.run(None, {"img0": a, "img1": b, "timestep": t})


def bench(streams, threads, label):
    sessions = [make_session(threads) for _ in range(streams)]
    # warm-up: first run pays lazy setup in every session
    for s in sessions:
        s.run(None, {"img0": a, "img1": b, "timestep": t})
    per_stream = max(1, total_inferences // streams)
    start = time.perf_counter()
    workers = [threading.Thread(target=run_stream, args=(s, per_stream)) for s in sessions]
    for w in workers:
        w.start()
    for w in workers:
        w.join()
    elapsed = time.perf_counter() - start
    done = per_stream * streams
    ms = elapsed / done * 1000
    print(f"{label:34s} {done:2d} inferences  {elapsed:6.2f} s  {ms:7.1f} ms/inference  {done/elapsed:5.2f} inf/s")
    return ms


print(f"model {model} · {size}x{size} · {total_inferences} inferences per configuration")
print(f"onnxruntime {ort.__version__} · {ort.get_device()}\n")

single = {}
for threads in (4, 6, 8):
    single[threads] = bench(1, threads, f"1 stream x {threads} threads")

print()
for streams, threads in ((2, 3), (2, 4), (3, 2), (4, 2)):
    bench(streams, threads, f"{streams} streams x {threads} threads")

best_single = min(single.values())
print(f"\nbest single stream: {best_single:.1f} ms/inference")
print("A concurrent configuration wins if its ms/inference is lower than that.")
