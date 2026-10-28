package com.flowframes.android.core

import java.nio.ByteBuffer

/**
 * YUV 4:2:0 <-> ARGB conversion with configurable colour matrix and range.
 *
 * Works directly on [ByteBuffer]s (the ones exposed by `android.media.Image.Plane`) so the same
 * code runs on device and in JVM unit tests. All reads/writes are absolute: buffer positions are
 * never modified.
 */
object YuvConverter {

    /**
     * @param yMul/yOff  luma expansion (Q8): `Y' = y * yMul + yOff`
     * @param cr/cgu/cgv/cb  chroma coefficients (Q8) used when decoding
     * @param eY/eU/eV  three RGB coefficient triplets (Q8) used when encoding
     * @param encYOff/encUvOff offsets added after the luma/chroma shifts
     */
    data class Spec(
        val label: String,
        val yMul: Int,
        val yOff: Int,
        val cr: Int,
        val cgu: Int,
        val cgv: Int,
        val cb: Int,
        val eY: IntArray,
        val encYOff: Int,
        val eU: IntArray,
        val eV: IntArray,
        val encUvOff: Int
    ) {
        /** `y * yMul + yOff` for all 256 luma values: a table read replaces the multiply. */
        val lumaLut: IntArray = IntArray(256) { it * yMul + yOff }
    }

    val BT601_LIMITED = Spec(
        label = "bt601-limited", yMul = 298, yOff = -16 * 298,
        cr = 409, cgu = 100, cgv = 208, cb = 516,
        eY = intArrayOf(66, 129, 25), encYOff = 16,
        eU = intArrayOf(-38, -74, 112), eV = intArrayOf(112, -94, -18), encUvOff = 128
    )

    val BT709_LIMITED = Spec(
        label = "bt709-limited", yMul = 298, yOff = -16 * 298,
        cr = 459, cgu = 55, cgv = 136, cb = 541,
        eY = intArrayOf(47, 157, 16), encYOff = 16,
        eU = intArrayOf(-26, -86, 112), eV = intArrayOf(112, -102, -10), encUvOff = 128
    )

    val BT601_FULL = Spec(
        label = "bt601-full", yMul = 256, yOff = 0,
        cr = 359, cgu = 88, cgv = 183, cb = 454,
        eY = intArrayOf(77, 150, 29), encYOff = 0,
        eU = intArrayOf(-43, -85, 128), eV = intArrayOf(128, -107, -21), encUvOff = 128
    )

    val BT709_FULL = Spec(
        label = "bt709-full", yMul = 256, yOff = 0,
        cr = 403, cgu = 48, cgv = 120, cb = 475,
        eY = intArrayOf(54, 183, 19), encYOff = 0,
        eU = intArrayOf(-29, -99, 128), eV = intArrayOf(128, -116, -12), encUvOff = 128
    )

    /**
     * Converts a YUV 4:2:0 image to ARGB pixels.
     *
     * @param uvPixelStride 1 for planar (I420), 2 for semi-planar (NV12/NV21)
     */
    fun yuv420ToArgb(
        y: ByteBuffer, u: ByteBuffer, v: ByteBuffer,
        yRowStride: Int, uvRowStride: Int, uvPixelStride: Int,
        width: Int, height: Int,
        spec: Spec, out: IntArray,
        scratch: PlaneScratch? = null
    ) {
        require(out.size >= width * height) { "output too small" }
        // The planes are copied in bulk once and then read as plain arrays: a ByteBuffer.get(index)
        // per channel per pixel cost more than the colour maths itself.
        val s = scratch ?: PlaneScratch()
        val luma = s.luma(y, width, height, yRowStride)
        val chromaRows = (height + 1) / 2
        val ua = s.chroma(u, uvRowStride * chromaRows)
        val va = s.chromaV(v, uvRowStride * chromaRows)
        val lut = spec.lumaLut
        val cr = spec.cr
        val cgu = spec.cgu
        val cgv = spec.cgv
        val cb = spec.cb
        val pack = { rSum: Int, gSum: Int, bSum: Int ->
            (0xFF shl 24) or (clamp((rSum + 128) shr 8) shl 16) or
                (clamp((gSum + 128) shr 8) shl 8) or clamp((bSum + 128) shr 8)
        }
        for (row in 0 until height) {
            val base = row * width
            val uvBase = (row shr 1) * uvRowStride
            var col = 0
            while (col < width) {
                // The two pixels of one chroma cell share U and V, so the three colour products are
                // computed once per cell instead of once per pixel.
                val cu = uvBase + (col shr 1) * uvPixelStride
                val uu = (ua[cu].toInt() and 0xFF) - 128
                val vv = (va[cu].toInt() and 0xFF) - 128
                val rAdd = cr * vv
                val gAdd = -cgu * uu - cgv * vv
                val bAdd = cb * uu
                var i = base + col
                val y0 = lut[luma[i].toInt() and 0xFF]
                out[i] = pack(y0 + rAdd, y0 + gAdd, y0 + bAdd)
                if (col + 1 < width) {
                    i++
                    val y1 = lut[luma[i].toInt() and 0xFF]
                    out[i] = pack(y1 + rAdd, y1 + gAdd, y1 + bAdd)
                }
                col += 2
            }
        }
    }

