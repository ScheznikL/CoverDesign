package com.example.coverdesign.gait

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import java.util.Random
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.*
import org.junit.Test

/** No Android/Firebase runtime or real patient data is used by these transport checks. */
class GaitCloudSessionCodecTest {
    private fun session(method: GaitMethod = GaitMethod.MARKERS, source: String = "LIVE"): GaitSession {
        val samples = GaitView.entries.flatMap { view -> (0..3).map { index ->
            GaitSample(891_234_567L + index * 203L, view,
                expectedGaitMetrics(view, method).associateWith { metric ->
                    if (index == 0) -0.0 else (index * 1.23456789012345 - metric.ordinal * .91) * if (index == 2) -1 else 1
                }, index + 1, index / 2 + 1)
        } }
        return GaitSession("full-report-test", 1_700_000_000_123L,
            GaitProfile(183, 76, "Prefer not to say", 32, 6, 18, 3, 9), samples,
            7, 5, samples.size, 11, true, false, if (method == GaitMethod.POSE) 9 else if (source == "VIDEO") 8 else 7,
            6_345, 4_678, 3, 2, true, false, source, if (source == "VIDEO") 44_555 else 0,
            source == "VIDEO", if (source == "VIDEO") GaitView.entries.toSet() else emptySet(), method)
    }

    private fun roundTrip(session: GaitSession): GaitSession = GaitCloudSessionCodec.encode(session).let {
        GaitCloudSessionCodec.decode(it.manifest, it.chunks, session.id)
    }
    private fun assertRejected(action: () -> Any?) = assertTrue("Malformed full report must be rejected", runCatching(action).isFailure)
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun compressed(encoded: EncodedGaitSession): ByteArray = ByteArrayOutputStream().also { output ->
        encoded.chunks.forEach { output.write(Base64.getDecoder().decode(it)) }
    }.toByteArray()
    private fun raw(encoded: EncodedGaitSession): ByteArray = GZIPInputStream(ByteArrayInputStream(compressed(encoded))).use { it.readBytes() }
    private fun wrapCompressed(bytes: ByteArray, rawSize: Int): EncodedGaitSession {
        val chunks = (bytes.indices step GaitCloudSessionCodec.CHUNK_BYTES).map { offset ->
            Base64.getEncoder().encodeToString(bytes.copyOfRange(offset, minOf(bytes.size, offset + GaitCloudSessionCodec.CHUNK_BYTES)))
        }
        return EncodedGaitSession(GaitSessionManifest(1, chunks.size, hash(bytes), bytes.size, rawSize), chunks)
    }
    private fun wrapRaw(bytes: ByteArray): EncodedGaitSession {
        val compressed = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()
        return wrapCompressed(compressed, bytes.size)
    }
    private fun decode(encoded: EncodedGaitSession) = GaitCloudSessionCodec.decode(encoded.manifest, encoded.chunks, session().id)

    @Test fun fullMarkerAndPoseSessionsKeepEverySampleProfileAndAcquisitionFieldExactly() {
        for (method in GaitMethod.entries) for (source in listOf("LIVE", "VIDEO")) {
            val original = session(method, source)
            val decoded = roundTrip(original)
            assertEquals(original, decoded)
            original.samples.zip(decoded.samples).forEach { (left, right) ->
                left.angles.forEach { (metric, value) -> assertEquals(value.toRawBits(), right.angles.getValue(metric).toRawBits()) }
            }
        }
    }

    @Test fun partialSingleViewAndEmptySessionsRemainPartialWithoutSynthesizedMeasurements() {
        val full = session(GaitMethod.POSE)
        val front = full.copy(samples = full.samples.filter { it.view == GaitView.FRONT }, backSteps = 0,
            backValidDurationMs = 0, backCompletedPasses = 0, videoViews = setOf(GaitView.FRONT))
        assertEquals(front, roundTrip(front))
        val empty = front.copy(samples = emptyList(), acceptedFrames = 0, frontValidDurationMs = 0)
        assertEquals(empty, roundTrip(empty))
        assertFalse(roundTrip(front).samples.any { GaitMetric.SPINE in it.angles })
    }

