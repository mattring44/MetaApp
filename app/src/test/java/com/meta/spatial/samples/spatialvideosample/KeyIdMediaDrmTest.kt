package com.spectrum.spectrumsports

import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.exoplayer.drm.ExoMediaDrm
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap
import org.junit.Assert.*
import org.junit.Test

class KeyIdMediaDrmTest {
    @Test fun keepsConcurrentRequestsAndRenewalsAssociatedWithTheirSession() {
        val mapping = Collections.synchronizedMap(WeakHashMap<ExoMediaDrm.KeyRequest, UUID>())
        val challenge = byteArrayOf(0, 127, -128, -1)
        val delegate = fakeDrm { ExoMediaDrm.KeyRequest(challenge, "https://ignored.test") }
        val drm = KeyIdMediaDrm(delegate, mapping)
        val first = UUID(1, 2)
        val second = UUID(3, 4)
        val audio = drm.getKeyRequest(byteArrayOf(1), scheme(first), 1, null)
        val video = drm.getKeyRequest(byteArrayOf(2), scheme(second), 1, null)
        val renewal = drm.getKeyRequest(byteArrayOf(1), null, 1, null)
        assertEquals(first, mapping[audio])
        assertEquals(second, mapping[video])
        assertEquals(first, mapping[renewal])
        assertSame(challenge, audio.data)
        // The original requests are still associated when the network callback retries them.
        assertEquals(first, mapping[audio])
        assertEquals(second, mapping[video])
    }

    @Test fun rotationChangesOnlyTheNewRequestAndCloseClearsRenewalMetadata() {
        val mapping = mutableMapOf<ExoMediaDrm.KeyRequest, UUID>()
        val drm = KeyIdMediaDrm(fakeDrm { ExoMediaDrm.KeyRequest(byteArrayOf(1), "") }, mapping)
        val old = drm.getKeyRequest(byteArrayOf(1), scheme(UUID(1, 2)), 1, null)
        val rotated = drm.getKeyRequest(byteArrayOf(1), scheme(UUID(3, 4)), 1, null)
        assertEquals(UUID(1, 2), mapping[old])
        assertEquals(UUID(3, 4), mapping[rotated])
        drm.closeSession(byteArrayOf(1))
        assertThrows(IllegalArgumentException::class.java) {
            drm.getKeyRequest(byteArrayOf(1), null, 1, null)
        }
    }

    @Test fun rejectsAmbiguousMetadataBeforeRequestingAChallenge() {
        val drm = KeyIdMediaDrm(fakeDrm { error("Must not request a challenge") }, mutableMapOf())
        assertThrows(IllegalArgumentException::class.java) {
            drm.getKeyRequest(byteArrayOf(1), (scheme(UUID(1, 2)) + scheme(UUID(3, 4))).toMutableList(), 1, null)
        }
    }

    private fun scheme(kid: UUID): MutableList<DrmInitData.SchemeData> {
        val pssh = ByteBuffer.allocate(52).apply {
            putInt(52); putInt(0x70737368); putInt(1 shl 24)
            putLong(C.WIDEVINE_UUID.mostSignificantBits); putLong(C.WIDEVINE_UUID.leastSignificantBits)
            putInt(1); putLong(kid.mostSignificantBits); putLong(kid.leastSignificantBits); putInt(0)
        }.array()
        return mutableListOf(DrmInitData.SchemeData(C.WIDEVINE_UUID, "video/mp4", pssh))
    }

    private fun fakeDrm(request: () -> ExoMediaDrm.KeyRequest): ExoMediaDrm =
        Proxy.newProxyInstance(ExoMediaDrm::class.java.classLoader, arrayOf(ExoMediaDrm::class.java)) { _, method, _ ->
            when (method.name) {
                "getKeyRequest" -> request()
                "closeSession" -> null
                else -> error("Unexpected DRM method: ${method.name}")
            }
        } as ExoMediaDrm
}
