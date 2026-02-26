package com.example.onvifcameraviewer.data.onvif

import android.util.Log
import com.example.onvifcameraviewer.domain.exception.OnvifException
import com.example.onvifcameraviewer.domain.model.Credentials
import com.example.onvifcameraviewer.domain.model.MediaProfile
import com.example.onvifcameraviewer.domain.model.VideoEncoderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ONVIF Media Service client for retrieving profiles and stream URIs.
 * 
 * Auto-detects SOAP version (1.1 vs 1.2) from camera responses to maximize compatibility.
 */
@Singleton
class OnvifMediaService @Inject constructor() {
    
    companion object {
        private const val TAG = "OnvifMediaService"
        private const val SOAP11_CONTENT_TYPE = "text/xml; charset=utf-8"
        private const val SOAP12_CONTENT_TYPE = "application/soap+xml; charset=utf-8"
        private const val SOAP11_NS = "http://schemas.xmlsoap.org/soap/envelope/"
        private const val SOAP12_NS = "http://www.w3.org/2003/05/soap-envelope"
        private const val CONNECT_TIMEOUT_SEC = 10L
        private const val READ_TIMEOUT_SEC = 15L

        /**
         * Percent-encodes a string for use in URI userinfo (RFC 3986 Section 3.2.1).
         *
         * Manually encodes every byte that is NOT in the unreserved set or the
         * sub-delims set (minus "@" and ":" which are NOT safe in userinfo values).
         *
         * We do NOT rely on android.net.Uri.encode() because its behavior with "@"
         * is uncertain on some Android versions, and ExoPlayer's RtspMessageUtil
         * splits the encoded authority on ALL literal "@" characters — if even one
         * unencoded "@" leaks through, ExoPlayer resolves the wrong host.
         */
        private fun percentEncodeUserInfo(value: String): String {
            val safe = buildSet {
                ('a'..'z').forEach { add(it) }
                ('A'..'Z').forEach { add(it) }
                ('0'..'9').forEach { add(it) }
                "-._~!\$&'()*+,;=".forEach { add(it) }
            }
            val sb = StringBuilder(value.length * 2)
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val c = byte.toInt() and 0xFF
                if (c.toChar() in safe) {
                    sb.append(c.toChar())
                } else {
                    sb.append('%')
                    sb.append(String.format("%02X", c))
                }
            }
            return sb.toString()
        }

        /**
         * Embeds credentials into an RTSP URI for ExoPlayer's RtspMediaSource.
         *
         * ExoPlayer's RtspMessageUtil.removeUserInfo() splits the encoded authority
         * on ALL literal "@" chars and takes index [1]. If the password contains an
         * unencoded "@", ExoPlayer will resolve the wrong host. This function
         * percent-encodes all special characters in credentials to prevent that.
         *
         * Called at player-creation time, NOT at URI-storage time. The DB stores
         * clean URIs without credentials; this function adds them back.
         */
        fun embedCredentialsInUri(uri: String, credentials: Credentials): String {
            return try {
                val parsed = android.net.Uri.parse(uri)

                val host = parsed.host
                if (host.isNullOrEmpty()) {
                    Log.w(TAG, "embedCredentials: could not extract host from URI")
                    return uri
                }
                val port = if (parsed.port > 0) ":${parsed.port}" else ""

                val encodedUser = percentEncodeUserInfo(credentials.effectiveRtspUsername)
                val encodedPass = percentEncodeUserInfo(credentials.password)

                // Use Uri.Builder.encodedAuthority() to store percent-encoded userinfo
                // VERBATIM. Unlike Uri.parse() which decodes %40 back to @ in the
                // authority (causing ExoPlayer to misparse the host), encodedAuthority()
                // preserves the encoding so ExoPlayer's split-on-@ logic works correctly.
                val resultUri = android.net.Uri.Builder()
                    .scheme("rtsp")
                    .encodedAuthority("$encodedUser:$encodedPass@$host$port")
                    .encodedPath(parsed.encodedPath ?: "")
                    .encodedQuery(parsed.encodedQuery)
                    .encodedFragment(parsed.encodedFragment)
                    .build()

                resultUri.toString()
            } catch (e: Exception) {
                Log.e(TAG, "embedCredentials exception: ${e.message}", e)
                uri
            }
        }

        // --- Digest Authentication (shared with CameraViewModel RTSP probe) ---

        data class DigestChallenge(
            val realm: String,
            val nonce: String,
            val qop: String?,
            val opaque: String?,
            val algorithm: String
        )

        fun parseDigestChallenge(wwwAuthenticate: String): DigestChallenge? {
            if (!wwwAuthenticate.startsWith("Digest ", ignoreCase = true)) return null

            val params = mutableMapOf<String, String>()
            val paramRegex = Regex("""(\w+)=(?:"([^"]*)"|([\w/]+))""")
            paramRegex.findAll(wwwAuthenticate).forEach { match ->
                val key = match.groupValues[1].lowercase()
                val value = match.groupValues[2].ifEmpty { match.groupValues[3] }
                params[key] = value
            }

            val realm = params["realm"] ?: return null
            val nonce = params["nonce"] ?: return null
            val qop = params["qop"]
            val opaque = params["opaque"]
            val algorithm = params["algorithm"] ?: "MD5"

            return DigestChallenge(realm, nonce, qop, opaque, algorithm)
        }