    /** Converts ARGB pixels into YUV 4:2:0 planes (chroma is averaged over each 2x2 block). */
    fun argbToYuv420(
        argb: IntArray, width: Int, height: Int,
        y: ByteBuffer, u: ByteBuffer, v: ByteBuffer,
        yRowStride: Int, uvRowStride: Int, uvPixelStride: Int,
        spec: Spec
    ) {
        require(argb.size >= width * height) { "input too small" }
        val eY = spec.eY
        val eU = spec.eU
        val eV = spec.eV
        for (row in 0 until height) {
            val yBase = row * yRowStride
            val outBase = row * width
            for (col in 0 until width) {
                val c = argb[outBase + col]
                val r = (c ushr 16) and 0xFF
                val g = (c ushr 8) and 0xFF
                val b = c and 0xFF
                val yv = ((eY[0] * r + eY[1] * g + eY[2] * b + 128) shr 8) + spec.encYOff
                y.put(yBase + col, clamp(yv).toByte())
            }
        }
        for (row in 0 until height step 2) {
            val uvBase = (row shr 1) * uvRowStride
            for (col in 0 until width step 2) {
                var rSum = 0
                var gSum = 0
                var bSum = 0
                var n = 0
                for (dy in 0..1) {
                    val yy = row + dy
                    if (yy >= height) continue
                    val base = yy * width
                    for (dx in 0..1) {
                        val xx = col + dx
                        if (xx >= width) continue
                        val c = argb[base + xx]
                        rSum += (c ushr 16) and 0xFF
                        gSum += (c ushr 8) and 0xFF
                        bSum += c and 0xFF
                        n++
                    }
                }
                if (n == 0) continue
                val r = rSum / n
                val g = gSum / n
                val b = bSum / n
                val uu = ((eU[0] * r + eU[1] * g + eU[2] * b + 128) shr 8) + spec.encUvOff
                val vv = ((eV[0] * r + eV[1] * g + eV[2] * b + 128) shr 8) + spec.encUvOff
                val idx = uvBase + (col shr 1) * uvPixelStride
                u.put(idx, clamp(uu).toByte())
                v.put(idx, clamp(vv).toByte())
            }
        }
    }

