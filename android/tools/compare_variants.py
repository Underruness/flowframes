#!/usr/bin/env python3
"""Compare ONNX Runtime versions and graph variants for the RIFE model."""
import sys
import time

import numpy as np
import onnx
import onnxruntime as ort


def timeit(path, w, h, static, repeats=3):
    so = ort.SessionOptions()
    so.intra_op_num_threads = 4
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
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
    out = sess.run(None, feeds)[0]
    for _ in range(2):
        sess.run(None, feeds)
    t0 = time.perf_counter()
    for _ in range(repeats):
        sess.run(None, feeds)
    return out, (time.perf_counter() - t0) * 1000 / repeats


def main():
    src = sys.argv[1]
    w = h = 576
    print(f"ort {ort.__version__}")
    dyn, ms_dyn = timeit(src, w, h, False)
    sta, ms_sta = timeit(src, w, h, True)
    print(f"  dynamic {ms_dyn:7.1f} ms | static {ms_sta:7.1f} ms | diff {np.abs(dyn - sta).max():.2e}")

    # Statically folded graph (onnxsim with fixed input shapes)
    try:
        from onnxsim import simplify
        m = onnx.load(src)
        m2, ok = simplify(m, overwrite_input_shapes={
            "img0": [1, 3, h, w], "img1": [1, 3, h, w], "timestep": [1]})
        if ok:
            folded = "/tmp/folded.onnx"
            onnx.save(m2, folded)
            nodes = len(m.graph.node), len(m2.graph.node)
            out2, ms_folded = timeit(folded, w, h, True)
            print(f"  folded  {ms_folded:7.1f} ms | nodes {nodes[0]} -> {nodes[1]} | diff {np.abs(dyn - out2).max():.2e}")
    except Exception as e:  # noqa: BLE001
        print("  onnxsim failed:", e)


if __name__ == "__main__":
    main()
