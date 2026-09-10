package com.spectrum.spectrumsports

import java.nio.ByteBuffer
import java.util.UUID

/** Reads public KIDs from PSSH initialization metadata, never from the opaque CDM challenge. */
internal object WidevineKeyIds {
    private val widevine = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")

    fun fromPssh(data: ByteArray): Set<UUID> {
        val buffer = ByteBuffer.wrap(data)
        val ids = linkedSetOf<UUID>()
        while (buffer.hasRemaining()) {
            val start = buffer.position()
            require(buffer.remaining() >= 32) { "Truncated PSSH metadata" }
            val size = buffer.int
            require(size >= 32 && size <= data.size - start) { "Invalid PSSH size" }
            require(buffer.int == 0x70737368) { "Expected a PSSH atom" }
            val versionAndFlags = buffer.int
            val version = versionAndFlags ushr 24
            require(version in 0..1 && versionAndFlags and 0xFFFFFF == 0) {
                "Unsupported PSSH version or flags"
            }
            val systemId = UUID(buffer.long, buffer.long)
            val end = start + size
            val headerIds = linkedSetOf<UUID>()
            if (version == 1) {
                require(end - buffer.position() >= 8) { "Truncated PSSH KID count" }
                val count = buffer.int
                require(count >= 0 && count <= (end - buffer.position() - 4) / 16) {
                    "Invalid PSSH KID count"
                }
                repeat(count) { headerIds.add(UUID(buffer.long, buffer.long)) }
            }
            require(end - buffer.position() >= 4) { "Missing PSSH payload size" }
            val payloadSize = buffer.int
            require(payloadSize >= 0 && payloadSize == end - buffer.position()) {
                "Invalid PSSH payload size"
            }
            val payload = ByteArray(payloadSize).also { buffer.get(it) }
            if (systemId == widevine) {
                ids.addAll(headerIds)
                ids.addAll(fromWidevinePayload(payload))
            }
        }
        return ids
    }

    fun single(ids: Set<UUID>): UUID {
        require(ids.size == 1) {
            "SpatialGen requires exactly one KID per DRM request; found ${ids.size} in PSSH metadata"
        }
        return ids.single()
    }

    // WidevinePsshData is protobuf: repeated bytes key_id = 2. Skip all other fields.
    private fun fromWidevinePayload(data: ByteArray): Set<UUID> {
        val buffer = ByteBuffer.wrap(data)
        val ids = linkedSetOf<UUID>()
        while (buffer.hasRemaining()) {
            val tag = readVarint(buffer)
            require(tag ushr 3 != 0L) { "Invalid Widevine protobuf field" }
            val wireType = (tag and 7).toInt()
            if (tag ushr 3 == 2L) {
                require(wireType == 2 && readVarint(buffer) == 16L && buffer.remaining() >= 16) {
                    "Widevine KID must contain 16 bytes"
                }
                ids.add(UUID(buffer.long, buffer.long))
            } else {
                when (wireType) {
                    0 -> readVarint(buffer)
                    1 -> skip(buffer, 8)
                    2 -> skip(buffer, readVarint(buffer))
                    5 -> skip(buffer, 4)
                    else -> error("Unsupported Widevine protobuf wire type")
                }
            }
        }
        return ids
    }

    private fun skip(buffer: ByteBuffer, count: Long) {
        require(count >= 0 && count <= buffer.remaining().toLong()) { "Truncated Widevine metadata" }
        buffer.position(buffer.position() + count.toInt())
    }

    private fun readVarint(buffer: ByteBuffer): Long {
        var value = 0L
        for (shift in 0..63 step 7) {
            require(buffer.hasRemaining()) { "Truncated Widevine varint" }
            val byte = buffer.get().toInt() and 0xFF
            require(shift != 63 || byte <= 1) { "Invalid Widevine varint" }
            value = value or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) return value
        }
        error("Invalid Widevine varint")
    }
}