    @Test fun binaryEncodingIsStableAcrossMapInsertionOrderAndPreservesSampleOrder() {
        val original = session()
        val reorderedMaps = original.copy(samples = original.samples.map { it.copy(angles = it.angles.entries.reversed().associate { entry -> entry.key to entry.value }) })
        assertEquals(GaitCloudSessionCodec.encode(original), GaitCloudSessionCodec.encode(reorderedMaps))
        val reversedSamples = original.copy(samples = original.samples.reversed())
        assertEquals(reversedSamples, roundTrip(reversedSamples))
        assertNotEquals(GaitCloudSessionCodec.encode(original).manifest.sha256, GaitCloudSessionCodec.encode(reversedSamples).manifest.sha256)
    }

    @Test fun streamingFingerprintCoversEveryProfileMetadataAndSignedSampleValue() {
        val base = session()
        val identity = GaitCloudSessionCodec.fingerprint(base)
        assertEquals(hash(raw(GaitCloudSessionCodec.encode(base))), identity)
        val reversedMaps = base.copy(samples = base.samples.map { sample ->
            sample.copy(angles = sample.angles.entries.reversed().associate { it.key to it.value })
        })
        assertEquals(identity, GaitCloudSessionCodec.fingerprint(reversedMaps))
        val changedProfile = listOf(
            base.profile.copy(heightCm = 184), base.profile.copy(weightKg = 77),
            base.profile.copy(sex = "Female"), base.profile.copy(age = 33),
            base.profile.copy(passSteps = 7), base.profile.copy(targetStepsPerView = 19),
            base.profile.copy(passDurationSeconds = 2), base.profile.copy(targetDurationSecondsPerView = 10)
        ).map { base.copy(profile = it) }
        val changed = changedProfile + listOf(
            base.copy(id = "another-session"), base.copy(createdAt = base.createdAt + 1),
            base.copy(frontSteps = 8), base.copy(backSteps = 6),
            base.copy(acceptedFrames = 9), base.copy(rejectedFrames = 12),
            base.copy(manualLevel = false), base.copy(completed = true), base.copy(protocolVersion = 6),
            base.copy(frontValidDurationMs = 6_346), base.copy(backValidDurationMs = 4_679),
            base.copy(frontCompletedPasses = 4), base.copy(backCompletedPasses = 3),
            base.copy(frontStepsPartial = false), base.copy(backStepsPartial = true),
            base.copy(acquisitionSource = "VIDEO"), base.copy(sourceDurationMs = 500),
            base.copy(sourceMirrored = true), base.copy(videoViews = setOf(GaitView.FRONT)),
            base.copy(samples = base.samples.reversed()),
            base.copy(samples = listOf(base.samples.first().copy(atMs = 42)) + base.samples.drop(1)),
            base.copy(samples = listOf(base.samples.first().copy(stepIndex = 99)) + base.samples.drop(1)),
            base.copy(samples = listOf(base.samples.first().copy(passIndex = 99)) + base.samples.drop(1)),
            base.copy(samples = listOf(base.samples.first().copy(angles = base.samples.first().angles + (GaitMetric.SHOULDERS to 0.0))) + base.samples.drop(1)),
            base.copy(samples = base.samples.drop(1))
        )
        changed.forEach { assertNotEquals(identity, GaitCloudSessionCodec.fingerprint(it)) }
        val pose = session(GaitMethod.POSE)
        assertNotEquals(GaitCloudSessionCodec.fingerprint(pose), GaitCloudSessionCodec.fingerprint(pose.copy(measurementMethod = GaitMethod.MARKERS)))
    }

    @Test fun manifestStrictlyRoundTripsFirestoreLongNumbersAndRejectsUnexpectedFields() {
        val manifest = GaitCloudSessionCodec.encode(session()).manifest
        val data = manifest.toMap().mapValues { (_, value) -> if (value is Int) value.toLong() else value }
        assertEquals(manifest, GaitSessionManifest.fromMap(data))
        assertRejected { GaitSessionManifest.fromMap(data + ("formatVersion" to 2L)) }
        assertRejected { GaitSessionManifest.fromMap(data + ("chunkCount" to 1.5)) }
        assertRejected { GaitSessionManifest.fromMap(data + ("compressedBytes" to Double.NaN)) }
        assertRejected { GaitSessionManifest.fromMap(data + ("uncompressedBytes" to Long.MAX_VALUE)) }
        assertRejected { GaitSessionManifest.fromMap(data + ("sha256" to "a".repeat(63))) }
        assertRejected { GaitSessionManifest.fromMap(data + ("unexpected" to true)) }
        assertRejected { GaitSessionManifest.fromMap(data - "sha256") }
    }

