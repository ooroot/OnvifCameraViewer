package com.example.onvifcameraviewer.domain.model

/**
 * Credentials for authenticating with an ONVIF device.
 */
data class Credentials(
    val username: String,
    val password: String,
    val rtspUsername: String? = null
) {
    val effectiveRtspUsername: String get() = rtspUsername?.takeIf { it.isNotBlank() } ?: username
}
