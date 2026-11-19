package com.example.coverdesign.domain.model

/**
 * User-scoped gait profile sent to the rehab server (biometrics + capture settings).
 * Mirrors the on-device `GaitProfile`; the server reads it when analysing a video so it can
 * calibrate centimetre scale and age/sex-sensitive scoring.
 */
data class GaitProfileInput(
    val heightCm: Int,
    val weightKg: Int,
    val sex: String,                     // "Female" | "Male" | "Other" | "Prefer not to say"
    val age: Int,
    val passSteps: Int,
    val targetStepsPerView: Int,
    val passDurationSeconds: Int,
    val targetDurationSecondsPerView: Int,
    val updatedAt: Long
)