        fun computeDigestResponse(
            challenge: DigestChallenge,
            username: String,
            password: String,
            method: String,
            uri: String
        ): String {
            fun md5Hex(data: String): String {
                val md = MessageDigest.getInstance("MD5")
                return md.digest(data.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
            }

            val ha1 = md5Hex("$username:${challenge.realm}:$password")
            val ha2 = md5Hex("$method:$uri")

            val cnonce = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
            val nc = "00000001"

            val response: String
            val headerParts = mutableListOf(
                """username="$username"""",
                """realm="${challenge.realm}"""",
                """nonce="${challenge.nonce}"""",
                """uri="$uri""""
            )

            if (challenge.qop != null) {
                val qopUsed = if ("auth" in challenge.qop) "auth" else challenge.qop
                response = md5Hex("$ha1:${challenge.nonce}:$nc:$cnonce:$qopUsed:$ha2")
                headerParts.add("""qop=$qopUsed""")
                headerParts.add("""nc=$nc""")
                headerParts.add("""cnonce="$cnonce"""")
            } else {
                response = md5Hex("$ha1:${challenge.nonce}:$ha2")
            }

            headerParts.add("""response="$response"""")
            if (challenge.opaque != null) {
                headerParts.add("""opaque="${challenge.opaque}"""")
            }
            headerParts.add("""algorithm=${challenge.algorithm}""")

            return "Digest " + headerParts.joinToString(", ")
        }
    }
    
    /**
     * Trust manager that accepts all certificates.
     * ONVIF cameras on local networks use self-signed certs — this is intentional and safe
     * because we only communicate with devices on the private LAN, not the public internet.
     */
    private val trustAllCerts: Array<TrustManager> = arrayOf(
        object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
    )

    private val sslContext: SSLContext = SSLContext.getInstance("TLS").apply {
        init(null, trustAllCerts, SecureRandom())
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
        .hostnameVerifier { _, _ -> true }
        .build()
    
    // Cache for media service URLs to avoid repeated GetCapabilities calls
    private val mediaUrlCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    
    // Cache detected SOAP version per device (true = SOAP 1.2, false = SOAP 1.1)
    private val soapVersionCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    
    // Cache devices that reject WS-Security and need HTTP Digest auth only.
    // Key = normalized device URL (not media URL) so the flag carries across
    // getProfiles() and getStreamUri() calls for the same camera.
    private val digestOnlyCache = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    
    // Cache time offsets per device to avoid redundant GetSystemDateAndTime SOAP calls.
    // Key = deviceUrl, Value = Pair(offsetMs, timestampWhenCached).
    // Valid for 60 seconds — camera clock drift is negligible in that window.
    private val timeOffsetCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Long>>()
    private val TIME_OFFSET_TTL_MS = 60_000L

    // --- Manual HTTP Digest Authentication ---
    // Replaces okhttp-digest library to avoid nonce poisoning (the library's
    // DigestAuthenticator.havePreviousDigestAuthorizationAndShouldAbort() returns
    // null after one failed attempt with the same nonce, even with a fresh client).
    // Manual implementation: send probe → parse WWW-Authenticate → compute digest → send with auth header.
    // DigestChallenge, parseDigestChallenge(), computeDigestResponse() are in the companion object
    // above so CameraViewModel's RTSP probe can reuse them.
    
    /**
     * Retrieves available media profiles from the device.
     * Flow: sync clock → get media URL → fetch profiles with auth.
     */
    suspend fun getProfiles(
        deviceUrl: String,
        credentials: Credentials
    ): Result<List<MediaProfile>> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "getProfiles: Starting for $deviceUrl")

            // Clear digest-only flag so we re-test WS-Security each time
            // (credential changes or camera restarts may make it work again).
            digestOnlyCache.remove(deviceUrl)

            // Step 1: sync time with camera (unauthenticated)
            val timeOffset = calculateTimeOffset(deviceUrl)
            Log.d(TAG, "Time offset: ${timeOffset}ms")

            // Step 2: resolve actual media service URL
            val mediaUrl = resolveMediaServiceUrl(deviceUrl, credentials, timeOffset)
            Log.d(TAG, "Resolved media URL: $mediaUrl")

            // Step 3: fetch profiles with auth + correct time offset
            val useSoap12 = soapVersionCache[deviceUrl] ?: false
            fetchProfilesWithOffset(mediaUrl, credentials, timeOffset, useSoap12, deviceUrl)
        } catch (e: Exception) {
            Log.e(TAG, "getProfiles failed", e)
            Result.failure(e)
        }
    }

    /**
     * Resolves the actual Media Service URL using GetCapabilities.
     * Per ONVIF spec this should be unauthenticated, but some cameras require auth.
     * Falls back to authenticated request, then heuristic URL.
     */
    private suspend fun resolveMediaServiceUrl(
        deviceUrl: String,
        credentials: Credentials? = null,
        timeOffset: Long = 0
    ): String {
        // Check cache first
        mediaUrlCache[deviceUrl]?.let { return it }

        val useSoap12 = soapVersionCache[deviceUrl] ?: false
        
        // Attempt 1: unauthenticated (per ONVIF spec)
        try {
            val soapRequest = buildGetCapabilitiesRequest(useSoap12)
            val response = executeSoapRequest(deviceUrl, soapRequest, null, useSoap12 = useSoap12)
            val mediaUrl = parseMediaUrlFromCapabilities(response)

            if (mediaUrl != null) {
                mediaUrlCache[deviceUrl] = mediaUrl
                return mediaUrl
            }
        } catch (e: Exception) {
            Log.w(TAG, "GetCapabilities unauthenticated failed: ${e.message}")
        }
        
        // Attempt 2: with HTTP Digest auth (some cameras require it)
        if (credentials != null) {
            try {
                val soapRequest = buildGetCapabilitiesRequest(useSoap12)
                val response = executeSoapRequest(
                    deviceUrl, soapRequest, credentials,
                    useSoap12 = useSoap12, useDigestAuth = true
                )
                val mediaUrl = parseMediaUrlFromCapabilities(response)
                if (mediaUrl != null) {
                    mediaUrlCache[deviceUrl] = mediaUrl
                    return mediaUrl
                }
            } catch (e: Exception) {
                Log.w(TAG, "GetCapabilities with auth also failed: ${e.message}")
            }
            
            // Attempt 3: with WS-Security auth
            if (!digestOnlyCache.contains(deviceUrl)) {
                try {
                    val auth = OnvifAuth.generateAuthComponents(
                        credentials.username, credentials.password, timeOffset
                    )
                    val soapRequest = buildGetCapabilitiesRequestWithAuth(auth, useSoap12)
                    val response = executeSoapRequest(
                        deviceUrl, soapRequest, credentials,
                        useSoap12 = useSoap12, useDigestAuth = false
                    )
                    val mediaUrl = parseMediaUrlFromCapabilities(response)
                    if (mediaUrl != null) {
                        mediaUrlCache[deviceUrl] = mediaUrl
                        return mediaUrl
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "GetCapabilities with WS-Security also failed: ${e.message}")
                }
            }
        }
        
        Log.w(TAG, "All GetCapabilities attempts failed, using heuristic URL")
        return heuristicMediaUrl(deviceUrl)
    }

    private fun buildGetCapabilitiesRequest(useSoap12: Boolean): String {
        val soapNs = if (useSoap12) SOAP12_NS else SOAP11_NS
        return """<?xml version="1.0" encoding="UTF-8"?>
<soap:Envelope xmlns:soap="$soapNs"
               xmlns:tds="http://www.onvif.org/ver10/device/wsdl">
    <soap:Body>
        <tds:GetCapabilities>
            <tds:Category>Media</tds:Category>
        </tds:GetCapabilities>
    </soap:Body>
</soap:Envelope>"""
    }
    
    private fun buildGetCapabilitiesRequestWithAuth(auth: AuthComponents, useSoap12: Boolean): String {
        val soapNs = if (useSoap12) SOAP12_NS else SOAP11_NS
        return """<?xml version="1.0" encoding="UTF-8"?>
<soap:Envelope xmlns:soap="$soapNs"
               xmlns:tds="http://www.onvif.org/ver10/device/wsdl">
    <soap:Header>
        ${OnvifAuth.buildSecurityHeader(auth)}
    </soap:Header>
    <soap:Body>
        <tds:GetCapabilities>
            <tds:Category>Media</tds:Category>
        </tds:GetCapabilities>
    </soap:Body>
</soap:Envelope>"""
    }

    private fun parseMediaUrlFromCapabilities(response: String): String? {
        val mediaUrlRegex = Regex("""<[^:]*:?Media[^>]*>\s*<[^:]*:?XAddr>([^<]+)</[^:]*:?XAddr>""", RegexOption.IGNORE_CASE)
        return mediaUrlRegex.find(response)?.groupValues?.get(1)?.trim()
            ?.replace("https://", "http://")
    }

    private fun heuristicMediaUrl(deviceUrl: String): String {
        return deviceUrl.replace("https://", "http://")
            .replace("device_service", "media_service")
            .replace("/onvif/device", "/onvif/media")
    }

    private suspend fun fetchProfilesWithOffset(
        mediaUrl: String, 
        credentials: Credentials, 
        offset: Long,
        useSoap12: Boolean,
        deviceUrl: String
    ): Result<List<MediaProfile>> {
        val useDigestOnly = digestOnlyCache.contains(deviceUrl)
        
        if (!useDigestOnly) {
            // Try WS-Security with detected SOAP version first
            try {
                val auth = OnvifAuth.generateAuthComponents(
                    credentials.username, credentials.password, offset
                )
                Log.d(TAG, "WS-Security: user=${credentials.username}, created=${auth.created}, offset=${offset}ms")
                val soapRequest = buildGetProfilesRequest(auth, useSoap12)
                val response = executeSoapRequest(
                    mediaUrl, soapRequest, credentials,
                    useSoap12 = useSoap12, useDigestAuth = false
                )
                val profiles = parseProfilesResponse(response)
                return Result.success(profiles)
            } catch (e: Exception) {
                val errMsg = e.message?.lowercase() ?: ""
                val is400 = errMsg.contains("400")
                val is401 = errMsg.contains("401") ||
                    e is OnvifException.AuthenticationException
                val isAuthSoapFault = e is OnvifException.AuthenticationException &&
                    errMsg.contains("soap fault")
                val shouldTryAlt = is400 || is401 || isAuthSoapFault

                // If the camera explicitly says "invalid credentials", don't waste
                // more attempts — it will lock us out.
                val isExplicitBadPassword = errMsg.contains("invalid username or password") ||
                    errMsg.contains("invalid credentials")
                if (isExplicitBadPassword) {
                    Log.w(TAG, "Camera explicitly rejects credentials — not retrying")
                    throw OnvifException.AuthenticationException(
                        "Authentication failed - camera says invalid username or password"
                    )
                }

                if (shouldTryAlt) {
                    Log.w(TAG, "WS-Security rejected with ${if (useSoap12) "SOAP1.2" else "SOAP1.1"} (${e.javaClass.simpleName}: ${e.message?.take(80)})")

                    // Try WS-Security with the ALTERNATE SOAP version before giving up on WS-Security
                    val altSoap12 = !useSoap12
                    try {
                        val altAuth = OnvifAuth.generateAuthComponents(
                            credentials.username, credentials.password, offset
                        )
                        Log.d(TAG, "Retrying WS-Security with ${if (altSoap12) "SOAP1.2" else "SOAP1.1"}")
                        val altRequest = buildGetProfilesRequest(altAuth, altSoap12)
                        val response = executeSoapRequest(
                            mediaUrl, altRequest, credentials,
                            useSoap12 = altSoap12, useDigestAuth = false
                        )
                        soapVersionCache[deviceUrl] = altSoap12
                        val profiles = parseProfilesResponse(response)
                        return Result.success(profiles)
                    } catch (altE: Exception) {
                        Log.w(TAG, "WS-Security also rejected with ${if (altSoap12) "SOAP1.2" else "SOAP1.1"}: ${altE.message?.take(80)}")
                    }

                    // Both SOAP versions rejected WS-Security → fall through to HTTP Digest
                    Log.w(TAG, "WS-Security rejected with both SOAP versions, falling back to HTTP Digest")
                    digestOnlyCache.add(deviceUrl)
                } else {
                    Log.e(TAG, "WS-Security failed (non-recoverable): ${e.javaClass.simpleName}: ${e.message}")
                    throw e
                }
            }
        }
        
        // HTTP Digest only
        Log.d(TAG, "Using HTTP Digest auth only (no WS-Security)")
        try {
            val soapRequest = buildGetProfilesRequestNoAuth(useSoap12)
            val response = executeSoapRequest(
                mediaUrl, soapRequest, credentials,
                useSoap12 = useSoap12, useDigestAuth = true
            )
            val profiles = parseProfilesResponse(response)
            return Result.success(profiles)
        } catch (e: OnvifException.AuthenticationException) {
            // HTTP Digest also failed - try the other SOAP version in case the camera is picky.
            val altSoap12 = !useSoap12
            Log.w(TAG, "HTTP Digest failed with ${if (useSoap12) "SOAP1.2" else "SOAP1.1"}, trying ${if (altSoap12) "SOAP1.2" else "SOAP1.1"}")
            try {
                val altRequest = buildGetProfilesRequestNoAuth(altSoap12)
                val response = executeSoapRequest(
                    mediaUrl, altRequest, credentials,
                    useSoap12 = altSoap12, useDigestAuth = true
                )
                soapVersionCache[deviceUrl] = altSoap12
                val profiles = parseProfilesResponse(response)
                return Result.success(profiles)
            } catch (_: Exception) {
                // Both SOAP versions failed with digest - throw original auth error
                throw e
            }
        }
    }

    /**
     * Calculates time offset between device and local clock.
     * Uses a short-lived cache to avoid redundant SOAP calls when getProfiles()
     * and getStreamUri() are called in quick succession.
     */
    private suspend fun calculateTimeOffset(deviceUrl: String): Long {
        // Return cached offset if still fresh
        timeOffsetCache[deviceUrl]?.let { (offset, cachedAt) ->
            if (System.currentTimeMillis() - cachedAt < TIME_OFFSET_TTL_MS) {
                Log.d(TAG, "Using cached time offset: ${offset}ms (age ${System.currentTimeMillis() - cachedAt}ms)")
                return offset
            }
        }
        
        val offset = fetchTimeOffset(deviceUrl)
        timeOffsetCache[deviceUrl] = Pair(offset, System.currentTimeMillis())
        return offset
    }
    
    private suspend fun fetchTimeOffset(deviceUrl: String): Long {
        // Try SOAP 1.1 first (most common), then SOAP 1.2 if it fails
        val soap11Request = """<?xml version="1.0" encoding="UTF-8"?>
<soap:Envelope xmlns:soap="$SOAP11_NS"
               xmlns:tds="http://www.onvif.org/ver10/device/wsdl">
    <soap:Body>
        <tds:GetSystemDateAndTime/>
    </soap:Body>
</soap:Envelope>"""
        
        val soap12Request = """<?xml version="1.0" encoding="UTF-8"?>
<soap:Envelope xmlns:soap="$SOAP12_NS"
               xmlns:tds="http://www.onvif.org/ver10/device/wsdl">
    <soap:Body>
        <tds:GetSystemDateAndTime/>
    </soap:Body>
</soap:Envelope>"""

        return try {
            val response = executeSoapRequest(deviceUrl, soap11Request, null, useSoap12 = false)
            // Detect SOAP version from response
            val isSoap12 = response.contains(SOAP12_NS)
            soapVersionCache[deviceUrl] = isSoap12
            if (isSoap12) Log.d(TAG, "Camera uses SOAP 1.2")
            parseSystemDateAndTime(response)
        } catch (e: Exception) {
            // SOAP 1.1 failed -- try SOAP 1.2
            try {
                val response = executeSoapRequest(deviceUrl, soap12Request, null, useSoap12 = true)
                soapVersionCache[deviceUrl] = true
                Log.d(TAG, "Camera uses SOAP 1.2 (SOAP 1.1 rejected)")
                parseSystemDateAndTime(response)
            } catch (e2: Exception) {
                Log.w(TAG, "GetSystemDateAndTime failed with both SOAP versions, using offset 0: ${e2.message}")
                0L
            }
        }
    }

    private fun parseSystemDateAndTime(response: String): Long {
        // Extract UTCDateTime block specifically (not LocalDateTime) to avoid
        // timezone-skewed values. Fall back to first match if UTC block not found.
        val utcBlock = Regex(
            """<[^:]*:?UTCDateTime[^>]*>(.*?)</[^:]*:?UTCDateTime>""",
            RegexOption.DOT_MATCHES_ALL
        ).find(response)?.groupValues?.get(1)
        val source = utcBlock ?: response

        val year = Regex("""<[^:]*:?Year>(\d+)</[^:]*:?Year>""").find(source)?.groupValues?.get(1)?.toInt() ?: return 0
        val month = Regex("""<[^:]*:?Month>(\d+)</[^:]*:?Month>""").find(source)?.groupValues?.get(1)?.toInt() ?: return 0
        val day = Regex("""<[^:]*:?Day>(\d+)</[^:]*:?Day>""").find(source)?.groupValues?.get(1)?.toInt() ?: return 0
        val hour = Regex("""<[^:]*:?Hour>(\d+)</[^:]*:?Hour>""").find(source)?.groupValues?.get(1)?.toInt() ?: return 0
        val minute = Regex("""<[^:]*:?Minute>(\d+)</[^:]*:?Minute>""").find(source)?.groupValues?.get(1)?.toInt() ?: return 0
        val second = Regex("""<[^:]*:?Second>(\d+)</[^:]*:?Second>""").find(source)?.groupValues?.get(1)?.toInt() ?: return 0
        
        val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        calendar.set(year, month - 1, day, hour, minute, second)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        val serverTime = calendar.timeInMillis
        val localTime = System.currentTimeMillis()

        Log.d(TAG, "Camera UTC time: $year-$month-$day $hour:$minute:$second, offset=${serverTime - localTime}ms")
        
        return serverTime - localTime
    }

    /**
     * Retrieves the RTSP stream URI for a specific profile.
     */
    suspend fun getStreamUri(
        deviceUrl: String,
        credentials: Credentials,
        profileToken: String,
        useUdp: Boolean = false
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val timeOffset = calculateTimeOffset(deviceUrl)
            val mediaUrl = resolveMediaServiceUrl(deviceUrl, credentials, timeOffset)
            val cameraIp = try {
                java.net.URI(deviceUrl).host ?: ""
            } catch (_: Exception) { "" }
            val useSoap12 = soapVersionCache[deviceUrl] ?: false
            fetchStreamUriWithOffset(mediaUrl, credentials, profileToken, useUdp, timeOffset, cameraIp, useSoap12, deviceUrl)
        } catch (e: Exception) {
            Log.e(TAG, "getStreamUri failed for $deviceUrl", e)
            Result.failure(e)
        }
    }

