package com.example.coverdesign.gait

import kotlin.math.roundToInt

/** Starting geometry for a level, full-body view, not a clinically validated camera height. */
internal fun suggestedCameraHeightCm(heightCm: Int): Int = (heightCm / 10.0).roundToInt() * 5

internal fun cameraHeightGuidance(heightCm: Int): String =
    "Place the camera lens about ${suggestedCameraHeightCm(heightCm)} cm above the floor (around half your height of $heightCm cm)."