    @Test fun maximumLengthRecordingUsesBoundedChunksWithoutLosingOrDownsamplingValues() {
        val random = Random(71)
        val base = session()
        val samples = List(30_000) { index ->
            val view = if (index % 2 == 0) GaitView.FRONT else GaitView.BACK
            GaitSample(index * 97L, view, expectedGaitMetrics(view).associateWith { random.nextDouble() * 180 - 90 },
                index / 5, index / 70)
        }
        val original = base.copy(samples = samples, acceptedFrames = samples.size)
        val encoded = GaitCloudSessionCodec.encode(original)
        assertTrue("Random double samples should exercise multiple Firestore documents", encoded.chunks.size > 1)
        assertTrue(encoded.chunks.size <= GaitCloudSessionCodec.MAX_CHUNKS)
        assertTrue(encoded.chunks.all { it.length <= GaitCloudSessionCodec.MAX_CHUNK_CHARACTERS })
        assertEquals(original, decode(encoded))
        assertRejected { GaitCloudSessionCodec.decode(encoded.manifest, encoded.chunks.dropLast(1), original.id) }
        assertRejected { GaitCloudSessionCodec.decode(encoded.manifest, encoded.chunks.reversed(), original.id) }
        assertRejected { GaitCloudSessionCodec.decode(encoded.manifest, encoded.chunks.toMutableList().also { it[1] = it[0] }, original.id) }
        assertRejected { GaitCloudSessionCodec.encode(original.copy(samples = samples + samples.first())) }
    }

    @Test fun checksumCorruptionAndNoncanonicalOrOversizedBase64AreRejected() {
        val encoded = GaitCloudSessionCodec.encode(session())
        val changed = compressed(encoded).also { it[12] = (it[12].toInt() xor 1).toByte() }
        val changedChunks = wrapCompressed(changed, encoded.manifest.uncompressedBytes).chunks
        assertRejected { GaitCloudSessionCodec.decode(encoded.manifest, changedChunks, session().id) }
        assertRejected { decode(encoded.copy(chunks = encoded.chunks.map { it + "\n" })) }
        assertRejected { decode(encoded.copy(chunks = listOf("A".repeat(GaitCloudSessionCodec.MAX_CHUNK_CHARACTERS + 4)))) }
        assertRejected { decode(encoded.copy(manifest = encoded.manifest.copy(chunkCount = 65))) }
        assertRejected { decode(encoded.copy(manifest = encoded.manifest.copy(compressedBytes = GaitCloudSessionCodec.MAX_COMPRESSED_BYTES + 1))) }
    }

    @Test fun requestedSessionMustMatchEmbeddedIdentity() {
        val encoded = GaitCloudSessionCodec.encode(session())
        assertRejected { GaitCloudSessionCodec.decode(encoded.manifest, encoded.chunks, "different-session") }
        assertRejected { GaitCloudSessionCodec.decode(encoded.manifest, encoded.chunks, "../session") }
        assertRejected { GaitCloudSessionCodec.encode(session().copy(id = "bad session id")) }
    }

    @Test fun gzipTrailingBytesMembersChecksumAndSizeMismatchAreRejectedEvenWithFreshManifestHashes() {
        val encoded = GaitCloudSessionCodec.encode(session())
        val compressed = compressed(encoded)
        assertRejected { decode(wrapCompressed(compressed + byteArrayOf(1, 2, 3), encoded.manifest.uncompressedBytes)) }
        assertRejected { decode(wrapCompressed(compressed + compressed, encoded.manifest.uncompressedBytes * 2)) }
        assertRejected { decode(wrapCompressed(compressed.copyOf().also { it[it.size - 8] = (it[it.size - 8].toInt() xor 1).toByte() }, encoded.manifest.uncompressedBytes)) }
        assertRejected { decode(wrapCompressed(compressed.copyOf().also { it[it.size - 4] = (it[it.size - 4].toInt() xor 1).toByte() }, encoded.manifest.uncompressedBytes)) }
        assertRejected { decode(wrapCompressed(compressed.copyOf().also { it[3] = 8 }, encoded.manifest.uncompressedBytes)) }
        assertRejected { decode(encoded.copy(manifest = encoded.manifest.copy(uncompressedBytes = encoded.manifest.uncompressedBytes - 1))) }
    }

