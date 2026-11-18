package com.example.coverdesign.gait

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.abs
import kotlin.math.atan2

/** Upright, unmirrored image coordinates. Anatomical left and right belong to the subject. */
enum class GaitView { FRONT, BACK }

/** Estimated pose joints and placed surface markers are distinct measurement methods. */
enum class GaitMethod { MARKERS, POSE }

enum class MarkerId(val label: String) {
    LEFT_SHOULDER("Left shoulder"), RIGHT_SHOULDER("Right shoulder"),
    LEFT_PELVIS("Left pelvis"), RIGHT_PELVIS("Right pelvis"),
    LEFT_KNEE("Left knee"), RIGHT_KNEE("Right knee"),
    C7("C7 at the base of the neck"), COCCYX("Tailbone (coccyx) marker")
}

enum class GaitMetric(val label: String) {
    SHOULDERS("Shoulders"), PELVIS("Pelvis"), KNEES("Knees"), SPINE("C7–coccyx line")
}

data class MarkerPoint(val x: Double, val y: Double)
data class MarkerDiagnostics(val colourCandidates: Map<String, Int>, val poseAvailable: Boolean, val colourPixels: Map<String, Int> = emptyMap(), val workingWidth: Int? = null, val workingHeight: Int? = null, val conversionMs: Long? = null, val colourMs: Long? = null, val poseMs: Long? = null)
data class GaitFrame(
    val timestampMs: Long,
    val width: Int,
    val height: Int,
    val markers: Map<MarkerId, MarkerPoint>,
    val observedView: GaitView?,
    val bodyVisible: Boolean,
    val stepSignal: Double?,
    val markerDiagnostics: MarkerDiagnostics? = null,
    val measurementMethod: GaitMethod = GaitMethod.MARKERS
)

fun requiredMarkers(view: GaitView, method: GaitMethod = GaitMethod.MARKERS): List<MarkerId> = listOf(
    MarkerId.LEFT_SHOULDER, MarkerId.RIGHT_SHOULDER,
    MarkerId.LEFT_PELVIS, MarkerId.RIGHT_PELVIS
) + if (view == GaitView.FRONT) listOf(MarkerId.LEFT_KNEE, MarkerId.RIGHT_KNEE)
else if (method == GaitMethod.MARKERS) listOf(MarkerId.C7, MarkerId.COCCYX) else emptyList()

fun expectedGaitMetrics(view: GaitView, method: GaitMethod = GaitMethod.MARKERS): List<GaitMetric> =
    listOf(GaitMetric.SHOULDERS, GaitMetric.PELVIS) +
        if (view == GaitView.FRONT) listOf(GaitMetric.KNEES)
        else if (method == GaitMethod.MARKERS) listOf(GaitMetric.SPINE) else emptyList()

data class GaitProfile(
    val heightCm: Int = 170,
    val weightKg: Int = 70,
    val sex: String = "Prefer not to say",
    val age: Int = 30,
    val passSteps: Int = 4,
    val targetStepsPerView: Int = 12,
    val passDurationSeconds: Int = 2,
    val targetDurationSecondsPerView: Int = 6
) {
    fun validationError(): String? = when {
        heightCm !in 40..250 -> "Enter height between 40 and 250 cm."
        weightKg !in 10..350 -> "Enter weight between 10 and 350 kg."
        age !in 1..120 -> "Enter age between 1 and 120 years."
        passSteps !in 2..10 -> "Choose 2 to 10 steps per pass."
        targetStepsPerView !in 4..100 -> "Choose 4 to 100 usable steps per view."
        passDurationSeconds !in 2..4 -> "Choose a 2 to 4 second pass."
        targetDurationSecondsPerView !in 6..60 -> "Choose 6 to 60 usable seconds per view."
        targetDurationSecondsPerView < passDurationSeconds -> "The view target must include at least one pass."
        else -> null
    }
}

/** Compare the units actually used by a protocol, without changing historical profile values. */
internal fun GaitProfile.matchesCaptureSettings(other: GaitProfile, protocolVersion: Int): Boolean {
    if (heightCm != other.heightCm || weightKg != other.weightKg || sex != other.sex || age != other.age) return false
    return if (protocolVersion >= 6) {
        passDurationSeconds == other.passDurationSeconds && targetDurationSecondsPerView == other.targetDurationSecondsPerView
    } else {
        passSteps == other.passSteps && targetStepsPerView == other.targetStepsPerView
    }
}

data class GaitSample(
    val atMs: Long,
    val view: GaitView,
    val angles: Map<GaitMetric, Double>,
    val stepIndex: Int,
    val passIndex: Int = 0
)

