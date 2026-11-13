#!/usr/bin/env python3
"""Rewrite the `GridSample` nodes of a RIFE ONNX graph into ops the Hexagon HTP can run.

`GridSample` (bilinear, `padding_mode=border`, `align_corners=1`) is the only operator in RIFE 4.9
that QNN has no kernel for, and there are 14 of them: every one falls back to the CPU, and with it
the warping half of the network. This tool replaces each one with the same arithmetic expressed as
flat integer indices, four `GatherElements` and bilinear weights, which the HTP can execute.

    python3 tools/qnn_rewrite.py in.onnx out.onnx --verify

The transform is shape-preserving and numerically equivalent in fp32; `--verify` runs both graphs
through onnxruntime and prints the largest absolute difference.
"""

from __future__ import annotations

import argparse
import os
import sys
import tempfile

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper


def _c(name: str, value) -> onnx.NodeProto:
    return helper.make_node(
        "Constant", [], [name], value=numpy_helper.from_array(np.asarray(value), name + "_v")
    )


def _grid_sample(prefix: str, x: str, grid: str, out: str) -> list[onnx.NodeProto]:
    """Node list replacing one GridSample(x, grid) -> out.

    `align_corners=1` means the grid maps -1..1 onto pixel 0..size-1, `padding_mode=border` clamps
    to the edge. The four corners are gathered from the channel-flattened feature map with
    `GatherElements`, and mixed with the usual bilinear weights.
    """
    nodes: list[onnx.NodeProto] = []

    def n(suffix: str) -> str:
        return f"{prefix}_{suffix}"

    def node(op: str, inputs: list[str], outputs: list[str], **attrs):
        nodes.append(helper.make_node(op, inputs, outputs, name=outputs[0], **attrs))

    # Shapes, straight from the tensors: with static session dims the optimizer folds them.
    node("Shape", [x], [n("xs")])                      # [N,C,H,W]
    node("Slice", [n("xs"), n("s0"), n("s2")], [n("nc")])   # [N,C]
    node("Gather", [n("xs"), n("i2")], [n("H")], axis=0)
    node("Gather", [n("xs"), n("i3")], [n("W")], axis=0)
    node("Cast", [n("H")], [n("Hf")], to=TensorProto.FLOAT)
    node("Cast", [n("W")], [n("Wf")], to=TensorProto.FLOAT)
    node("Cast", [n("W")], [n("Wi")], to=TensorProto.INT32)

    # grid [N,Ho,Wo,2] -> x and y in pixels.
    # opset 17 Split: two equal halves of the last axis, no attribute needed.
    node("Split", [grid], [n("gx"), n("gy")], axis=3)
    node("Squeeze", [n("gx"), n("ax3")], [n("gx2")])
    node("Squeeze", [n("gy"), n("ax3")], [n("gy2")])
    for tag, size in (("x", "Wf"), ("y", "Hf")):
        node("Add", [n(f"g{tag}2"), n("one")], [n(f"{tag}_p1")])
        node("Mul", [n(f"{tag}_p1"), n("half")], [n(f"{tag}_half")])
        node("Sub", [n(size), n("one")], [n(f"{tag}_max")])
        node("Mul", [n(f"{tag}_half"), n(f"{tag}_max")], [n(f"{tag}_f")])
        node("Clip", [n(f"{tag}_f"), n("zero"), n(f"{tag}_max")], [n(f"{tag}_c")])   # border
        node("Floor", [n(f"{tag}_c")], [n(f"{tag}_i")])
        node("Sub", [n(f"{tag}_c"), n(f"{tag}_i")], [n(f"{tag}_w")])
        node("Add", [n(f"{tag}_i"), n("one")], [n(f"{tag}_ip1")])
        node("Min", [n(f"{tag}_ip1"), n(f"{tag}_max")], [n(f"{tag}_i1")])
        node("Cast", [n(f"{tag}_i")], [n(f"{tag}_lo")], to=TensorProto.INT32)
        node("Cast", [n(f"{tag}_i1")], [n(f"{tag}_hi")], to=TensorProto.INT32)
        # weight as [N,1,Ho,Wo] so it broadcasts over the channel axis.
        node("Unsqueeze", [n(f"{tag}_w"), n("ax1")], [n(f"{tag}_wb")])

    # flat indices i = y*W + x, shaped [N,1,Ho*Wo].
    node("Reshape", [n("gx2"), n("shape_hw")], [n("idx_sh")])       # [Ho*Wo] and its shape
    node("Shape", [n("idx_sh")], [n("ish")])                        # [Ho*Wo]
    node("Concat", [n("nc"), n("ish")], [n("target")], axis=0)      # [N,C,Ho*Wo]
    node("Reshape", [n("gx2"), n("shape_flat")], [n("gx_flat")])    # [1,1,Ho*Wo]
    node("Shape", [n("gx_flat")], [n("fh")])
    node("Slice", [n("fh"), n("s1"), n("s2")], [n("hw_of")])        # [Ho*Wo]
    node("Reshape", [n("hw_of"), n("shape_hw")], [n("hw_flat")])
    for corner, ytag, xtag in (("00", "lo", "lo"), ("01", "lo", "hi"),
                               ("10", "hi", "lo"), ("11", "hi", "hi")):
        node("Mul", [n(f"y_{ytag}"), n("Wi")], [n(f"row_{corner}")])
        node("Add", [n(f"row_{corner}"), n(f"x_{xtag}")], [n(f"i_{corner}")])
        node("Reshape", [n(f"i_{corner}"), n("shape_flat")], [n(f"if_{corner}")])
        node("Expand", [n(f"if_{corner}"), n("target")], [n(f"ie_{corner}")])
        node("GatherElements", [n("xflat"), n(f"ie_{corner}")], [n(f"s_{corner}")], axis=2)

    node("Reshape", [x, n("shape_flat3")], [n("xflat")])            # [N,C,H*W]
    node("Shape", [n("gx2")], [n("gsh")])                           # [N,Ho,Wo]
    node("Slice", [n("gsh"), n("s1"), n("s3")], [n("ihw")])         # [Ho,Wo]
    node("Concat", [n("nc"), n("ihw")], [n("nchw")], axis=0)        # [N,C,Ho,Wo]
    for corner in ("00", "01", "10", "11"):
        node("Reshape", [n(f"s_{corner}"), n("nchw")], [n(f"v_{corner}")])

    node("Sub", [n("one"), n("x_wb")], [n("wx0")])
    node("Sub", [n("one"), n("y_wb")], [n("wy0")])
    node("Mul", [n("wx0"), n("wy0")], [n("w00")])
    node("Mul", [n("x_wb"), n("wy0")], [n("w01")])
    node("Mul", [n("wx0"), n("y_wb")], [n("w10")])
    node("Mul", [n("x_wb"), n("y_wb")], [n("w11")])
    for corner in ("00", "01", "10", "11"):
        node("Mul", [n(f"v_{corner}"), n(f"w{corner}")], [n(f"t{corner}")])
    node("Add", [n("t00"), n("t01")], [n("sum_a")])
    node("Add", [n("t10"), n("t11")], [n("sum_b")])
    node("Add", [n("sum_a"), n("sum_b")], [n("flat_sum")])
    node("Reshape", [n("flat_sum"), n("nchw")], [out])

    return [
        # Reshape shapes: -1 where the dim is implied, 0 to copy the input dim (opset 14+).
        _c(n("shape_hw"), np.array([-1], dtype=np.int64)),
        _c(n("shape_flat"), np.array([1, 1, -1], dtype=np.int64)),
        _c(n("shape_flat3"), np.array([0, 0, -1], dtype=np.int64)),
        _c(n("i0"), np.array(0, dtype=np.int64)),
        _c(n("i1"), np.array(1, dtype=np.int64)),
        _c(n("i2"), np.array(2, dtype=np.int64)),
        _c(n("i3"), np.array(3, dtype=np.int64)),
        _c(n("zero"), np.array(0.0, dtype=np.float32)),
        _c(n("one"), np.array(1.0, dtype=np.float32)),
        _c(n("half"), np.array(0.5, dtype=np.float32)),
        _c(n("s0"), np.array([0], dtype=np.int64)),
        _c(n("s1"), np.array([1], dtype=np.int64)),
        _c(n("s2"), np.array([2], dtype=np.int64)),
        _c(n("s3"), np.array([3], dtype=np.int64)),
        _c(n("ax1"), np.array([1], dtype=np.int64)),
        _c(n("ax3"), np.array([3], dtype=np.int64)),
    ] + nodes


