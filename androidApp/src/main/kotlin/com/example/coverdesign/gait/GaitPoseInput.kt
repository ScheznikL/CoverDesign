package com.example.coverdesign.gait

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.roundToInt

/** Preserve the upright field of view; marker segmentation retains its separate full RGB input. */
internal fun poseInputBitmap(original: Bitmap): Bitmap {
    val longest = max(original.width, original.height)
    if (longest <= 640) return original
    val scale = 640.0 / longest
    return Bitmap.createScaledBitmap(original, (original.width * scale).roundToInt().coerceAtLeast(1),
        (original.height * scale).roundToInt().coerceAtLeast(1), true)
}
