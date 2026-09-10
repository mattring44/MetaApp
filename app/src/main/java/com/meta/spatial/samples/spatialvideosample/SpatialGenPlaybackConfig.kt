package com.spectrum.spectrumsports

/** Settings for the SpatialGen Widevine sample. */
object SpatialGenPlaybackConfig {
    // Set SPATIALGEN_API_KEY in local.properties (ignored by Git), or replace this with your key.
    // This value is packaged in the APK; use a backend license proxy for a production secret.
    val API_KEY: String = BuildConfig.SPATIALGEN_API_KEY

    const val STREAM_URL =
        "https://dev-cdn-02.spatialgen.com/widevine-sample/widevine/index.m3u8"
    const val LICENSE_URL = "https://drm.spatialgen.com/widevine/license"
}
