#!/usr/bin/env python3
"""Diagnostics for the bundled RIFE ONNX models.

Reports graph inputs/outputs, op-type histogram and measures inference latency with
dynamic dimensions versus statically resolved dimensions (free-dimension override),
checking that both produce the same image.

Usage: diag_model.py MODEL... [--size WxH]
"""
import sys
import time
from collections import Counter

import numpy as np
import onnx
import onnxruntime as ort


def describe(path):
    m = onnx.load(path)
    g = m.graph
    print(f"\n=== {path} ===")
    print(f"IR {m.ir_version} · opset {[ (o.domain or 'ai.onnx', o.version) for o in m.opset_import ]}")
    print(f"nodes={len(g.node)} · initializers={len(g.initializer)}")
    for i in g.input:
        dims = [(d.dim_param or d.dim_value) for d in i.type.tensor_type.shape.dim]
        print(f"  input  {i.name}{dims}")
    for o in g.output:
        dims = [(d.dim_param or d.dim_value) for d in o.type.tensor_type.shape.dim]
        print(f"  output {o.name}{dims}")
    ops = Counter(n.op_type for n in g.node)
    print("  ops:", ", ".join(f"{k}×{v}" for k, v in ops.most_common()))
    dyn = sorted({d.dim_param for i in g.input for d in i.type.tensor_type.shape.dim if d.dim_param})
    return [i.name for i in g.input], dyn


def make_inputs(inputs, dyn_names, w, h, seed=0):
    rng = np.random.default_rng(seed)
    feeds = {}
    for name in inputs:
        if name == "timestep":
            feeds[name] = np.array([0.5], dtype=np.float32)
        else:
            # two frames arrive as separate inputs; different seeds keep them distinct
            feeds[name] = rng.random((1, 3, h, w), dtype=np.float32)
    return feeds


def run(path, inputs, dyn_names, w, h, static, repeats=3):
    so = ort.SessionOptions()
    so.intra_op_num_threads = 4
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    if static:
        for d in dyn_names:
            so.add_free_dimension_override_by_name(d, w if d.lower() in ("w", "width") else h)
    sess = ort.InferenceSession(path, so, providers=["CPUExecutionProvider"])
    feeds = make_inputs(inputs, dyn_names, w, h)
    out = sess.run(None, feeds)[0]
    for _ in range(2):
        sess.run(None, feeds)
    t0 = time.perf_counter()
    for _ in range(repeats):
        sess.run(None, feeds)
    ms = (time.perf_counter() - t0) * 1000 / repeats
    nodes = [n for n in sess.get_profiling_results()] if False else None
    return out, ms, sess


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    size = next((a.split("=", 1)[1] for a in sys.argv[1:] if a.startswith("--size=")), "576x576")
    w, h = (int(v) for v in size.lower().split("x"))
    for path in args:
        inputs, dyn_names = describe(path)
        print(f"  dynamic dims: {dyn_names}")
        ref, ms_dyn, _ = run(path, inputs, dyn_names, w, h, static=False)
        sta, ms_sta, _ = run(path, inputs, dyn_names, w, h, static=True)
        diff = float(np.max(np.abs(ref - sta)))
        print(f"  {w}x{h} dynamic: {ms_dyn:8.1f} ms | static: {ms_sta:8.1f} ms | max|diff|={diff:.2e}")


if __name__ == "__main__":
    main()
