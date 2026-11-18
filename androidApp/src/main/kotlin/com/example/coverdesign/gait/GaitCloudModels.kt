package com.example.coverdesign.gait

import java.security.MessageDigest

/** Small list/trend index. Full numerical session data is stored in protected detail chunks. */
internal data class GaitCloudReport(
    val patientUid: String,
    val sessionId: String,
    val createdAt: Long,
    val measurementMethod: GaitMethod,
    val acquisitionSource: String,
    val protocolVersion: Int,
    val completed: Boolean,
    val manualLevel: Boolean,
    val comparisonGroupId: String,
    val typicalInclinations: Map<String, Double>,
    val sampleCounts: Map<String, Int>,
    val qualifiedMetrics: Set<String>,
    val acceptedFrames: Int,
    val rejectedFrames: Int,
    val frontValidDurationMs: Long,
    val backValidDurationMs: Long,
    val detailManifest: GaitSessionManifest? = null
) {
    fun toMap(): Map<String, Any> = linkedMapOf(
        "schemaVersion" to 1, "patientUid" to patientUid, "sessionId" to sessionId,
        "createdAt" to createdAt, "measurementMethod" to measurementMethod.name,
        "acquisitionSource" to acquisitionSource, "protocolVersion" to protocolVersion,
        "completed" to completed, "manualLevel" to manualLevel, "comparisonGroupId" to comparisonGroupId,
        "typicalInclinations" to typicalInclinations, "sampleCounts" to sampleCounts,
        "qualifiedMetrics" to qualifiedMetrics.sorted(), "acceptedFrames" to acceptedFrames,
        "rejectedFrames" to rejectedFrames, "frontValidDurationMs" to frontValidDurationMs,
        "backValidDurationMs" to backValidDurationMs
    ).also { map -> detailManifest?.let { map["detailManifest"] = it.toMap() } }

    companion object {
        val metricKeys = setOf("FRONT_SHOULDERS", "FRONT_PELVIS", "FRONT_KNEES", "BACK_SHOULDERS", "BACK_PELVIS", "BACK_SPINE")
        fun fromSession(uid: String, session: GaitSession): GaitCloudReport {
            require(validDocumentId(uid) && validDocumentId(session.id))
            val overview = GaitInsights.overview(session)
            fun GaitMetricKey.cloudKey() = "${view.name}_${metric.name}"
            val settings = GaitInsights.comparisonKey(session)
            // Length prefixes avoid collisions from arbitrary profile text. The fingerprint keeps
            // incompatible series apart without publishing the profile used to form the series.
            val canonical = listOf(settings.measurementMethod.name, settings.acquisitionSource,
                settings.protocolVersion, settings.manualLevel, settings.heightCm, settings.weightKg,
                settings.sex, settings.age, settings.passSetting, settings.targetSetting)
                .joinToString("") { value -> value.toString().let { "${it.length}:$it" } }
            return GaitCloudReport(uid, session.id, session.createdAt, session.measurementMethod,
                session.acquisitionSource, session.protocolVersion, session.completed, session.manualLevel,
                gaitDigest(canonical), overview.metrics.associate { it.key.cloudKey() to it.summary.meanAbsolute },
                overview.metrics.associate { it.key.cloudKey() to it.summary.count },
                overview.metrics.filter { it.qualifiesForTrend }.map { it.key.cloudKey() }.toSet(),
                session.acceptedFrames, session.rejectedFrames, session.frontValidDurationMs, session.backValidDurationMs)
                .also { require(it.isValid()) { "Invalid numerical gait report." } }
        }

        fun fromMap(data: Map<String, Any?>, owner: String, id: String): GaitCloudReport {
            fun exactLong(value: Any?): Long {
                val number = value as Number
                val d = number.toDouble()
                require(d.isFinite() && d >= 0 && d < Long.MAX_VALUE.toDouble() && d == number.toLong().toDouble())
                return number.toLong()
            }
            require(exactLong(data["schemaVersion"]) == 1L)
            fun long(key: String) = exactLong(data[key])
            fun int(key: String) = long(key).also { require(it in 0..Int.MAX_VALUE.toLong()) }.toInt()
            val values = (data["typicalInclinations"] as Map<*, *>).entries.associate { (k,v) -> k as String to (v as Number).toDouble() }
            val counts = (data["sampleCounts"] as Map<*, *>).entries.associate { (k,v) ->
                val n = exactLong(v); require(n in 1..30_000); k as String to n.toInt()
            }
            return GaitCloudReport(data["patientUid"] as String, data["sessionId"] as String,
                long("createdAt"), GaitMethod.valueOf(data["measurementMethod"] as String),
                data["acquisitionSource"] as String, int("protocolVersion"), data["completed"] as Boolean,
                data["manualLevel"] as Boolean, data["comparisonGroupId"] as String,
                values, counts, (data["qualifiedMetrics"] as List<*>).map { it as String }.toSet(),
                int("acceptedFrames"), int("rejectedFrames"), long("frontValidDurationMs"), long("backValidDurationMs"),
                data["detailManifest"]?.let { raw ->
                    @Suppress("UNCHECKED_CAST")
                    GaitSessionManifest.fromMap(raw as Map<String, Any?>)
                })
                .also { require(it.patientUid == owner && it.sessionId == id && it.isValid()) }
        }
    }

    private fun isValid(): Boolean = validDocumentId(patientUid) && validDocumentId(sessionId) && createdAt > 0 &&
        acquisitionSource in setOf("LIVE", "VIDEO") && protocolVersion in 1..100 &&
        comparisonGroupId.matches(Regex("[0-9a-f]{64}")) &&
        typicalInclinations.keys.all { it in metricKeys && (measurementMethod != GaitMethod.POSE || it != "BACK_SPINE") } &&
        typicalInclinations.values.all { it.isFinite() && it in 0.0..90.0 } &&
        sampleCounts.keys == typicalInclinations.keys && sampleCounts.values.all { it in 1..30_000 } &&
        qualifiedMetrics.all { it in typicalInclinations.keys } && acceptedFrames >= 0 && rejectedFrames >= 0 &&
        frontValidDurationMs >= 0 && backValidDurationMs >= 0
}

internal fun validDocumentId(value: String) = value.isNotBlank() && value.length <= 128 && '/' !in value && value !in setOf(".", "..")
internal fun gaitDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

internal data class GaitPatientLink(
    val patientUid: String, val doctorUid: String, val patientDisplayName: String,
    val doctorDisplayName: String, val active: Boolean
)
internal data class GaitSyncState(val userId: String? = null, val message: String = "Waiting to sync results.",
    val pending: Int = 0, val lastSyncedAt: Long? = null)
