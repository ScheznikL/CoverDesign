package com.example.coverdesign.gait

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.DigestOutputStream
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.GZIPOutputStream
import java.util.zip.Inflater

/** Bounded, versioned descriptor for a complete numerical session, not camera media. */
internal data class GaitSessionManifest(
    val formatVersion: Int = 1,
    val chunkCount: Int,
    val sha256: String,
    val compressedBytes: Int,
    val uncompressedBytes: Int
) {
    fun toMap(): Map<String, Any> {
        validate()
        return linkedMapOf("formatVersion" to formatVersion, "chunkCount" to chunkCount,
            "sha256" to sha256, "compressedBytes" to compressedBytes, "uncompressedBytes" to uncompressedBytes)
    }

    internal fun validate() {
        require(formatVersion == 1) { "Unsupported full session format." }
        require(chunkCount in 1..GaitCloudSessionCodec.MAX_CHUNKS) { "Invalid session chunk count." }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid session checksum." }
        require(compressedBytes in 18..GaitCloudSessionCodec.MAX_COMPRESSED_BYTES) { "Session payload is too large." }
        require(uncompressedBytes in 1..GaitCloudSessionCodec.MAX_UNCOMPRESSED_BYTES) { "Session data is too large." }
        require(chunkCount == (compressedBytes + GaitCloudSessionCodec.CHUNK_BYTES - 1) / GaitCloudSessionCodec.CHUNK_BYTES) {
            "Session chunk count does not match its size."
        }
    }

    companion object {
        private val fields = setOf("formatVersion", "chunkCount", "sha256", "compressedBytes", "uncompressedBytes")
        fun fromMap(data: Map<String, Any?>): GaitSessionManifest {
            require(data.keys == fields) { "Invalid full session descriptor fields." }
            fun exactInt(key: String): Int {
                val number = data[key] as? Number ?: error("Missing session descriptor number.")
                val value = number.toDouble()
                require(value.isFinite() && value in 0.0..Int.MAX_VALUE.toDouble() && value == number.toInt().toDouble()) {
                    "Invalid session descriptor number."
                }
                return number.toInt()
            }
            return GaitSessionManifest(exactInt("formatVersion"), exactInt("chunkCount"), data["sha256"] as String,
                exactInt("compressedBytes"), exactInt("uncompressedBytes")).also { it.validate() }
        }
    }
}

internal data class EncodedGaitSession(val manifest: GaitSessionManifest, val chunks: List<String>)

/**
 * Exact numerical transport used by both patient and doctor reports. No downsampling, coordinates,
 * photographs, video or palette enter this codec. Explicit field IDs keep binary format v1 stable.
 * Chunks stay well below Firestore's document limit; all decompression and allocation are bounded.
 */
internal object GaitCloudSessionCodec {
    const val CHUNK_BYTES = 96 * 1024
    const val MAX_CHUNK_CHARACTERS = 131_072
    const val MAX_CHUNKS = 64
    const val MAX_COMPRESSED_BYTES = CHUNK_BYTES * MAX_CHUNKS
    const val MAX_UNCOMPRESSED_BYTES = 12 * 1024 * 1024
    private const val MAX_SAMPLES = 30_000
    private const val MAGIC = 0x47414954 // GAIT
    private val sessionIdPattern = Regex("[A-Za-z0-9_-]{1,128}")