    private suspend fun fetchStreamUriWithOffset(
        mediaUrl: String,
        credentials: Credentials,
        profileToken: String,
        useUdp: Boolean,
        offset: Long,
        cameraIp: String = "",
        useSoap12: Boolean = false,
        deviceUrl: String = ""
    ): Result<String> {
        val transportProtocol = if (useUdp) "UDP" else "TCP"
        val useDigestOnly = digestOnlyCache.contains(deviceUrl)
        
        val response = if (useDigestOnly) {
            Log.d(TAG, "Using HTTP Digest auth only for GetStreamUri")
            val soapRequest = buildGetStreamUriRequestNoAuth(profileToken, transportProtocol, useSoap12)
            executeSoapRequest(
                mediaUrl, soapRequest, credentials,
                useSoap12 = useSoap12, useDigestAuth = true
            )
        } else {
            val auth = OnvifAuth.generateAuthComponents(
                credentials.username, credentials.password, offset
            )
            val soapRequest = buildGetStreamUriRequest(auth, profileToken, transportProtocol, useSoap12)
            try {
                executeSoapRequest(
                    mediaUrl, soapRequest, credentials,
                    useSoap12 = useSoap12, useDigestAuth = false
                )
            } catch (e: Exception) {
                val is400 = e.message?.contains("400") == true
                val is401 = e.message?.contains("401") == true ||
                    e is OnvifException.AuthenticationException
                val isAuthSoapFault = e is OnvifException.AuthenticationException &&
                    e.message?.contains("SOAP fault") == true
                val shouldFallbackToDigest = is400 || is401 || isAuthSoapFault
                if (shouldFallbackToDigest && deviceUrl.isNotEmpty()) {
                    Log.w(TAG, "WS-Security rejected for GetStreamUri (${e.javaClass.simpleName}), retrying with HTTP Digest only")
                    digestOnlyCache.add(deviceUrl)
                    val noAuthRequest = buildGetStreamUriRequestNoAuth(profileToken, transportProtocol, useSoap12)
                    executeSoapRequest(
                        mediaUrl, noAuthRequest, credentials,
                        useSoap12 = useSoap12, useDigestAuth = true
                    )
                } else {
                    throw e
                }
            }
        }
        
        val uri = parseStreamUriResponse(response)
            ?: throw OnvifException.StreamUriException("Failed to parse stream URI from SOAP response")
        
        var cleanUri = uri.trim().replace(Regex("^rtsp:///+"), "rtsp://")
        
        if (cameraIp.isNotEmpty()) {
            cleanUri = fixStreamUriHost(cleanUri, cameraIp)
        }

        cleanUri = stripOnvifQueryParams(cleanUri)
        
        Log.d(TAG, "Raw stream URI from camera: $uri")
        Log.d(TAG, "Clean URI (no credentials): $cleanUri")
        
        return Result.success(cleanUri)
    }
    