    /**
     * Decodes a YUV 4:2:0 image straight into an ARGB buffer of a different size and orientation.
     *
     * This replaces the old three-pass path (convert at full size, rotate, then bilinear resize) with
     * a single pass that only touches the destination pixels: luma is point-sampled bilinearly from
     * the source plane, chroma is taken from the nearest subsampled cell.
     *
     * @param rotation clockwise rotation in degrees (0, 90, 180, 270) applied to the source image
     */
    fun yuv420ToArgbScaled(
        y: ByteBuffer, u: ByteBuffer, v: ByteBuffer,
        yRowStride: Int, uvRowStride: Int, uvPixelStride: Int,
        srcW: Int, srcH: Int,
        dstW: Int, dstH: Int,
        rotation: Int,
        spec: Spec, out: IntArray,
        scratch: PlaneScratch? = null
    ) {
        require(out.size >= dstW * dstH) { "output too small" }
        val deg = ((rotation % 360) + 360) % 360
        // Identity case: keep the exact same code path as the unscaled converter.
        if (deg == 0 && dstW == srcW && dstH == srcH) {
            yuv420ToArgb(y, u, v, yRowStride, uvRowStride, uvPixelStride, srcW, srcH, spec, out, scratch)
            return
        }
        // ByteBuffer.get(index) costs a JNI-free but bounds-checked access each time (~6 ns).
        // Copying the planes in bulk and then indexing plain arrays is several times faster.
        val s = scratch ?: PlaneScratch()
        // No rotation: the source column of an output column does not depend on the row (and the
        // source row does not depend on the column). Building that mapping once per frame size turns
        // the per-pixel work into four reads and one blend, for every frame of the job.
        if (deg == 0) {
            scaleToArgb(
                y, u, v, yRowStride, uvRowStride, uvPixelStride,
                srcW, srcH, dstW, dstH, spec, out, s
            )
            return
        }
        val yPlane = s.luma(y, srcW, srcH, yRowStride)
        val chromaRows = (srcH + 1) / 2
        val uPlane = s.chroma(u, uvRowStride * chromaRows)
        val vPlane = s.chromaV(v, uvRowStride * chromaRows)
        val swapped = deg == 90 || deg == 270
        val rotW = if (swapped) srcH else srcW
        val rotH = if (swapped) srcW else srcH
        val xScale = rotW.toFloat() / dstW
        val yScale = rotH.toFloat() / dstH
        val cw = (srcW + 1) / 2
        val ch = (srcH + 1) / 2
        val maxX = srcW - 1
        val maxY = srcH - 1
        val maxXF = maxX.toFloat()
        val maxYF = maxY.toFloat()

        // Rotated (rx, ry) -> source (sx, sy) is a linear map per rotation, written as coefficients so
        // the per-pixel branch disappears from the inner loop.
        val ax: Float; val bx: Float; val cx: Float
        val ay: Float; val by: Float; val cy: Float
        when (deg) {
            90 -> { ax = 0f; bx = 0f; cx = 1f; ay = maxYF; by = -1f; cy = 0f }
            180 -> { ax = maxXF; bx = -1f; cx = 0f; ay = maxYF; by = 0f; cy = -1f }
            270 -> { ax = maxXF; bx = 0f; cx = -1f; ay = 0f; by = 1f; cy = 0f }
            else -> { ax = 0f; bx = 1f; cx = 0f; ay = 0f; by = 0f; cy = 1f }
        }

        for (row in 0 until dstH) {
            val ry = (row + 0.5f) * yScale - 0.5f
            val outBase = row * dstW
            for (col in 0 until dstW) {
                val rx = (col + 0.5f) * xScale - 0.5f
                var sxf = ax + bx * rx + cx * ry
                var syf = ay + by * rx + cy * ry
                if (sxf < 0f) sxf = 0f else if (sxf > maxXF) sxf = maxXF
                if (syf < 0f) syf = 0f else if (syf > maxYF) syf = maxYF

                val x0 = sxf.toInt()
                val y0 = syf.toInt()
                val fxs = sxf - x0
                val fys = syf - y0
                val x1 = if (x0 < maxX) x0 + 1 else maxX
                val y1 = if (y0 < maxY) y0 + 1 else maxY
                val r0 = y0 * srcW
                val r1 = y1 * srcW
                val l00 = (yPlane[r0 + x0].toInt() and 0xFF).toFloat()
                val l10 = (yPlane[r0 + x1].toInt() and 0xFF).toFloat()
                val l01 = (yPlane[r1 + x0].toInt() and 0xFF).toFloat()
                val l11 = (yPlane[r1 + x1].toInt() and 0xFF).toFloat()
                val top = l00 + (l10 - l00) * fxs
                val bottom = l01 + (l11 - l01) * fxs
                val luma = top + (bottom - top) * fys

                val cu = ((x0 shr 1).coerceAtMost(cw - 1)) * uvPixelStride
                val cv = (y0 shr 1).coerceAtMost(ch - 1)
                val uvIdx = cv * uvRowStride + cu
                var uu = 0
                var vv = 0
                if (uvIdx >= 0 && uvIdx < uPlane.size) {
                    uu = (uPlane[uvIdx].toInt() and 0xFF) - 128
                    vv = (vPlane[uvIdx].toInt() and 0xFF) - 128
                }

                // Integer maths, same shape as yuv420ToArgb: float divisions per pixel were expensive.
                val yInt = (luma * spec.yMul + spec.yOff + 0.5f).toInt()
                val r = (yInt + spec.cr * vv + 128) shr 8
                val g = (yInt - spec.cgu * uu - spec.cgv * vv + 128) shr 8
                val b = (yInt + spec.cb * uu + 128) shr 8
                out[outBase + col] = (0xFF shl 24) or
                    (clamp(r) shl 16) or (clamp(g) shl 8) or clamp(b)
            }
        }
    }