data class GaitSession(
    val id: String,
    val createdAt: Long,
    val profile: GaitProfile,
    val samples: List<GaitSample>,
    val frontSteps: Int,
    val backSteps: Int,
    val acceptedFrames: Int,
    val rejectedFrames: Int,
    val manualLevel: Boolean,
    val completed: Boolean,
    val protocolVersion: Int = 7,
    val frontValidDurationMs: Long = 0,
    val backValidDurationMs: Long = 0,
    val frontCompletedPasses: Int = 0,
    val backCompletedPasses: Int = 0,
    val frontStepsPartial: Boolean = false,
    val backStepsPartial: Boolean = false,
    val acquisitionSource: String = "LIVE",
    val sourceDurationMs: Long = 0,
    val sourceMirrored: Boolean = false,
    val videoViews: Set<GaitView> = emptySet(),
    val measurementMethod: GaitMethod = GaitMethod.MARKERS
)

data class MetricSummary(
    val mean: Double,
    val meanAbsolute: Double,
    val min: Double,
    val max: Double,
    val count: Int
)

object GaitMath {
    /** Positive horizontal angles mean anatomical left is lower. Back-view spine positive means right lean. */
    fun angles(frame: GaitFrame, view: GaitView): Map<GaitMetric, Double> {
        if (frame.width <= 0 || frame.height <= 0) return emptyMap()
        val result = linkedMapOf<GaitMetric, Double>()
        fun pixel(id: MarkerId): MarkerPoint? = frame.markers[id]?.takeIf {
            it.x.isFinite() && it.y.isFinite() && it.x in 0.0..1.0 && it.y in 0.0..1.0
        }?.let { MarkerPoint(it.x * frame.width, it.y * frame.height) }
        fun pair(metric: GaitMetric, leftId: MarkerId, rightId: MarkerId) {
            val l = pixel(leftId) ?: return
            val r = pixel(rightId) ?: return
            val dx = l.x - r.x
            // Reject duplicate contours and side assignments crossed by an occlusion or turn.
            if (abs(dx) < frame.width * 0.02 || abs(dx) > frame.width * 0.85) return
            if ((view == GaitView.FRONT && dx <= 0) || (view == GaitView.BACK && dx >= 0)) return
            result[metric] = Math.toDegrees(atan2(l.y - r.y, abs(dx)))
        }
        pair(GaitMetric.SHOULDERS, MarkerId.LEFT_SHOULDER, MarkerId.RIGHT_SHOULDER)
        pair(GaitMetric.PELVIS, MarkerId.LEFT_PELVIS, MarkerId.RIGHT_PELVIS)
        if (view == GaitView.FRONT) pair(GaitMetric.KNEES, MarkerId.LEFT_KNEE, MarkerId.RIGHT_KNEE)
        if (view == GaitView.BACK && frame.measurementMethod == GaitMethod.MARKERS) {
            val top = pixel(MarkerId.C7)
            val bottom = pixel(MarkerId.COCCYX)
            if (top != null && bottom != null && bottom.y - top.y >= frame.height * 0.08) {
                result[GaitMetric.SPINE] = Math.toDegrees(atan2(top.x - bottom.x, bottom.y - top.y))
            }
        }
        return result
    }

    fun summary(session: GaitSession, metric: GaitMetric, view: GaitView): MetricSummary? {
        if (metric !in expectedGaitMetrics(view, session.measurementMethod)) return null
        val values = session.samples.asSequence().filter { it.view == view }
            .mapNotNull { it.angles[metric] }.filter { it.isFinite() }.toList()
        if (values.isEmpty()) return null
        return MetricSummary(values.average(), values.map(::abs).average(), values.min(), values.max(), values.size)
    }
}

/**
 * Research step estimate from alternating leg/foot height, not a validated clinical step detector.
 * A sign must persist before being accepted. Missing frames break phase continuity so turns and
 * occlusions cannot be bridged by an invented step. The first observed phase establishes a baseline.
 */
class GaitStepCounter {
    private var phase = 0
    private var candidate = 0
    private var candidateSince = 0L
    private var lastSeen: Long? = null
    private var lastStep: Long? = null

    fun reset() {
        phase = 0
        candidate = 0
        candidateSince = 0L
        lastSeen = null
        lastStep = null
    }

    fun update(signal: Double?, atMs: Long): Boolean {
        val previous = lastSeen
        if (signal == null || !signal.isFinite()) {
            reset()
            return false
        }
        if (previous != null && (atMs <= previous || atMs - previous > 700L)) reset()
        lastSeen = atMs
        val sign = when {
            signal > 0.055 -> 1
            signal < -0.055 -> -1
            else -> 0
        }
        if (sign == 0) {
            candidate = 0
            return false
        }
        if (candidate != sign) {
            candidate = sign
            candidateSince = atMs
            return false
        }
        if (atMs - candidateSince < 80L || sign == phase) return false
        if (phase == 0) {
            phase = sign
            return false
        }
        // 220 ms is a debounce, not a normative gait limit. Slower transitions remain allowed.
        if (lastStep != null && atMs - lastStep!! < 220L) return false
        phase = sign
        lastStep = atMs
        return true
    }
}

