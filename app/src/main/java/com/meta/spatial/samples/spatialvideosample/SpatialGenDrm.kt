package com.spectrum.spectrumsports

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.drm.MediaDrmCallback
import androidx.media3.exoplayer.drm.MediaDrmCallbackException
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.util.Base64
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap

@UnstableApi
internal object SpatialGenDrm {
    fun createSessionManager(): DefaultDrmSessionManager {
        // The request identity keeps concurrent audio/video sessions, retries and key changes paired.
        val requestKeyIds = Collections.synchronizedMap(WeakHashMap<ExoMediaDrm.KeyRequest, UUID>())
        val client = SpatialGenLicenseClient(
            SpatialGenPlaybackConfig.API_KEY,
            SpatialGenPlaybackConfig.LICENSE_URL,
        )
        val provisioning = HttpMediaDrmCallback(
            null,
            DefaultHttpDataSource.Factory().setConnectTimeoutMs(15_000).setReadTimeoutMs(30_000),
        )
        val callback = object : MediaDrmCallback {
            override fun executeProvisionRequest(uuid: UUID, request: ExoMediaDrm.ProvisionRequest) =
                // Provisioning belongs to the device CDM's endpoint, without the SpatialGen app key.
                provisioning.executeProvisionRequest(uuid, request)

            override fun executeKeyRequest(uuid: UUID, request: ExoMediaDrm.KeyRequest): ByteArray {
                try {
                    val keyId = requestKeyIds[request]
                        ?: throw SpatialGenLicenseException("No PSSH KID associated with this CDM request")
                    return client.acquireLicense(keyId, request.data)
                } catch (e: Exception) {
                    // Omit sensitive request headers/body from the exception's diagnostic DataSpec.
                    val uri = Uri.parse(SpatialGenPlaybackConfig.LICENSE_URL)
                    throw MediaDrmCallbackException(
                        DataSpec.Builder().setUri(uri).build(), uri, emptyMap(), 0, e,
                    )
                }
            }
        }
        return DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID) { uuid ->
                KeyIdMediaDrm(FrameworkMediaDrm.DEFAULT_PROVIDER.acquireExoMediaDrm(uuid), requestKeyIds)
            }
            .setMultiSession(true)
            .setPlayClearSamplesWithoutKeys(false)
            .setLoadErrorHandlingPolicy(object : DefaultLoadErrorHandlingPolicy(3) {
                override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
                    val failure = generateSequence<Throwable>(info.exception) { it.cause }
                        .filterIsInstance<SpatialGenLicenseException>().firstOrNull()
                    return if (failure != null && !failure.retryable) C.TIME_UNSET
                    else super.getRetryDelayMsFor(info)
                }
            })
            .build(callback)
    }
}

/** Intercepts public init metadata while FrameworkMediaDrm still generates every challenge. */
@UnstableApi
internal class KeyIdMediaDrm(
    private val delegate: ExoMediaDrm,
    private val requestKeyIds: MutableMap<ExoMediaDrm.KeyRequest, UUID>,
) : ExoMediaDrm by delegate {
    private val sessionKeyIds = mutableMapOf<String, UUID>()

    override fun getKeyRequest(
        scope: ByteArray,
        schemeDatas: MutableList<DrmInitData.SchemeData>?,
        keyType: Int,
        optionalParameters: HashMap<String, String>?,
    ): ExoMediaDrm.KeyRequest {
        val session = Base64.getEncoder().encodeToString(scope)
        val keyId = if (!schemeDatas.isNullOrEmpty()) {
            WidevineKeyIds.single(schemeDatas.filter { it.matches(C.WIDEVINE_UUID) }
                .flatMap { it.data?.let(WidevineKeyIds::fromPssh).orEmpty() }.toSet())
        } else {
            requireNotNull(sessionKeyIds[session]) { "No KID available for Widevine renewal" }
        }
        val request = delegate.getKeyRequest(scope, schemeDatas, keyType, optionalParameters)
        sessionKeyIds[session] = keyId
        requestKeyIds[request] = keyId
        return request
    }

    override fun closeSession(sessionId: ByteArray) {
        sessionKeyIds.remove(Base64.getEncoder().encodeToString(sessionId))
        delegate.closeSession(sessionId)
    }
}