    /**
     * The unrotated scaling path: identical maths to [yuv420ToArgbScaled], with the source column
     * of every output column resolved once in [ScaleTables] instead of once per pixel.
     */
    private fun scaleToArgb(
        y: ByteBuffer, u: ByteBuffer, v: ByteBuffer,
        yRowStride: Int, uvRowStride: Int, uvPixelStride: Int,
        srcW: Int, srcH: Int, dstW: Int, dstH: Int,
        spec: Spec, out: IntArray, s: PlaneScratch
    ) {
        val cw = (srcW + 1) / 2
        val ch = (srcH + 1) / 2
        s.tables.ensure(srcW, dstW, cw, uvPixelStride)
        val x0c = s.tables.x0
        val x1c = s.tables.x1
        val fxc = s.tables.fx
        val cuc = s.tables.chroma
        val luma = s.luma(y, srcW, srcH, yRowStride)
        val ua = s.chroma(u, uvRowStride * ch)
        val va = s.chromaV(v, uvRowStride * ch)
        val lut = spec.lumaLut
        val yMul = spec.yMul
        val yOff = spec.yOff
        val cr = spec.cr
        val cgu = spec.cgu
        val cgv = spec.cgv
        val cb = spec.cb
        val maxY = srcH - 1
        val maxYF = maxY.toFloat()
        val yScale = srcH.toFloat() / dstH
        for (row in 0 until dstH) {
            var syf = (row + 0.5f) * yScale - 0.5f
            if (syf < 0f) syf = 0f else if (syf > maxYF) syf = maxYF
            val y0 = syf.toInt()
            val fy = syf - y0
            val y1 = if (y0 < maxY) y0 + 1 else maxY
            val r0 = y0 * srcW
            val r1 = y1 * srcW
            val cBase = (y0 shr 1).coerceAtMost(ch - 1) * uvRowStride
            val outBase = row * dstW
            for (col in 0 until dstW) {
                val xa = x0c[col]
                val xb = x1c[col]
                val fx = fxc[col]
                val l00 = (luma[r0 + xa].toInt() and 0xFF).toFloat()
                val l10 = (luma[r0 + xb].toInt() and 0xFF).toFloat()
                val l01 = (luma[r1 + xa].toInt() and 0xFF).toFloat()
                val l11 = (luma[r1 + xb].toInt() and 0xFF).toFloat()
                val top = l00 + (l10 - l00) * fx
                val bottom = l01 + (l11 - l01) * fx
                val blended = top + (bottom - top) * fy
                val uvIdx = cBase + cuc[col]
                var uu = 0
                var vv = 0
                if (uvIdx >= 0 && uvIdx < ua.size) {
                    uu = (ua[uvIdx].toInt() and 0xFF) - 128
                    vv = (va[uvIdx].toInt() and 0xFF) - 128
                }
                val yInt = (blended * yMul + yOff + 0.5f).toInt()
                val r = (yInt + cr * vv + 128) shr 8
                val g = (yInt - cgu * uu - cgv * vv + 128) shr 8
                val b = (yInt + cb * uu + 128) shr 8
                out[outBase + col] = (0xFF shl 24) or
                    (clamp(r) shl 16) or (clamp(g) shl 8) or clamp(b)
            }
        }
    }