/** Private, non-backed-up, account-scoped data. No camera images or video are persisted. */
class GaitStore(context: Context, userId: String) {
    private val file: AtomicFile
    private val lock: Any
    init {
        require(userId.isNotBlank()) { "A signed-in user is required for gait history." }
        val key = MessageDigest.getInstance("SHA-256").digest(userId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val dir = File(context.noBackupFilesDir, "gait")
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Could not create local gait storage.")
        val path = File(dir, "$key.json")
        file = AtomicFile(path)
        lock = locks.getOrPut(path.absolutePath) { Any() }
    }

    var instructionSeen: Boolean
        get() = synchronized(lock) { read().optBoolean("instructionSeen", false) }
        set(value) = synchronized(lock) { change { it.put("instructionSeen", value) } }

    fun profile(): GaitProfile? = synchronized(lock) {
        read().optJSONObject("profile")?.let(::readProfile)
    }

    fun saveProfile(profile: GaitProfile) = synchronized(lock) {
        require(profile.validationError() == null) { profile.validationError().orEmpty() }
        change { it.put("profile", writeProfile(profile)) }
    }

    fun sessions(): List<GaitSession> = synchronized(lock) {
        val a = read().optJSONArray("sessions") ?: JSONArray()
        try {
            (0 until a.length()).map { readSession(a.getJSONObject(it)) }.sortedByDescending { it.createdAt }
        } catch (e: Exception) {
            throw IOException("Saved gait sessions could not be read. Existing data has been preserved.", e)
        }
    }

    fun saveSession(session: GaitSession) = synchronized(lock) {
        require(session.id.isNotBlank()) { "A session ID is required." }
        require(session.samples.size <= 30_000) { "This session is too long to save. End the measurement sooner." }
        require(session.samples.all { s -> s.angles.values.all { it.isFinite() } }) { "Cannot save non-finite measurements." }
        if (session.measurementMethod == GaitMethod.POSE) require(session.samples.all { sample ->
            sample.angles.keys.all { it in expectedGaitMetrics(sample.view, GaitMethod.POSE) }
        }) { "Pose analysis cannot measure the C7–coccyx line or unsupported joint angles." }
        require(session.frontValidDurationMs >= 0 && session.backValidDurationMs >= 0) { "Recorded durations cannot be negative." }
        require(session.frontCompletedPasses >= 0 && session.backCompletedPasses >= 0) { "Completed pass counts cannot be negative." }
        require(session.samples.all { it.passIndex >= 0 }) { "Pass indices cannot be negative." }
        require(session.acquisitionSource in setOf("LIVE", "VIDEO")) { "Unknown measurement source." }
        require(session.sourceDurationMs >= 0) { "Video duration cannot be negative." }
        change { root ->
            val previous = root.optJSONArray("sessions") ?: JSONArray()
            val next = JSONArray()
            for (i in 0 until previous.length()) {
                val item = previous.getJSONObject(i)
                if (item.getString("id") != session.id) next.put(item)
            }
            next.put(writeSession(session))
            root.put("sessions", next)
        }
    }

    fun deleteSession(id: String) = synchronized(lock) {
        change { root ->
            val old = root.optJSONArray("sessions") ?: JSONArray()
            val next = JSONArray()
            for (i in 0 until old.length()) if (old.getJSONObject(i).getString("id") != id) next.put(old.get(i))
            root.put("sessions", next)
            val deleted = root.optJSONArray("deletedSessionIds") ?: JSONArray()
            if ((0 until deleted.length()).none { deleted.getString(it) == id }) deleted.put(id)
            root.put("deletedSessionIds", deleted)
        }
    }

    /** Durable deletion queue: a failed/offline cloud deletion is retried after restart. */
    internal fun deletedSessionIds(): Set<String> = synchronized(lock) {
        read().optJSONArray("deletedSessionIds")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
    }

    internal fun acknowledgeCloudDeletions(ids: Set<String>) = synchronized(lock) {
        if (ids.isNotEmpty()) change { root ->
            val remaining = deletedSessionIds() - ids
            root.put("deletedSessionIds", JSONArray(remaining.toList()))
        }
    }

    private fun read(): JSONObject {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return JSONObject().put("schemaVersion", 1)
        try {
            if (file.baseFile.length() > 40_000_000) throw IOException("Gait history is too large to load.")
            val root = file.openRead().bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            if (root.getInt("schemaVersion") != 1) throw IOException("This gait history uses an unsupported format.")
            return root
        } catch (e: Exception) {
            throw IOException("Saved gait data could not be read. Existing data has been preserved.", e)
        }
    }

    private fun change(update: (JSONObject) -> Unit) {
        val root = read()
        update(root)
        val bytes = root.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > 40_000_000) throw IOException("Local gait history is full. Delete an old session before saving another.")
        val out = file.startWrite()
        try {
            out.write(bytes)
            file.finishWrite(out)
            changes.update { it + 1L }
        } catch (e: Exception) {
            file.failWrite(out)
            throw IOException("Could not save gait data. Previous data has been preserved.", e)
        }
    }

