#!/usr/bin/env python3
"""Quantize the RIFE model to INT8 (QDQ) and report the quality loss against FP32.

Usage: python tools/quantize_rife.py <model.onnx> <out_int8.onnx>

The script refuses to keep a model whose PSNR against FP32 falls below the gate, so a bad
quantization cannot silently ship: run it and read the reported numbers.
"""
import sys
import numpy as np
import onnxruntime as ort
from onnxruntime.quantization import (
    CalibrationDataReader, QuantFormat, QuantType, quantize_static,
)
from onnxruntime.quantization.shape_inference import quant_pre_process

PSNR_GATE_DB = 33.0


def make_frame(rng, h, w, kind):
    """Synthetic but structured frames: quantization is sensitive to realistic dynamic range."""
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    if kind == 0:  # smooth gradient + vignette
        r = xx / max(w - 1, 1)
        g = yy / max(h - 1, 1)
        b = 0.5 + 0.5 * np.sin(6.28 * (xx + yy) / max(w, 1))
    elif kind == 1:  # high frequency noise (worst case for activations)
        r = rng.random((h, w)).astype(np.float32)
        g = rng.random((h, w)).astype(np.float32)
        b = rng.random((h, w)).astype(np.float32)
    elif kind == 2:  # sharp edges / text-like structure
        r = ((xx // 8 + yy // 8) % 2).astype(np.float32)
        g = np.clip(np.sin(xx / 9.0) * 0.5 + 0.5, 0, 1).astype(np.float32)
        b = np.clip(np.cos(yy / 11.0) * 0.5 + 0.5, 0, 1).astype(np.float32)
    else:  # flat regions (dark / bright)
        base = 0.05 if kind == 3 else 0.95
        r = np.full((h, w), base, np.float32)
        g = np.full((h, w), base * 0.8, np.float32)
        b = np.full((h, w), base * 0.6, np.float32)
    return np.stack([r, g, b], axis=-1)


def to_input(frame):
    """HWC float [0,1] -> 1x3xHxW."""
    return np.transpose(frame, (2, 0, 1))[None, ...].astype(np.float32)


class RifeCalibration(CalibrationDataReader):
    def __init__(self, names, size=320, samples=24):
        self._names = names
        self._data = []
        rng = np.random.default_rng(3)
        h, w = size, size
        for i in range(samples):
            kind = i % 4
            f0 = make_frame(rng, h, w, kind)
            f1 = make_frame(rng, h, w, (kind + 1) % 4)
            # small global shift between the pair, like real consecutive frames
            dx = int(rng.integers(-3, 4))
            f1 = np.roll(f1, dx, axis=1)
            self._data.append({
                names[0]: to_input(f0),
                names[1]: to_input(f1),
                names[2]: np.array([0.5], np.float32),
            })
        self._index = 0

    def get_next(self):
        if self._index >= len(self._data):
            return None
        item = self._data[self._index]
        self._index += 1
        return item

    def rewind(self):
        self._index = 0


def run(session, names, f0, f1, t=0.5):
    return session.run(None, {names[0]: to_input(f0), names[1]: to_input(f1),
                              names[2]: np.array([t], np.float32)})[0]


def psnr(a, b):
    mse = float(np.mean((a.astype(np.float64) - b.astype(np.float64)) ** 2))
    if mse <= 0:
        return 99.0
    return 10.0 * np.log10(1.0 / mse)


def main():
    src = sys.argv[1]
    out = sys.argv[2]
    sess = ort.InferenceSession(src, providers=["CPUExecutionProvider"])
    names = [i.name for i in sess.get_inputs()]

    pre = out + ".pre.onnx"
    # The model has fully dynamic H/W, so symbolic shape inference cannot complete on it.
    quant_pre_process(src, pre, skip_symbolic_shape=True)
    quantize_static(
        pre, out,
        calibration_data_reader=RifeCalibration(names),
        quant_format=QuantFormat.QDQ,
        per_channel=True,
        activation_type=QuantType.QUInt8,
        weight_type=QuantType.QInt8,
        op_types_to_quantize=["Conv", "MatMul", "Gemm"],
    )

    qsess = ort.InferenceSession(out, providers=["CPUExecutionProvider"])
    rng = np.random.default_rng(11)
    print(f"{'size':>10} {'t':>5} {'PSNR int8 vs fp32 (dB)':>24}")
    worst = 99.0
    for (h, w) in [(160, 256), (360, 640), (720, 1280)]:
        for kind in (0, 1, 2, 3):
            f0 = make_frame(rng, h, w, kind)
            f1 = make_frame(rng, h, w, (kind + 1) % 4)
            for t in (0.5, 0.25):
                ref = run(sess, names, f0, f1, t)
                got = run(qsess, names, f0, f1, t)
                if ref.shape != got.shape:
                    raise SystemExit(f"shape mismatch at {w}x{h}: {ref.shape} vs {got.shape}")
                p = psnr(np.clip(ref, 0, 1), np.clip(got, 0, 1))
                worst = min(worst, p)
                print(f"{w}x{h:>5} {t:5.2f} {p:24.2f}  (pattern {kind})")

    print(f"\nworst PSNR: {worst:.2f} dB (gate {PSNR_GATE_DB:.1f} dB)")
    if worst < PSNR_GATE_DB:
        print("RESULT: REJECT - quantization too lossy, do not ship")
        sys.exit(2)
    print("RESULT: ACCEPT")


if __name__ == "__main__":
    main()