    /**
     * Writes planar RGB floats in [0,1] (CHW, as returned by the RIFE model) straight into YUV 4:2:0
     * planes. Saves the intermediate ARGB frame the encoder used to require.
     */
    fun planarToYuv420(
        planar: FloatArray, width: Int, height: Int,
        y: ByteBuffer, u: ByteBuffer, v: ByteBuffer,
        yRowStride: Int, uvRowStride: Int, uvPixelStride: Int,
        spec: Spec,
        scratch: EncScratch? = null
    ) {
        val n = width * height
        require(planar.size >= n * 3) { "planar buffer too small" }
        val eY = spec.eY
        val eU = spec.eU
        val eV = spec.eV
        val yOff = spec.encYOff
        val uvOff = spec.encUvOff
        val cw = (width + 1) / 2
        val ch = (height + 1) / 2

        // Convert into packed arrays, then write whole rows: per-pixel ByteBuffer.put() calls were
        // the dominant cost of this stage.
        val s = scratch ?: EncScratch()
        val yPlane = s.luma(n)
        val uPlane = s.chroma(cw * ch, false)
        val vPlane = s.chroma(cw * ch, true)

        for (row in 0 until height) {
            val base = row * width
            val gBase = n + base
            val bBase = n * 2 + base
            for (col in 0 until width) {
                val k = base + col
                val r = toByteChannel(planar[k])
                val g = toByteChannel(planar[gBase + col])
                val b = toByteChannel(planar[bBase + col])
                yPlane[k] = clamp(((eY[0] * r + eY[1] * g + eY[2] * b + 128) shr 8) + yOff).toByte()
            }
        }

        var row = 0
        while (row < height) {
            val cRow = row shr 1
            val uvBase = cRow * uvRowStride
            var col = 0
            while (col < width) {
                var rSum = 0
                var gSum = 0
                var bSum = 0
                var count = 0
                for (dy in 0..1) {
                    val yy = row + dy
                    if (yy >= height) break
                    val base = yy * width
                    val gBase = n + base
                    val bBase = n * 2 + base
                    for (dx in 0..1) {
                        val xx = col + dx
                        if (xx >= width) break
                        rSum += toByteChannel(planar[base + xx])
                        gSum += toByteChannel(planar[gBase + xx])
                        bSum += toByteChannel(planar[bBase + xx])
                        count++
                    }
                }
                if (count > 0) {
                    val r = rSum / count
                    val g = gSum / count
                    val b = bSum / count
                    val cell = cRow * cw + (col shr 1)
                    uPlane[cell] = clamp(((eU[0] * r + eU[1] * g + eU[2] * b + 128) shr 8) + uvOff).toByte()
                    vPlane[cell] = clamp(((eV[0] * r + eV[1] * g + eV[2] * b + 128) shr 8) + uvOff).toByte()
                }
                col += 2
            }
            row += 2
        }

        writeRows(y, yPlane, height, yRowStride, width, width)
        if (uvPixelStride >= 2) {
            // Semi-planar (NV12/NV21): U and V are interleaved in a single buffer. Writing the pairs
            // into the U plane covers both, so the V plane is left untouched.
            val interleaved = ByteArray(minOf(uvRowStride, cw * 2))
            val dst = u.duplicate()
            dst.position(0)
            dst.limit(dst.capacity())
            for (cRow in 0 until ch) {
                val start = cRow * uvRowStride
                if (start >= dst.capacity()) break
                val pairCount = minOf(cw, interleaved.size / 2)
                for (col in 0 until pairCount) {
                    interleaved[col * 2] = uPlane[cRow * cw + col]
                    interleaved[col * 2 + 1] = vPlane[cRow * cw + col]
                }
                val end = minOf(start + pairCount * 2, dst.capacity())
                dst.position(start)
                dst.limit(end)
                dst.put(interleaved, 0, end - start)
            }
        } else {
            writeRows(u, uPlane, ch, uvRowStride, cw, cw)
            writeRows(v, vPlane, ch, uvRowStride, cw, cw)
        }
    }

    private fun writeRows(
        dst: ByteBuffer, src: ByteArray, rows: Int, rowStride: Int, width: Int, packedWidth: Int
    ) {
        val out = dst.duplicate()
        val capacity = out.capacity()
        for (row in 0 until rows) {
            val start = row * rowStride
            if (start >= capacity) break
            val end = minOf(start + width, capacity)
            // Same reason as PlaneScratch.luma: the limit must be widened before the next row.
            out.limit(capacity)
            out.position(start)
            out.limit(end)
            out.put(src, row * packedWidth, end - start)
        }
    }

    /** Reusable packed Y/U/V buffers for the encode path. */
    class EncScratch {
        private var yBuf = ByteArray(0)
        private var uBuf = ByteArray(0)
        private var vBuf = ByteArray(0)