def rewrite(model: onnx.ModelProto) -> tuple[onnx.ModelProto, int]:
    graph = model.graph
    kept: list[onnx.NodeProto] = []
    replaced = 0
    for item in graph.node:
        if item.op_type != "GridSample":
            kept.append(item)
            continue
        attrs = {a.name: helper.get_attribute_value(a) for a in item.attribute}
        mode = attrs.get("mode", b"bilinear")
        mode = mode.decode() if isinstance(mode, bytes) else mode
        padding = attrs.get("padding_mode", b"zeros")
        padding = padding.decode() if isinstance(padding, bytes) else padding
        if mode != "bilinear" or padding != "border" or attrs.get("align_corners", 0) != 1:
            raise SystemExit(
                f"{item.name}: unsupported GridSample variant ({mode}, {padding}, "
                f"align_corners={attrs.get('align_corners', 0)})"
            )
        prefix = (item.name or f"gs{replaced}").replace("/", "_").strip("_")
        kept += _grid_sample(prefix, item.input[0], item.input[1], item.output[0])
        replaced += 1
    graph.ClearField("node")
    graph.node.extend(kept)
    return model, replaced


def verify(source: onnx.ModelProto, rewritten: onnx.ModelProto, sizes) -> bool:
    import onnxruntime as ort

    ok = True
    for height, width in sizes:
        rng = np.random.default_rng(1234)
        feeds = {
            "img0": rng.random((1, 3, height, width), dtype=np.float32),
            "img1": rng.random((1, 3, height, width), dtype=np.float32),
            "timestep": np.array([0.5], dtype=np.float32),
        }
        outs = []
        for label, model in (("original", source), ("rewritten", rewritten)):
            path = os.path.join(tempfile.mkdtemp(), f"{label}.onnx")
            onnx.save(model, path)
            sess = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
            outs.append(sess.run(None, feeds)[0])
        diff = float(np.max(np.abs(outs[0] - outs[1])))
        mean = float(np.mean(np.abs(outs[0] - outs[1])))
        print(f"  {width}x{height}: max |diff| {diff:.3e} · mean {mean:.3e}")
        if not np.isfinite(diff) or diff > 1e-3:
            ok = False
    return ok


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source")
    parser.add_argument("destination")
    parser.add_argument("--verify", action="store_true", help="compare against onnxruntime")
    args = parser.parse_args()

    model = onnx.load(args.source)
    before = len(model.graph.node)
    model, replaced = rewrite(model)
    onnx.save(model, args.destination)
    print(f"{replaced} GridSample -> GatherElements · {before} -> {len(model.graph.node)} nodes")
    if args.verify:
        print("verification (fp32, onnxruntime CPU):")
        if not verify(onnx.load(args.source), onnx.load(args.destination), [(64, 64), (96, 128)]):
            print("FAILED: the rewritten graph does not match")
            return 1
        print("ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