    fun encode(session: GaitSession): EncodedGaitSession {
        validate(session)
        val raw = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                writeSession(out, session)
            }
        }.toByteArray()
        require(raw.size <= MAX_UNCOMPRESSED_BYTES) { "Full session exceeds the supported size." }
        val compressed = ByteArrayOutputStream().also { buffer -> GZIPOutputStream(buffer).use { it.write(raw) } }.toByteArray()
        require(compressed.size <= MAX_COMPRESSED_BYTES) { "Compressed session exceeds the supported size." }
        val encoder = Base64.getEncoder()
        val chunks = (compressed.indices step CHUNK_BYTES).map { offset ->
            encoder.encodeToString(compressed.copyOfRange(offset, minOf(offset + CHUNK_BYTES, compressed.size)))
        }
        return EncodedGaitSession(GaitSessionManifest(1, chunks.size, digest(compressed), compressed.size, raw.size), chunks)
    }

    /** Exact raw-session identity without retaining the session or allocating a second payload. */
    fun fingerprint(session: GaitSession): String {
        validate(session)
        val checksum = MessageDigest.getInstance("SHA-256")
        DataOutputStream(DigestOutputStream(DiscardOutputStream, checksum)).use { writeSession(it, session) }
        return checksum.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private object DiscardOutputStream : OutputStream() {
        override fun write(value: Int) = Unit
        override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
    }

    private fun writeSession(out: DataOutputStream, session: GaitSession) {
        out.writeInt(MAGIC); out.writeInt(1)
        out.text(session.id, 128); out.writeLong(session.createdAt)
        val profile = session.profile
        out.writeInt(profile.heightCm); out.writeInt(profile.weightKg); out.text(profile.sex, 480)
        out.writeInt(profile.age); out.writeInt(profile.passSteps); out.writeInt(profile.targetStepsPerView)
        out.writeInt(profile.passDurationSeconds); out.writeInt(profile.targetDurationSecondsPerView)
        out.writeInt(session.frontSteps); out.writeInt(session.backSteps)
        out.writeInt(session.acceptedFrames); out.writeInt(session.rejectedFrames)
        out.writeBoolean(session.manualLevel); out.writeBoolean(session.completed); out.writeInt(session.protocolVersion)
        out.writeLong(session.frontValidDurationMs); out.writeLong(session.backValidDurationMs)
        out.writeInt(session.frontCompletedPasses); out.writeInt(session.backCompletedPasses)
        out.writeBoolean(session.frontStepsPartial); out.writeBoolean(session.backStepsPartial)
        out.text(session.acquisitionSource, 5); out.writeLong(session.sourceDurationMs); out.writeBoolean(session.sourceMirrored)
        out.writeByte(session.videoViews.fold(0) { mask, view -> mask or (1 shl viewId(view)) })
        out.writeByte(when (session.measurementMethod) { GaitMethod.MARKERS -> 0; GaitMethod.POSE -> 1 })
        out.writeInt(session.samples.size)
        session.samples.forEach { sample ->
            out.writeLong(sample.atMs); out.writeByte(viewId(sample.view)); out.writeInt(sample.stepIndex); out.writeInt(sample.passIndex)
            out.writeByte(sample.angles.size)
            // The map's insertion order is not measurement data. Preserve sample order and every Double bit.
            GaitMetric.entries.filter { it in sample.angles }.forEach { metric ->
                out.writeByte(metricId(metric)); out.writeDouble(sample.angles.getValue(metric))
            }
        }
    }

    fun decode(manifest: GaitSessionManifest, chunks: List<String>, expectedSessionId: String): GaitSession {
        manifest.validate()
        require(expectedSessionId.matches(sessionIdPattern)) { "Invalid requested session identity." }
        require(chunks.size == manifest.chunkCount) { "Full session chunks are missing." }
        val decoder = Base64.getDecoder()
        val encoder = Base64.getEncoder()
        val compressed = ByteArrayOutputStream(manifest.compressedBytes).also { buffer ->
            chunks.forEachIndexed { index, chunk ->
                val expectedBytes = minOf(CHUNK_BYTES, manifest.compressedBytes - index * CHUNK_BYTES)
                require(chunk.length == ((expectedBytes + 2) / 3) * 4 && chunk.length <= MAX_CHUNK_CHARACTERS) {
                    "Invalid full session chunk size."
                }
                val bytes = decoder.decode(chunk)
                require(bytes.size == expectedBytes && encoder.encodeToString(bytes) == chunk) { "Invalid full session chunk encoding." }
                buffer.write(bytes)
            }
        }.toByteArray()
        require(compressed.size == manifest.compressedBytes && digest(compressed) == manifest.sha256) { "Full session checksum mismatch." }
        val raw = inflate(compressed, manifest.uncompressedBytes)
        val input = DataInputStream(ByteArrayInputStream(raw))
        require(input.readInt() == MAGIC && input.readInt() == 1) { "Unknown full session format." }
        val id = input.text(128)
        require(id == expectedSessionId) { "Full session identity does not match the report." }
        val createdAt = input.readLong()
        val profile = GaitProfile(input.readInt(), input.readInt(), input.text(480), input.readInt(), input.readInt(),
            input.readInt(), input.readInt(), input.readInt())
        val frontSteps = input.readInt(); val backSteps = input.readInt()
        val accepted = input.readInt(); val rejected = input.readInt()
        val manual = input.strictBoolean(); val completed = input.strictBoolean(); val protocol = input.readInt()
        val frontDuration = input.readLong(); val backDuration = input.readLong()
        val frontPasses = input.readInt(); val backPasses = input.readInt()
        val frontPartial = input.strictBoolean(); val backPartial = input.strictBoolean()
        val source = input.text(5); val sourceDuration = input.readLong(); val mirrored = input.strictBoolean()
        val viewsMask = input.readUnsignedByte()
        require(viewsMask in 0..3) { "Unknown recorded views." }
        val videoViews = GaitView.entries.filter { viewsMask and (1 shl viewId(it)) != 0 }.toSet()
        val method = when (input.readUnsignedByte()) { 0 -> GaitMethod.MARKERS; 1 -> GaitMethod.POSE; else -> error("Unknown measurement method.") }
        val count = input.readInt()
        require(count in 0..MAX_SAMPLES) { "Invalid full session sample count." }
        val samples = List(count) {
            val at = input.readLong(); val view = view(input.readUnsignedByte()); val step = input.readInt(); val pass = input.readInt()
            val angleCount = input.readUnsignedByte()
            require(angleCount <= expectedGaitMetrics(view, method).size) { "Invalid sample angle count." }
            val angles = linkedMapOf<GaitMetric, Double>()
            repeat(angleCount) {
                val key = metric(input.readUnsignedByte())
                require(key !in angles && key in expectedGaitMetrics(view, method)) { "Invalid or duplicate sample angle." }
                angles[key] = input.readDouble()
            }
            GaitSample(at, view, angles, step, pass)
        }
        require(input.read() == -1) { "Trailing data in full session." }
        return GaitSession(id, createdAt, profile, samples, frontSteps, backSteps, accepted, rejected, manual, completed,
            protocol, frontDuration, backDuration, frontPasses, backPasses, frontPartial, backPartial, source,
            sourceDuration, mirrored, videoViews, method).also(::validate)
    }

    private fun validate(session: GaitSession) {
        require(session.id.matches(sessionIdPattern) && session.createdAt > 0) { "Invalid full session identity." }
        require(session.profile.validationError() == null && session.profile.sex.length <= 120 &&
            session.profile.sex.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == session.profile.sex) { "Invalid saved profile." }
        require(session.protocolVersion in 1..100 && session.acquisitionSource in setOf("LIVE", "VIDEO")) { "Unknown acquisition protocol." }
        require(listOf(session.frontSteps, session.backSteps, session.acceptedFrames, session.rejectedFrames,
            session.frontCompletedPasses, session.backCompletedPasses).all { it >= 0 }) { "Negative session counts." }
        require(session.frontValidDurationMs >= 0 && session.backValidDurationMs >= 0 && session.sourceDurationMs >= 0) { "Negative session duration." }
        require(session.samples.size <= MAX_SAMPLES) { "Too many samples in full session." }
        session.samples.forEach { sample ->
            require(sample.atMs >= 0 && sample.stepIndex >= 0 && sample.passIndex >= 0) { "Negative sample metadata." }
            require(sample.angles.keys.all { it in expectedGaitMetrics(sample.view, session.measurementMethod) }) { "Unsupported sample angles." }
            require(sample.angles.values.all { it.isFinite() && it in -90.0..90.0 }) { "Invalid sample angle." }
        }
    }

    /** Only a single GZIP member without optional header fields is accepted; Java GZIPInputStream tolerates trailing garbage. */
    private fun inflate(compressed: ByteArray, expectedSize: Int): ByteArray {
        require(compressed.size >= 18 && (compressed[0].toInt() and 255) == 31 && (compressed[1].toInt() and 255) == 139 &&
            compressed[2].toInt() == 8 && compressed[3].toInt() == 0) { "Invalid session compression header." }
        val inflater = Inflater(true)
        try {
            inflater.setInput(compressed, 10, compressed.size - 10)
            val out = ByteArrayOutputStream(minOf(expectedSize, 64 * 1024))
            val block = ByteArray(8192)
            while (!inflater.finished()) {
                val count = inflater.inflate(block)
                require(count > 0 || inflater.finished()) { "Incomplete session compression stream." }
                require(count <= expectedSize - out.size() && count <= MAX_UNCOMPRESSED_BYTES - out.size()) { "Session decompression limit exceeded." }
                out.write(block, 0, count)
            }
            val trailer = 10 + inflater.totalIn
            require(trailer + 8 == compressed.size) { "Trailing data in compressed session." }
            val raw = out.toByteArray()
            require(raw.size == expectedSize && unsigned32(compressed, trailer + 4) == raw.size.toLong()) { "Session decompression size mismatch." }
            require(unsigned32(compressed, trailer) == CRC32().also { it.update(raw) }.value) { "Session compression checksum mismatch." }
            return raw
        } finally { inflater.end() }
    }

    private fun unsigned32(bytes: ByteArray, offset: Int): Long = (0..3).fold(0L) { value, index ->
        value or ((bytes[offset + index].toLong() and 255L) shl (8 * index))
    }
    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun DataOutputStream.text(value: String, maximumBytes: Int) {
        val encoded = value.toByteArray(Charsets.UTF_8)
        require(encoded.size <= maximumBytes) { "Session text is too long." }
        writeInt(encoded.size); write(encoded)
    }
    private fun DataInputStream.text(maximumBytes: Int): String {
        val length = readInt()
        require(length in 0..maximumBytes && length <= available()) { "Invalid session text length." }
        val bytes = ByteArray(length); readFully(bytes)
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }
    private fun DataInputStream.strictBoolean(): Boolean = when (readUnsignedByte()) {
        0 -> false; 1 -> true; else -> error("Invalid session boolean.")
    }
    private fun viewId(view: GaitView) = when (view) { GaitView.FRONT -> 0; GaitView.BACK -> 1 }
    private fun view(id: Int): GaitView = when (id) { 0 -> GaitView.FRONT; 1 -> GaitView.BACK; else -> error("Unknown sample view.") }
    private fun metricId(metric: GaitMetric) = when (metric) {
        GaitMetric.SHOULDERS -> 0; GaitMetric.PELVIS -> 1; GaitMetric.KNEES -> 2; GaitMetric.SPINE -> 3
    }
    private fun metric(id: Int): GaitMetric = when (id) {
        0 -> GaitMetric.SHOULDERS; 1 -> GaitMetric.PELVIS; 2 -> GaitMetric.KNEES; 3 -> GaitMetric.SPINE; else -> error("Unknown sample metric.")
    }
}