    /**
     * Fixes stream URIs that contain localhost, 0.0.0.0, or 127.0.0.1
     * by replacing with the actual camera IP.
     */
    private fun fixStreamUriHost(uri: String, actualIp: String): String {
        return try {
            val parsed = android.net.Uri.parse(uri)
            val host = parsed.host ?: return uri
            
            if (host == "0.0.0.0" || host == "127.0.0.1" || host.equals("localhost", ignoreCase = true)) {
                Log.w(TAG, "Camera returned bogus host '$host' in stream URI, replacing with $actualIp")
                val port = if (parsed.port > 0) ":${parsed.port}" else ""
                val path = parsed.encodedPath ?: ""
                val query = if (parsed.encodedQuery != null) "?${parsed.encodedQuery}" else ""
                val userInfo = if (parsed.encodedUserInfo != null) "${parsed.encodedUserInfo}@" else ""
                "rtsp://$userInfo$actualIp$port$path$query"
            } else {
                uri
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse URI for host fixup: ${e.message}")
            uri
        }
    }

    /**
     * Strips ONVIF-injected query params (unicast, proto) from stream URIs.
     * Some cameras (CP PLUS / Dahua) fail RTSP Digest auth when these are present.
     */
    private fun stripOnvifQueryParams(uri: String): String {
        return try {
            val parsed = android.net.Uri.parse(uri)
            val query = parsed.encodedQuery ?: return uri
            val filtered = query.split("&")
                .filter { param ->
                    val key = param.substringBefore("=").lowercase()
                    key != "unicast" && key != "proto"
                }
                .joinToString("&")
            val base = uri.substringBefore("?")
            if (filtered.isEmpty()) base else "$base?$filtered"
        } catch (e: Exception) {
            uri
        }
    }

    /**
     * Executes a SOAP request and returns the response body.
     *
     * @param useDigestAuth If true AND credentials are non-null, performs manual
     *   HTTP Digest authentication: sends the request first without auth, parses
     *   the 401 WWW-Authenticate challenge, computes the digest, and retries with
     *   an Authorization header. This avoids the nonce-poisoning bug in the
     *   okhttp-digest library. If false, credentials are ignored at the HTTP level
     *   (use for WS-Security where auth is in the SOAP body).
     */
    private suspend fun executeSoapRequest(
        url: String, 
        soapBody: String, 
        credentials: Credentials? = null,
        useSoap12: Boolean = false,
        useDigestAuth: Boolean = true
    ): String {
        return withContext(Dispatchers.IO) {
            val safeUrl = url.replace("https://", "http://")
            val contentType = if (useSoap12) SOAP12_CONTENT_TYPE else SOAP11_CONTENT_TYPE

            fun buildRequest(extraHeaders: Map<String, String> = emptyMap()): Request {
                val requestBody = soapBody.toRequestBody(contentType.toMediaType())
                return Request.Builder()
                    .url(safeUrl)
                    .post(requestBody)
                    .addHeader("Content-Type", contentType)
                    .apply {
                        if (!useSoap12) addHeader("SOAPAction", "\"\"")
                        extraHeaders.forEach { (k, v) -> addHeader(k, v) }
                    }
                    .build()
            }

            Log.d(TAG, "SOAP -> $safeUrl (${if (useSoap12) "SOAP1.2" else "SOAP1.1"}, digest=${credentials != null && useDigestAuth})")

            // Send initial request (no auth header — even for digest, we need the 401 challenge first)
            val initialRequest = buildRequest()
            val initialResponse = try {
                httpClient.newCall(initialRequest).execute()
            } catch (e: SocketTimeoutException) {
                throw OnvifException.TimeoutException("Connection timed out - check camera IP")
            } catch (e: java.net.ConnectException) {
                throw OnvifException.NetworkException("Cannot connect to camera - check network")
            }

            // If 401 and we have credentials and digest is requested, do manual digest auth
            if (initialResponse.code == 401 && credentials != null && useDigestAuth) {
                val wwwAuth = initialResponse.header("WWW-Authenticate") ?: ""
                initialResponse.close()
                Log.d(TAG, "HTTP 401, WWW-Authenticate: ${wwwAuth.take(200)}")

                val challenge = parseDigestChallenge(wwwAuth)
                if (challenge != null) {
                    val requestUri = try { java.net.URI(safeUrl).rawPath ?: "/" } catch (_: Exception) { "/" }
                    val authHeader = computeDigestResponse(
                        challenge, credentials.username, credentials.password,
                        "POST", requestUri
                    )
                    Log.d(TAG, "Retrying with manual Digest auth (realm=${challenge.realm}, qop=${challenge.qop})")

                    val authedRequest = buildRequest(mapOf("Authorization" to authHeader))
                    val authedResponse = try {
                        httpClient.newCall(authedRequest).execute()
                    } catch (e: SocketTimeoutException) {
                        throw OnvifException.TimeoutException("Connection timed out during digest auth retry")
                    } catch (e: java.net.ConnectException) {
                        throw OnvifException.NetworkException("Connection lost during digest auth retry")
                    }

                    return@withContext handleSoapResponse(authedResponse, safeUrl)
                } else {
                    // 401 but no parseable Digest challenge (maybe Basic auth?)
                    Log.w(TAG, "Got 401 but no Digest challenge in WWW-Authenticate: $wwwAuth")
                    throw OnvifException.AuthenticationException(
                        "Authentication failed - camera requires unsupported auth scheme"
                    )
                }
            }

            handleSoapResponse(initialResponse, safeUrl)
        }
    }

    private fun handleSoapResponse(response: okhttp3.Response, url: String): String {
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            Log.w(TAG, "SOAP HTTP ${response.code} from $url: ${responseBody.take(1000)}")

            // Check if a non-200 response body contains an auth SOAP fault
            // (e.g. some cameras return 400 with ter:NotAuthorized instead of 401)
            val lowerBody400 = responseBody.lowercase()
            val isAuthFaultIn400 = response.code == 400 && (
                lowerBody400.contains("notauthorized") ||
                lowerBody400.contains("invalid username or password") ||
                lowerBody400.contains("failedauthentication") ||
                lowerBody400.contains("failedcheck") ||
                lowerBody400.contains("sender not authorized")
            )

            if (isAuthFaultIn400) {
                throw OnvifException.AuthenticationException(
                    "Authentication failed (400) - camera says invalid credentials. Body: ${responseBody.take(500)}"
                )
            }

            val errorMessage = when (response.code) {
                400 -> "Bad request (400) - camera rejected SOAP. Body: ${responseBody.take(500)}"
                401 -> "Authentication failed - check username/password"
                403 -> "Access forbidden - insufficient permissions"
                404 -> "Service not found at this URL"
                500 -> "Camera internal error"
                503 -> "Camera service unavailable"
                else -> "Request failed: ${response.code}"
            }
            when {
                response.code == 401 -> throw OnvifException.AuthenticationException(errorMessage)
                response.code in 500..599 -> throw OnvifException.NetworkException("$errorMessage: $responseBody")
                else -> throw OnvifException.NetworkException(errorMessage)
            }
        }

        // Check for SOAP faults in HTTP 200 responses (common with WS-Security failures).
        val hasSoapFault = Regex("""<[^>]*:?Fault[\s/>]""", RegexOption.IGNORE_CASE)
            .containsMatchIn(responseBody)
        if (hasSoapFault) {
            Log.w(TAG, "SOAP Fault in 200 response: ${responseBody.take(500)}")
            val lowerBody = responseBody.lowercase()
            val isAuthFault = lowerBody.contains("notauthorized") ||
                lowerBody.contains("not authorized") ||
                lowerBody.contains("authenticationfailed") ||
                lowerBody.contains("failedauthentication") ||
                lowerBody.contains("time check failed") ||
                lowerBody.contains("sender not authorized") ||
                lowerBody.contains("ter:notauthorized") ||
                lowerBody.contains("wsse:failedauthentication") ||
                lowerBody.contains("wsse:failedcheck") ||
                lowerBody.contains("security token") ||
                lowerBody.contains("password") ||
                lowerBody.contains("digest")
            if (isAuthFault) {
                throw OnvifException.AuthenticationException(
                    "Authentication failed - check username/password (SOAP fault)"
                )
            }
            // Non-auth SOAP fault
            val faultString = Regex("""<[^:]*:?Text[^>]*>([^<]+)</""")
                .find(responseBody)?.groupValues?.get(1)
                ?: Regex("""<[^:]*:?Reason[^>]*>([^<]+)</""")
                    .find(responseBody)?.groupValues?.get(1)
                ?: Regex("""<[^:]*:?faultstring[^>]*>([^<]+)</""", RegexOption.IGNORE_CASE)
                    .find(responseBody)?.groupValues?.get(1)
                ?: responseBody.take(200)
            throw OnvifException.NetworkException("SOAP Fault: $faultString")
        }

        return responseBody
    }
    