        fun luma(size: Int): ByteArray {
            if (yBuf.size < size) yBuf = ByteArray(size)
            return yBuf
        }

        fun chroma(size: Int, second: Boolean): ByteArray {
            if (second) {
                if (vBuf.size < size) vBuf = ByteArray(size)
                return vBuf
            }
            if (uBuf.size < size) uBuf = ByteArray(size)
            return uBuf
        }
    }

    /**
     * Column mapping of the unrotated scaling path: source x0/x1, the blend factor and the chroma
     * offset of every output column. Built once per frame geometry, reused for the whole job.
     */
    class ScaleTables {
        private var key = Long.MIN_VALUE
        var x0 = IntArray(0); private set
        var x1 = IntArray(0); private set
        var fx = FloatArray(0); private set
        var chroma = IntArray(0); private set

        fun ensure(srcW: Int, dstW: Int, cw: Int, uvPixelStride: Int) {
            val k = (srcW.toLong() shl 40) or (dstW.toLong() shl 20) or (uvPixelStride.toLong() shl 8) or cw.toLong()
            if (k == key) return
            if (x0.size != dstW) {
                x0 = IntArray(dstW)
                x1 = IntArray(dstW)
                fx = FloatArray(dstW)
                chroma = IntArray(dstW)
            }
            val xScale = srcW.toFloat() / dstW
            val maxX = srcW - 1
            val maxXF = maxX.toFloat()
            for (col in 0 until dstW) {
                var sxf = (col + 0.5f) * xScale - 0.5f
                if (sxf < 0f) sxf = 0f else if (sxf > maxXF) sxf = maxXF
                val a = sxf.toInt()
                x0[col] = a
                x1[col] = if (a < maxX) a + 1 else maxX
                fx[col] = sxf - a
                chroma[col] = ((a shr 1).coerceAtMost(cw - 1)) * uvPixelStride
            }
            key = k
        }
    }

    /** Reusable plane copies so the fused decode path does not allocate per frame. */
    class PlaneScratch {
        private var yBuf: ByteArray = ByteArray(0)
        private var uvBuf: ByteArray = ByteArray(0)
        private var vBuf: ByteArray = ByteArray(0)

        /** Precomputed column mapping of the unrotated scaling path. */
        internal val tables = ScaleTables()

        /** Luma plane packed without row padding, `width * height` bytes. */
        fun luma(src: ByteBuffer, width: Int, height: Int, rowStride: Int): ByteArray {
            val need = width * height
            if (yBuf.size < need) yBuf = ByteArray(need)
            val out = yBuf
            val dup = src.duplicate()
            val capacity = dup.capacity()
            for (row in 0 until height) {
                val start = row * rowStride
                if (start >= capacity) break
                val end = minOf(start + width, capacity)
                // The limit was tightened to the end of the previous row: widen it again before
                // moving past it. Without this, any padded stride (rowStride > width, i.e. every
                // width that is not a multiple of 64) threw on the second row.
                dup.limit(capacity)
                dup.position(start)
                dup.limit(end)
                dup.get(out, row * width, end - start)
            }
            return out
        }

        /** Raw chroma plane, padding included so indices match the source layout. */
        fun chroma(src: ByteBuffer, bytes: Int): ByteArray {
            if (uvBuf.size < bytes) uvBuf = ByteArray(bytes)
            val dup = src.duplicate()
            dup.position(0)
            dup.limit(dup.capacity())
            val n = minOf(bytes, dup.remaining())
            dup.get(uvBuf, 0, n)
            return uvBuf
        }

        /** Raw V plane (kept separate from [chroma]'s buffer). */
        fun chromaV(src: ByteBuffer, bytes: Int): ByteArray {
            if (vBuf.size < bytes) vBuf = ByteArray(bytes)
            val dup = src.duplicate()
            dup.position(0)
            dup.limit(dup.capacity())
            val n = minOf(bytes, dup.remaining())
            dup.get(vBuf, 0, n)
            return vBuf
        }
    }

    private fun toByteChannel(v: Float): Int {
        val x = (v * 255f + 0.5f).toInt()
        return if (x < 0) 0 else if (x > 255) 255 else x
    }

    private fun clamp(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v
}
