package com.example.coverdesign.gait

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.Closeable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

internal const val GAIT_VIDEO_MAX_DURATION_MS = 120_000L
internal const val GAIT_VIDEO_FRAME_INTERVAL_MS = 200L
private const val MAX_DECODE_SIDE = 1280

/** Upright source dimensions; preview/analysis buffers are bounded separately. */
internal data class GaitVideoMetadata(val durationMs: Long, val width: Int, val height: Int)

/** Read-only document access. No URI, decoded photograph or video is written by this source. */
internal class GaitVideoSource(context: Context, uri: Uri, private val mirrored: Boolean = false) : Closeable {
    private val retriever = MediaMetadataRetriever()
    private var closed = false
    private val encodedWidth: Int
    private val encodedHeight: Int
    private val rotation: Int
    val metadata: GaitVideoMetadata

    init {
        try {
            retriever.setDataSource(context.applicationContext, uri)
            encodedWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            encodedHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            rotation = ((retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0) % 360 + 360) % 360
            require(encodedWidth in 1..8192 && encodedHeight in 1..8192) {
                "This file has no supported video picture. Choose a normal phone video."
            }
            require(duration in 1_000L..GAIT_VIDEO_MAX_DURATION_MS) {
                "Choose a video between 1 second and 2 minutes. Trim longer recordings first."
            }
            require(rotation in listOf(0, 90, 180, 270)) { "This video's orientation is unsupported. Export an upright copy first." }
            val swapped = rotation == 90 || rotation == 270
            metadata = GaitVideoMetadata(duration,
                if (swapped) encodedHeight else encodedWidth,
                if (swapped) encodedWidth else encodedHeight)
        } catch (error: Throwable) {
            runCatching { retriever.release() }
            closed = true
            throw error
        }
    }

    /** Caller owns the returned ARGB buffer and must recycle it after use. */
    @Synchronized
    fun frameAt(atMs: Long): Bitmap {
        check(!closed) { "The video has already been closed." }
        val time = atMs.coerceIn(0L, metadata.durationMs - 1)
        val params = MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.ARGB_8888 }
        // A square limit preserves resolution whether scaling precedes or follows the codec's
        // rotation. Never decode a full 4K picture as an error fallback.
        var owned = requireNotNull(retriever.getScaledFrameAtTime(time * 1000,
            MediaMetadataRetriever.OPTION_CLOSEST, MAX_DECODE_SIDE, MAX_DECODE_SIDE, params)) {
            "The selected frame could not be decoded. Try another frame or an MP4 recording."
        }
        try {
            val swapsAxes = rotation == 90 || rotation == 270
            val uprightAspect = metadata.width.toDouble() / metadata.height
            val encodedAspect = encodedWidth.toDouble() / encodedHeight
            val decodedAspect = owned.width.toDouble() / owned.height
            // Android normally returns an already upright bitmap. Only rotate when the actual
            // non-square dimensions establish that this decoder retained the encoded axes.
            if (swapsAxes && abs(uprightAspect - encodedAspect) > .01 &&
                abs(decodedAspect - encodedAspect) < abs(decodedAspect - uprightAspect)) {
                val corrected = Bitmap.createBitmap(owned, 0, 0, owned.width, owned.height,
                    Matrix().apply { postRotate(rotation.toFloat()) }, true)
                if (corrected !== owned) owned.recycle()
                owned = corrected
            }
            if (max(owned.width, owned.height) > MAX_DECODE_SIDE) {
                val factor = MAX_DECODE_SIDE.toDouble() / max(owned.width, owned.height)
                val scaled = Bitmap.createScaledBitmap(owned, (owned.width * factor).roundToInt().coerceAtLeast(1),
                    (owned.height * factor).roundToInt().coerceAtLeast(1), true)
                if (scaled !== owned) owned.recycle()
                owned = scaled
            }
            if (owned.config != Bitmap.Config.ARGB_8888) {
                val argb = requireNotNull(owned.copy(Bitmap.Config.ARGB_8888, false)) { "The video colour format is unsupported." }
                if (argb !== owned) owned.recycle()
                owned = argb
            }
            if (mirrored) {
                val corrected = Bitmap.createBitmap(owned, 0, 0, owned.width, owned.height,
                    Matrix().apply { postScale(-1f, 1f) }, false)
                if (corrected !== owned) owned.recycle()
                owned = corrected
            }
            return owned
        } catch (error: Throwable) {
            if (!owned.isRecycled) owned.recycle()
            throw error
        }
    }

    @Synchronized
    fun snapshotAt(atMs: Long, view: GaitView): GaitColourSnapshot {
        val bitmap = frameAt(atMs)
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return GaitColourSnapshot(pixels, bitmap.width, bitmap.height,
                atMs.coerceIn(0L, metadata.durationMs - 1), view)
        } finally { bitmap.recycle() }
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            runCatching { retriever.release() }
        }
    }
}