    /**
     * Builds GetProfiles SOAP request.
     */
    private fun buildGetProfilesRequest(auth: AuthComponents, useSoap12: Boolean): String {
        val soapNs = if (useSoap12) SOAP12_NS else SOAP11_NS
        return """<?xml version="1.0" encoding="UTF-8"?>
<soap:Envelope xmlns:soap="$soapNs"
               xmlns:trt="http://www.onvif.org/ver10/media/wsdl">
    <soap:Header>
        ${OnvifAuth.buildSecurityHeader(auth)}
    </soap:Header>
    <soap:Body>
        <trt:GetProfiles/>
    </soap:Body>
</soap:Envelope>"""
    }
    
    private fun buildGetProfilesRequestNoAuth(useSoap12: Boolean): String {
        val soapNs = if (useSoap12) SOAP12_NS else SOAP11_NS
        return """<?xml version="1.0" encoding="UTF-8"?>
<soap:Envelope xmlns:soap="$soapNs"
               xmlns:trt="http://www.onvif.org/ver10/media/wsdl">
    <soap:Body>
        <trt:GetProfiles/>
    </soap:Body>
</soap:Envelope>"""
    }
    
    /**
     * Builds GetStreamUri SOAP request.
     */
    private fun buildGetStreamUriRequest(
        auth: AuthComponents,
        profileToken: String,
        @Suppress("UNUSED_PARAMETER") transport: String,
        useSoap12: Boolean
    ): String {
        val soapNs = if (useSoap12) SOAP12_NS else SOAP11_NS
        return """<?xml version="1.0" encoding="UTF-8"?>
<soap:Envelope xmlns:soap="$soapNs"
               xmlns:trt="http://www.onvif.org/ver10/media/wsdl"
               xmlns:tt="http://www.onvif.org/ver10/schema">
    <soap:Header>
        ${OnvifAuth.buildSecurityHeader(auth)}
    </soap:Header>
    <soap:Body>
        <trt:GetStreamUri>
            <trt:StreamSetup>
                <tt:Stream>RTP-Unicast</tt:Stream>
                <tt:Transport>
                    <tt:Protocol>RTSP</tt:Protocol>
                </tt:Transport>
            </trt:StreamSetup>
            <trt:ProfileToken>$profileToken</trt:ProfileToken>
        </trt:GetStreamUri>
    </soap:Body>
</soap:Envelope>"""
    }
    
