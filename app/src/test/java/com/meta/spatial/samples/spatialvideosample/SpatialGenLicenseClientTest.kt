package com.spectrum.spectrumsports

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SpatialGenLicenseClientTest {
    private val kid = UUID.fromString("302f80dd-411e-4886-bca5-bb1f8018a024")
    private val endpoint = "https://drm.spatialgen.com/widevine/license"

    @Test fun sendsDocumentedHeadersAndJsonAndReturnsRawLicense() {
        val connection = FakeConnection(200, """{"license":"AAH+/w=="}""")
        val challenge = byteArrayOf(0, 1, 2, 127, -128, -1)
        val result = client(connection).acquireLicense(kid, challenge)
        assertEquals("POST", connection.requestMethod)
        assertEquals("test-app-key", connection.getRequestProperty("X-SPATIALGEN-APPKEY"))
        assertEquals("application/json", connection.getRequestProperty("Content-Type"))
        val body = JSONObject(connection.sent.toString("UTF-8"))
        assertEquals(2, body.length())
        assertEquals(kid.toString(), body.getString("key_id"))
        assertArrayEquals(challenge, Base64.getDecoder().decode(body.getString("license_challenge")))
        assertArrayEquals(byteArrayOf(0, 1, -2, -1), result)
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(connection.disconnected)
    }

    @Test fun rejectsMissingKeyAndOversizedOrEmptyChallengesBeforeConnecting() {
        val noConnection: (URL) -> HttpURLConnection = { error("Must not connect") }
        assertThrows(SpatialGenLicenseException::class.java) {
            SpatialGenLicenseClient("", endpoint, noConnection).acquireLicense(kid, byteArrayOf(1))
        }
        for (challenge in listOf(byteArrayOf(), ByteArray(1024 * 1024 + 1))) {
            assertThrows(SpatialGenLicenseException::class.java) {
                SpatialGenLicenseClient("test", endpoint, noConnection).acquireLicense(kid, challenge)
            }
        }
    }

    @Test fun permitsChallengeAtDocumentedLimit() {
        assertArrayEquals(byteArrayOf(1), client(FakeConnection(200, """{"license":"AQ=="}"""))
            .acquireLicense(kid, ByteArray(1024 * 1024)))
    }

    @Test fun rejectsHttpEndpointBeforeSendingCredentials() {
        assertThrows(SpatialGenLicenseException::class.java) {
            SpatialGenLicenseClient("test", "http://example.com/license") { error("Must not connect") }
                .acquireLicense(kid, byteArrayOf(1))
        }
    }

    @Test fun classifiesHttpFailuresWithoutExposingResponseBody() {
        for (code in listOf(302, 307, 400, 401, 403, 404, 408, 429, 500, 503)) {
            val connection = FakeConnection(code, "sensitive-server-body")
            val error = assertThrows(SpatialGenLicenseException::class.java) {
                client(connection).acquireLicense(kid, byteArrayOf(1))
            }
            assertEquals(code == 408 || code == 429 || code >= 500, error.retryable)
            assertTrue(error.message!!.contains(code.toString()))
            assertFalse(error.message!!.contains("sensitive-server-body"))
            assertTrue(connection.disconnected)
        }
    }

    @Test fun rejectsMissingEmptyOrMalformedLicenseResponses() {
        for (body in listOf("not JSON", "{}", """{"license":null}""", """{"license":123}""",
            """{"license":""}""", """{"license":"not-base64!"}""")) {
            val connection = FakeConnection(200, body)
            val error = assertThrows(SpatialGenLicenseException::class.java) {
                client(connection).acquireLicense(kid, byteArrayOf(1))
            }
            assertFalse(error.retryable)
            assertTrue(connection.disconnected)
        }
    }

    @Test fun boundsLicenseResponseSize() {
        assertThrows(SpatialGenLicenseException::class.java) {
            client(FakeConnection(200, "a".repeat(2 * 1024 * 1024 + 1)))
                .acquireLicense(kid, byteArrayOf(1))
        }
    }

    private fun client(connection: FakeConnection) =
        SpatialGenLicenseClient("test-app-key", endpoint) { url ->
            assertEquals(endpoint, url.toString())
            connection
        }

    private class FakeConnection(private val code: Int, private val body: String) :
        HttpURLConnection(URL("https://example.test/license")) {
        val sent = ByteArrayOutputStream()
        var disconnected = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getOutputStream() = sent
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
        override fun getResponseCode() = code
    }
}