    private fun writeProfile(p: GaitProfile) = JSONObject().put("heightCm", p.heightCm).put("weightKg", p.weightKg)
        .put("sex", p.sex).put("age", p.age).put("passSteps", p.passSteps).put("targetStepsPerView", p.targetStepsPerView)
        .put("passDurationSeconds", p.passDurationSeconds).put("targetDurationSecondsPerView", p.targetDurationSecondsPerView)
    private fun readProfile(p: JSONObject) = GaitProfile(p.getInt("heightCm"), p.getInt("weightKg"), p.getString("sex"),
        p.getInt("age"), p.getInt("passSteps"), p.getInt("targetStepsPerView"),
        p.optInt("passDurationSeconds", 2), p.optInt("targetDurationSecondsPerView", 6))
    private fun writeSession(s: GaitSession): JSONObject = JSONObject().put("id", s.id).put("createdAt", s.createdAt)
        .put("profile", writeProfile(s.profile)).put("frontSteps", s.frontSteps).put("backSteps", s.backSteps)
        .put("acceptedFrames", s.acceptedFrames).put("rejectedFrames", s.rejectedFrames).put("manualLevel", s.manualLevel)
        .put("completed", s.completed).put("protocolVersion", s.protocolVersion)
        .put("measurementMethod", s.measurementMethod.name)
        .put("frontValidDurationMs", s.frontValidDurationMs).put("backValidDurationMs", s.backValidDurationMs)
        .put("frontCompletedPasses", s.frontCompletedPasses).put("backCompletedPasses", s.backCompletedPasses)
        .put("frontStepsPartial", s.frontStepsPartial).put("backStepsPartial", s.backStepsPartial)
        .put("acquisitionSource", s.acquisitionSource).put("sourceDurationMs", s.sourceDurationMs)
        .put("sourceMirrored", s.sourceMirrored)
        .put("videoViews", JSONArray().also { views -> GaitView.entries.filter { it in s.videoViews }.forEach { views.put(it.name) } })
        .put("samples", JSONArray().also { a -> s.samples.forEach { sample ->
            a.put(JSONObject().put("atMs", sample.atMs).put("view", sample.view.name).put("stepIndex", sample.stepIndex)
                .put("passIndex", sample.passIndex)
                .put("angles", JSONObject().also { angles -> sample.angles.forEach { (k, v) -> angles.put(k.name, v) } }))
        } })
    private fun readSession(s: JSONObject): GaitSession {
        val array = s.getJSONArray("samples")
        val samples = (0 until array.length()).map { i ->
            val p = array.getJSONObject(i)
            val a = p.getJSONObject("angles")
            GaitSample(p.getLong("atMs"), GaitView.valueOf(p.getString("view")),
                a.keys().asSequence().associate { key -> GaitMetric.valueOf(key) to a.getDouble(key) }, p.getInt("stepIndex"), p.optInt("passIndex", 0))
        }
        return GaitSession(s.getString("id"), s.getLong("createdAt"), readProfile(s.getJSONObject("profile")), samples,
            s.getInt("frontSteps"), s.getInt("backSteps"), s.getInt("acceptedFrames"), s.getInt("rejectedFrames"),
            s.getBoolean("manualLevel"), s.getBoolean("completed"), s.getInt("protocolVersion"),
            s.optLong("frontValidDurationMs", 0L), s.optLong("backValidDurationMs", 0L),
            s.optInt("frontCompletedPasses", 0), s.optInt("backCompletedPasses", 0),
            s.optBoolean("frontStepsPartial", false), s.optBoolean("backStepsPartial", false),
            s.optString("acquisitionSource", "LIVE"), s.optLong("sourceDurationMs", 0), s.optBoolean("sourceMirrored", false),
            s.optJSONArray("videoViews")?.let { views ->
                (0 until views.length()).map { GaitView.valueOf(views.getString(it)) }.toSet()
            }.orEmpty(),
            GaitMethod.valueOf(s.optString("measurementMethod", GaitMethod.MARKERS.name)))
    }

    companion object {
        private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
        private val changes = MutableStateFlow(0L)
        val dataChanges = changes.asStateFlow()
    }
}