    private fun buildGetStreamUriRequestNoAuth(
        profileToken: String,
        @Suppress("UNUSED_PARAMETER") transport: String,
        useSoap12: Boolean
    ): String {
        val soapNs = if (useSoap12) SOAP12_NS else SOAP11_NS
        return """<?xml version="1.0" encoding="UTF-8"?>
<soap:Envelope xmlns:soap="$soapNs"
               xmlns:trt="http://www.onvif.org/ver10/media/wsdl"
               xmlns:tt="http://www.onvif.org/ver10/schema">
    <soap:Body>
        <trt:GetStreamUri>
            <trt:StreamSetup>
                <tt:Stream>RTP-Unicast</tt:Stream>
                <tt:Transport>
                    <tt:Protocol>RTSP</tt:Protocol>
                </tt:Transport>
            </trt:StreamSetup>
            <trt:ProfileToken>$profileToken</trt:ProfileToken>
        </trt:GetStreamUri>
    </soap:Body>
</soap:Envelope>"""
    }
    
    /**
     * Parses GetProfiles response.
     */
    private fun parseProfilesResponse(response: String): List<MediaProfile> {
        val profiles = mutableListOf<MediaProfile>()
        
        val profileRegex = Regex("""<[^:]*:?Profiles[^>]*token="([^"]+)"[^>]*>.*?</[^:]*:?Profiles>""", RegexOption.DOT_MATCHES_ALL)
        val nameRegex = Regex("""<[^:]*:?Name>([^<]+)</[^:]*:?Name>""")
        val encodingRegex = Regex("""<[^:]*:?Encoding>([^<]+)</[^:]*:?Encoding>""")
        val widthRegex = Regex("""<[^:]*:?Width>([^<]+)</[^:]*:?Width>""")
        val heightRegex = Regex("""<[^:]*:?Height>([^<]+)</[^:]*:?Height>""")
        
        profileRegex.findAll(response).forEach { match ->
            val profileXml = match.value
            val token = match.groupValues[1]
            val name = nameRegex.find(profileXml)?.groupValues?.get(1) ?: token
            
            val encoding = encodingRegex.find(profileXml)?.groupValues?.get(1) ?: "H264"
            val width = widthRegex.find(profileXml)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val height = heightRegex.find(profileXml)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            
            profiles.add(MediaProfile(
                token = token,
                name = name,
                videoEncoderConfig = VideoEncoderConfig(
                    encoding = encoding,
                    width = width,
                    height = height
                )
            ))
        }
        
        return profiles
    }
    
    /**
     * Parses GetStreamUri response.
     */
    private fun parseStreamUriResponse(response: String): String? {
        val uriRegex = Regex("""<[^:]*:?Uri>([^<]+)</[^:]*:?Uri>""", RegexOption.DOT_MATCHES_ALL)
        return uriRegex.find(response)?.groupValues?.get(1)?.trim()?.let { decodeXmlEntities(it) }
    }
    
    /**
     * Decodes common XML/HTML entities in strings extracted from SOAP responses.
     */
    private fun decodeXmlEntities(text: String): String {
        return text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
    }

}
