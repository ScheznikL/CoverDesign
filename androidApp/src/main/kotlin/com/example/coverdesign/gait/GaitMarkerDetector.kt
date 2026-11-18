package com.example.coverdesign.gait

import kotlin.math.max
import kotlin.math.min
import kotlin.math.floor
import kotlin.math.roundToInt

internal data class MarkerBlob(val color: MarkerColor, val point: MarkerPoint, val areaFraction: Double)

/** HSV thresholding and connected components. It never fills in missing points. */
internal class MarkerBlobDetector {
    var lastColourPixelCounts: Map<MarkerColor, Int> = emptyMap()
        private set
    var lastWorkingWidth: Int = 0
        private set
    var lastWorkingHeight: Int = 0
        private set
    fun detect(argb: IntArray, width: Int, height: Int, calibration: MarkerColorCalibration? = null, view: GaitView? = null): List<MarkerBlob> {
        lastColourPixelCounts = emptyMap()
        lastWorkingWidth = 0
        lastWorkingHeight = 0
        if (width < 3 || height < 3 || width.toLong() * height != argb.size.toLong()) return emptyList()
        // The same physical scene must have the same working scale on a 640, 1088 or 1920
        // pixel camera output. Integer stride made higher-resolution inputs lose more detail.
        val scale = min(1.0, 640.0 / max(width, height))
        val w = max(1, (width * scale).roundToInt())
        val h = max(1, (height * scale).roundToInt())
        lastWorkingWidth = w
        lastWorkingHeight = h
        if (w < 3 || h < 3) return emptyList()
        val classifier = MarkerColorClassifier.forFrame(calibration, view)
        if (!classifier.hasTargets) {
            lastColourPixelCounts = MarkerColor.entries.associateWith { 0 }
            return emptyList()
        }
        val counts = IntArray(MarkerColor.entries.size)
        val raw = IntArray(w * h)
        if (w == width && h == height) {
            for (index in raw.indices) {
                val code = classifier.classifyCode(argb[index])
                if (code != 0) { counts[code - 1]++; raw[index] = code }
            }
        } else {
            // Pixel-centre aligned bilinear RGB sampling, with no upscaling or invented blobs.
            // Keep interpolation out of HSV space so hue wrap is handled by the classifier.
            val left = IntArray(w)
            val right = IntArray(w)
            val fractionX = DoubleArray(w)
            for (x in 0 until w) {
                val sourceX = ((x + .5) * width / w - .5).coerceIn(0.0, width - 1.0)
                left[x] = floor(sourceX).toInt()
                right[x] = min(left[x] + 1, width - 1)
                fractionX[x] = sourceX - left[x]
            }
            for (y in 0 until h) {
                val sourceY = ((y + .5) * height / h - .5).coerceIn(0.0, height - 1.0)
                val top = floor(sourceY).toInt()
                val bottom = min(top + 1, height - 1)
                val fractionY = sourceY - top
                for (x in 0 until w) {
                    val pixel = bilinearPixel(argb[top * width + left[x]], argb[top * width + right[x]],
                        argb[bottom * width + left[x]], argb[bottom * width + right[x]], fractionX[x], fractionY)
                    val code = classifier.classifyCode(pixel)
                    if (code != 0) { counts[code - 1]++; raw[y * w + x] = code }
                }
            }
        }
        lastColourPixelCounts = MarkerColor.entries.associateWith { counts[it.ordinal] }
        // Reject isolated pixels before component extraction; preserve small square markers.
        val mask = IntArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val color = raw[i]
            if (color != 0) {
                var same = 0
                for (dy in -1..1) for (dx in -1..1) if (raw[i + dy * w + dx] == color) same++
                if (same >= 5) mask[i] = color
            }
        }
        val queue = IntArray(mask.size)
        val results = mutableListOf<MarkerBlob>()
        for (start in mask.indices) {
            val color = mask[start]
            if (color == 0) continue
            var head = 0
            var tail = 1
            queue[0] = start
            mask[start] = 0
            var sx = 0L
            var sy = 0L
            var xmin = w
            var xmax = 0
            var ymin = h
            var ymax = 0
            while (head < tail) {
                val i = queue[head++]
                val x = i % w
                val y = i / w
                sx += x
                sy += y
                xmin = min(xmin, x); xmax = max(xmax, x)
                ymin = min(ymin, y); ymax = max(ymax, y)
                fun push(j: Int) {
                    if (mask[j] == color) { mask[j] = 0; queue[tail++] = j }
                }
                if (x > 0) push(i - 1)
                if (x < w - 1) push(i + 1)
                if (y > 0) push(i - w)
                if (y < h - 1) push(i + w)
            }
            val bw = xmax - xmin + 1
            val bh = ymax - ymin + 1
            val fraction = tail.toDouble() / mask.size
            val aspect = bw.toDouble() / bh
            val solidity = tail.toDouble() / (bw * bh)
            val shoulderShape = color == MarkerColor.SHOULDERS.ordinal + 1
            val aspectRange = if (shoulderShape) 0.22..4.5 else 0.45..2.2
            if (tail >= 8 && fraction in 0.00004..0.02 && aspect in aspectRange && solidity >= 0.48) {
                results += MarkerBlob(
                    MarkerColor.entries[color - 1],
                    MarkerPoint((sx.toDouble() / tail + 0.5) / w, (sy.toDouble() / tail + 0.5) / h),
                    fraction
                )
            }
        }
        return results
    }

    private fun bilinearPixel(topLeft: Int, topRight: Int, bottomLeft: Int, bottomRight: Int, fx: Double, fy: Double): Int {
        fun channel(shift: Int): Int {
            val top = ((topLeft shr shift) and 255) * (1.0 - fx) + ((topRight shr shift) and 255) * fx
            val bottom = ((bottomLeft shr shift) and 255) * (1.0 - fx) + ((bottomRight shr shift) and 255) * fx
            return (top * (1.0 - fy) + bottom * fy).roundToInt().coerceIn(0, 255)
        }
        return (0xff shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
