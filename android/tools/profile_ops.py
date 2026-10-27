#!/usr/bin/env python3
"""Per-operator profile of a RIFE ONNX model (ORT JSON profiling, aggregated by op type)."""
import json
import sys
import time
from collections import defaultdict

import numpy as np
import onnxruntime as ort


def profile(path, w, h, threads=4, static=False):
    so = ort.SessionOptions()
    so.intra_op_num_threads = threads
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    so.enable_profiling = True
    if static:
        so.add_free_dimension_override_by_name("height", h)
        so.add_free_dimension_override_by_name("width", w)
    sess = ort.InferenceSession(path, so, providers=["CPUExecutionProvider"])
    rng = np.random.default_rng(0)
    feeds = {
        "img0": rng.random((1, 3, h, w), dtype=np.float32),
        "img1": rng.random((1, 3, h, w), dtype=np.float32),
        "timestep": np.array([0.5], dtype=np.float32),
    }
    for _ in range(3):
        sess.run(None, feeds)
    t0 = time.perf_counter()
    out = sess.run(None, feeds)[0]
    total = (time.perf_counter() - t0) * 1000
    prof_file = sess.end_profiling()

    per_op = defaultdict(float)
    counts = defaultdict(int)
    for ev in json.load(open(prof_file)):
        if ev.get("cat") != "Node" or "dur" not in ev:
            continue
        op = ev["args"].get("op_name", "?")
        per_op[op] += ev["dur"] / 1000.0
        counts[op] += 1
    print(f"{path} @ {w}x{h} static={static}: {total:.1f} ms/run, {len(per_op)} op types")
    for op, us in sorted(per_op.items(), key=lambda kv: -kv[1])[:14]:
        print(f"  {op:<16} {us:8.1f} ms  ({counts[op]:3d} nodes, {us / counts[op]:6.2f} ms each)")
    return out


if __name__ == "__main__":
    path = sys.argv[1]
    w = int(sys.argv[2]) if len(sys.argv) > 2 else 576
    h = int(sys.argv[3]) if len(sys.argv) > 3 else 576
    a = profile(path, w, h, static=False)
    b = profile(path, w, h, static=True)
    print("max|diff| static/dynamic:", float(np.max(np.abs(a - b))))