    @Test fun decompressionBombIsStoppedBeforeExceedingAdvertisedOrGlobalSize() {
        val limit = GaitCloudSessionCodec.MAX_UNCOMPRESSED_BYTES
        val big = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { gzip ->
            val block = ByteArray(8192)
            repeat(limit / block.size) { gzip.write(block) }; gzip.write(0)
        } }.toByteArray()
        val encoded = wrapCompressed(big, limit)
        assertRejected { decode(encoded) }
        assertRejected { decode(encoded.copy(manifest = encoded.manifest.copy(uncompressedBytes = limit + 1))) }
    }

    @Test fun unknownBinaryVersionTrailingDataAndInvalidCountsAreRejected() {
        val encoded = GaitCloudSessionCodec.encode(session())
        val raw = raw(encoded)
        assertRejected { decode(wrapRaw(raw.copyOf().also { ByteBuffer.wrap(it).putInt(4, 2) })) }
        assertRejected { decode(wrapRaw(raw + byteArrayOf(0))) }
        val countOffset = sampleCountOffset(raw)
        assertRejected { decode(wrapRaw(raw.copyOf().also { ByteBuffer.wrap(it).putInt(countOffset, 30_001) })) }
        assertRejected { decode(wrapRaw(raw.copyOf().also { ByteBuffer.wrap(it).putInt(countOffset, -1) })) }
        assertRejected { decode(wrapRaw(raw.copyOf().also { it[countOffset - 1] = 99 })) }
    }

    @Test fun malformedSampleViewsMetricsDuplicateKeysAndNonfiniteAnglesAreRejected() {
        val raw = raw(GaitCloudSessionCodec.encode(session()))
        val first = sampleCountOffset(raw) + 4
        assertRejected { decode(wrapRaw(raw.copyOf().also { it[first + 8] = 99 })) }
        assertRejected { decode(wrapRaw(raw.copyOf().also { it[first + 18] = 99 })) }
        assertRejected { decode(wrapRaw(raw.copyOf().also { it[first + 27] = it[first + 18] })) }
        assertRejected { decode(wrapRaw(raw.copyOf().also { ByteBuffer.wrap(it).putDouble(first + 19, Double.NaN) })) }
        assertRejected { decode(wrapRaw(raw.copyOf().also { ByteBuffer.wrap(it).putLong(first, -1) })) }
        assertRejected { decode(wrapRaw(raw.copyOf().also { it[first + 17] = 4 })) }
    }

    @Test fun impossibleProfilesAndUnsupportedPoseAnglesCannotBeUploaded() {
        val base = session()
        assertRejected { GaitCloudSessionCodec.encode(base.copy(profile = base.profile.copy(heightCm = 10))) }
        assertRejected { GaitCloudSessionCodec.encode(base.copy(profile = base.profile.copy(sex = "x".repeat(121)))) }
        assertRejected { GaitCloudSessionCodec.encode(base.copy(frontValidDurationMs = -1)) }
        assertRejected { GaitCloudSessionCodec.encode(base.copy(acceptedFrames = -1)) }
        assertRejected { GaitCloudSessionCodec.encode(base.copy(protocolVersion = 101)) }
        assertRejected { GaitCloudSessionCodec.encode(base.copy(samples = listOf(base.samples.first().copy(angles = mapOf(GaitMetric.KNEES to Double.POSITIVE_INFINITY))))) }
        val pose = session(GaitMethod.POSE)
        val back = pose.samples.first { it.view == GaitView.BACK }
        assertRejected { GaitCloudSessionCodec.encode(pose.copy(samples = listOf(back.copy(angles = back.angles + (GaitMetric.SPINE to 1.0))))) }
    }

    private fun sampleCountOffset(raw: ByteArray): Int {
        val input = DataInputStream(ByteArrayInputStream(raw))
        fun skipText() { val n = input.readInt(); check(input.skipBytes(n) == n) }
        input.readInt(); input.readInt(); skipText(); input.readLong()
        input.readInt(); input.readInt(); skipText(); repeat(5) { input.readInt() }
        repeat(4) { input.readInt() }; repeat(2) { input.readBoolean() }; input.readInt()
        repeat(2) { input.readLong() }; repeat(2) { input.readInt() }; repeat(2) { input.readBoolean() }
        skipText(); input.readLong(); input.readBoolean(); input.readUnsignedByte(); input.readUnsignedByte()
        return raw.size - input.available()
    }
}
