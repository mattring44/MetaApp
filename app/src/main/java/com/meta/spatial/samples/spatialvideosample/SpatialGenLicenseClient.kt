package com.spectrum.spectrumsports

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.UUID
import org.json.JSONObject

/** An error safe to display/log: it never contains an API key, challenge, or response body. */
internal class SpatialGenLicenseException(message: String, val retryable: Boolean = false) :
    IOException(message)

internal class SpatialGenLicenseClient(
    private val apiKey: String,
    private val licenseUrl: String,
    private val connectionFactory: (URL) -> HttpURLConnection = {
        it.openConnection() as HttpURLConnection
    },
) {
    fun acquireLicense(keyId: UUID, challenge: ByteArray): ByteArray {
        if (apiKey.isBlank()) throw SpatialGenLicenseException("Set SPATIALGEN_API_KEY and rebuild")
        if (challenge.isEmpty() || challenge.size > 1024 * 1024) {
            throw SpatialGenLicenseException("Widevine challenge must contain 1 byte to 1 MiB")
        }
        val url = URL(licenseUrl)
        if (url.protocol != "https") throw SpatialGenLicenseException("License endpoint must use HTTPS")
        val body = JSONObject()
            .put("key_id", keyId.toString())
            .put("license_challenge", Base64.getEncoder().encodeToString(challenge))
            .toString().toByteArray(Charsets.UTF_8)
        val connection = connectionFactory(url)
        try {
            connection.requestMethod = "POST"
            // Do not forward the app key through a redirect to another endpoint.
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-SPATIALGEN-APPKEY", apiKey)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            if (status !in 200..299) {
                throw SpatialGenLicenseException(
                    "SpatialGen license request failed (HTTP $status)",
                    retryable = status == 408 || status == 429 || status in 500..599,
                )
            }
            val response = connection.inputStream.use { input ->
                val bytes = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val read = input.read(chunk)
                    if (read == -1) break
                    if (bytes.size() + read > 2 * 1024 * 1024) {
                        throw SpatialGenLicenseException("SpatialGen license response is too large")
                    }
                    bytes.write(chunk, 0, read)
                }
                bytes.toString("UTF-8")
            }
            return decodeLicense(response)
        } finally {
            connection.disconnect()
        }
    }

    internal fun decodeLicense(response: String): ByteArray {
        try {
            val encoded = JSONObject(response).get("license")
            if (encoded !is String || encoded.isBlank()) {
                throw SpatialGenLicenseException("SpatialGen response is missing a base64 license")
            }
            return Base64.getDecoder().decode(encoded).also {
                if (it.isEmpty()) throw SpatialGenLicenseException("SpatialGen returned an empty license")
            }
        } catch (e: SpatialGenLicenseException) {
            throw e
        } catch (_: Exception) {
            throw SpatialGenLicenseException("SpatialGen returned invalid license JSON/base64")
        }
    }
}
