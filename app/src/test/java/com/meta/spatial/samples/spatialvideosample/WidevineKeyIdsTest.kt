package com.spectrum.spectrumsports

import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class WidevineKeyIdsTest {
    private val sampleId = UUID.fromString("302f80dd-411e-4886-bca5-bb1f8018a024")
    private val widevine = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")
    // Public PSSH from both the sample's video-40 and audio playlists (not a license/challenge).
    private val sample = Base64.getDecoder().decode(
        "AAAAOHBzc2gAAAAA7e+LqXnWSs6jyCfc1R0h7QAAABgSEDAvgN1BHkiGvKW7H4AYoCRI49yVmwY="
    )

    @Test fun extractsActualSampleKidFromVersionZeroPayload() {
        assertEquals(sampleId, WidevineKeyIds.single(WidevineKeyIds.fromPssh(sample)))
    }

    @Test fun extractsVersionOneHeaderKid() {
        assertEquals(setOf(sampleId), WidevineKeyIds.fromPssh(atom(1, byteArrayOf(), listOf(sampleId))))
    }

    @Test fun deduplicatesKidsAcrossAtomsAndPayloads() {
        val versionOne = atom(1, sample.copyOfRange(32, sample.size), listOf(sampleId))
        assertEquals(setOf(sampleId), WidevineKeyIds.fromPssh(sample + versionOne))
    }

    @Test fun skipsOtherDrmSystems() {
        val other = atom(0, byteArrayOf(0xFF.toByte()), system = UUID(1, 2))
        assertEquals(setOf(sampleId), WidevineKeyIds.fromPssh(other + sample))
    }

    @Test fun rejectsMissingAndAmbiguousKids() {
        assertThrows(IllegalArgumentException::class.java) { WidevineKeyIds.single(emptySet()) }
        assertThrows(IllegalArgumentException::class.java) {
            WidevineKeyIds.single(WidevineKeyIds.fromPssh(atom(1, byteArrayOf(), listOf(sampleId, UUID(1, 2)))))
        }
    }

    @Test fun rejectsEveryTruncatedSample() {
        for (length in 1 until sample.size) {
            assertThrows("length=$length", IllegalArgumentException::class.java) {
                WidevineKeyIds.fromPssh(sample.copyOf(length))
            }
        }
    }

    @Test fun rejectsMalformedLengthsAndKids() {
        val malformed = listOf(
            atom(0, byteArrayOf(0x12, 0x10, 1)),
            atom(0, byteArrayOf(0x12, 0x01, 1)),
            atom(0, byteArrayOf(0x1A, 0x7F, 1)),
            atom(0, byteArrayOf(0x80.toByte())),
            atom(0, ByteArray(11) { 0x80.toByte() }),
            atom(1, byteArrayOf(), listOf(sampleId)).also { ByteBuffer.wrap(it).putInt(28, Int.MAX_VALUE) },
            sample.copyOf().also { ByteBuffer.wrap(it).putInt(28, Int.MAX_VALUE) },
        )
        malformed.forEach { bytes ->
            assertThrows(IllegalArgumentException::class.java) { WidevineKeyIds.fromPssh(bytes) }
        }
    }

    @Test fun skipsUnknownProtobufFields() {
        val unknown = byteArrayOf(0x08, 0x01, 0x1A, 0x03, 1, 2, 3, 0x2D, 1, 2, 3, 4)
        val key = ByteBuffer.allocate(18).put(0x12).put(16)
            .putLong(sampleId.mostSignificantBits).putLong(sampleId.leastSignificantBits).array()
        assertEquals(setOf(sampleId), WidevineKeyIds.fromPssh(atom(0, unknown + key)))
    }

    private fun atom(
        version: Int,
        payload: ByteArray,
        kids: List<UUID> = emptyList(),
        system: UUID = widevine,
    ): ByteArray {
        val size = 32 + payload.size + if (version == 1) 4 + kids.size * 16 else 0
        return ByteBuffer.allocate(size).apply {
            putInt(size); putInt(0x70737368); putInt(version shl 24)
            putLong(system.mostSignificantBits); putLong(system.leastSignificantBits)
            if (version == 1) {
                putInt(kids.size)
                kids.forEach { putLong(it.mostSignificantBits); putLong(it.leastSignificantBits) }
            }
            putInt(payload.size); put(payload)
        }.array()
    }
}
