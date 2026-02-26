package com.example.onvifcameraviewer.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.onvifcameraviewer.data.onvif.OnvifMediaService
import com.example.onvifcameraviewer.data.player.RtspPlayerManager
import com.example.onvifcameraviewer.data.repository.CameraRepository
import com.example.onvifcameraviewer.domain.exception.OnvifException
import com.example.onvifcameraviewer.domain.model.CameraUiState
import com.example.onvifcameraviewer.domain.model.ConnectionState
import com.example.onvifcameraviewer.domain.model.Credentials
import com.example.onvifcameraviewer.domain.model.OnvifDevice
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Main screen UI state.
 */
data class MainScreenState(
    val cameras: List<CameraUiState> = emptyList(),
    val isScanning: Boolean = false,
    val showAuthDialog: Boolean = false,
    val showManualAddDialog: Boolean = false,
    val showDeleteDialog: Boolean = false,
    val selectedCameraId: String? = null,
    val errorMessage: String? = null
)

/**
 * ViewModel for the camera grid screen.
 * Manages camera discovery, authentication, and streaming state.
 */
@HiltViewModel
class CameraViewModel @Inject constructor(
    private val cameraRepository: CameraRepository,
    val playerManager: RtspPlayerManager
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(MainScreenState())
    val uiState: StateFlow<MainScreenState> = _uiState.asStateFlow()
    
    companion object {
        private const val TAG = "CameraViewModel"

        // Trust-all SSLSocketFactory for RTSPS probes (cameras with TLS on port 554).
        // Same trust policy as RtspPlayerManager — safe on private LAN.
        private val trustAllSslSocketFactory: SSLSocketFactory by lazy {
            val trustAll = arrayOf<javax.net.ssl.TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            SSLContext.getInstance("TLS").apply {
                init(null, trustAll, SecureRandom())
            }.socketFactory
        }
    }
    
    init {
        loadSavedCameras()
    }
    
    /**
     * Loads saved cameras from the database.
     */
    private fun loadSavedCameras() {
        viewModelScope.launch {
            try {
                cameraRepository.getSavedCameras().collect { savedCameras ->
                    val cameraStates = savedCameras.map { saved ->
                        CameraUiState(
                            id = saved.id,
                            device = saved.device,
                            credentials = saved.credentials,
                            streamUri = saved.streamUri,
                            // Saved cameras with credentials + stream URI auto-reconnect;
                            // those without need manual connection
                            connectionState = if (saved.streamUri != null && saved.credentials != null) 
                                ConnectionState.STREAMING 
                            else 
                                ConnectionState.DISCONNECTED
                        )
                    }
                    _uiState.update { it.copy(cameras = cameraStates) }
                    Log.d(TAG, "Loaded ${cameraStates.size} saved cameras")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading saved cameras", e)
            }
        }
    }
    
    /**
     * Starts scanning for ONVIF cameras on the network.
     * Preserves manually added cameras during discovery.
     */
    fun startDiscovery() {
        if (_uiState.value.isScanning) return
        
        // Preserve cameras that are manual, saved (have credentials), or actively streaming.
        // Only discard disconnected ONVIF cameras that were from a previous discovery
        // but never authenticated — those will be re-discovered.
        val preservedCameras = _uiState.value.cameras.filter { cam ->
            val isManual = cam.device.serviceUrl.isEmpty()
            val hasSavedCredentials = cam.credentials != null
            val isStreaming = cam.connectionState == ConnectionState.STREAMING
            isManual || hasSavedCredentials || isStreaming
        }
        _uiState.update { it.copy(isScanning = true, cameras = preservedCameras) }
        
        viewModelScope.launch {
            try {
                cameraRepository.discoverCameras().collect { device ->
                    addDiscoveredCamera(device)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Normal cancellation, don't show error
            } catch (e: Exception) {
                _uiState.update { 
                    it.copy(errorMessage = "Discovery failed: ${e.message ?: "Unknown error"}") 
                }
            } finally {
                _uiState.update { it.copy(isScanning = false) }
            }
        }
    }
    
    /**
     * Adds a newly discovered camera to the UI state.
     */
    private fun addDiscoveredCamera(device: OnvifDevice) {
        _uiState.update { state ->
            // Check for existing camera by ID or by IP address (discovery methods
            // produce different IDs for the same physical camera)
            val existingById = state.cameras.any { it.id == device.id }
            val existingByIp = state.cameras.any { 
                it.device.ipAddress == device.ipAddress && it.device.serviceUrl.isNotEmpty() 
            }
            
            if (existingById || existingByIp) {
                // Camera already present (saved or from earlier in this scan). 
                // Update the serviceUrl on existing entries that match by IP but
                // may have a stale or empty serviceUrl (e.g. loaded from DB before
                // discovery ran).
                state.copy(cameras = state.cameras.map { cam ->
                    if (cam.device.ipAddress == device.ipAddress && 
                        cam.device.serviceUrl.isEmpty() &&
                        device.serviceUrl.isNotEmpty()) {
                        cam.copy(device = cam.device.copy(serviceUrl = device.serviceUrl))
                    } else {
                        cam
                    }
                })
            } else {
                val cameraState = CameraUiState(
                    id = device.id,
                    device = device,
                    connectionState = ConnectionState.DISCONNECTED
                )
                state.copy(cameras = state.cameras + cameraState)
            }
        }
    }
    
    /**
     * Opens the authentication dialog for a camera.
     */
    fun requestAuthentication(cameraId: String) {
        _uiState.update { 
            it.copy(showAuthDialog = true, selectedCameraId = cameraId) 
        }
    }
    
    /**
     * Dismisses the authentication dialog.
     */
    fun dismissAuthDialog() {
        _uiState.update { 
            it.copy(showAuthDialog = false, selectedCameraId = null) 
        }
    }
    
    /**
     * Authenticates and connects to a camera.
     */
    fun connectCamera(cameraId: String, username: String, password: String, rtspUsername: String? = null) {
        val camera = _uiState.value.cameras.find { it.id == cameraId } ?: return
        val credentials = Credentials(username.trim(), password.trim(), rtspUsername = rtspUsername?.trim())
        
        updateCameraState(cameraId) { 
            it.copy(
                credentials = credentials,
                connectionState = ConnectionState.AUTHENTICATING
            ) 
        }
        dismissAuthDialog()
        
        viewModelScope.launch {
            try {
                // Get profiles first
                updateCameraState(cameraId) { 
                    it.copy(connectionState = ConnectionState.LOADING_PROFILES) 
                }
                
                val profilesResult = cameraRepository.getProfiles(camera.device, credentials)
                if (profilesResult.isFailure) {
                    throw profilesResult.exceptionOrNull()!!
                }
                val profiles = profilesResult.getOrNull() ?: emptyList()
                
                // Get sub-stream URI for grid view (pass profiles to avoid redundant SOAP call)
                val streamResult = cameraRepository.getSubStreamUri(camera.device, credentials, profiles)
                if (streamResult.isFailure) {
                    throw streamResult.exceptionOrNull()!!
                }
                val streamUri = streamResult.getOrNull()!!
                
                updateCameraState(cameraId) { 
                    it.copy(
                        profiles = profiles,
                        selectedProfileToken = profiles.lastOrNull()?.token,
                        streamUri = streamUri,
                        connectionState = ConnectionState.STREAMING
                    ) 
                }
                
                // Save camera to database for persistence
                try {
                    cameraRepository.saveCamera(
                        id = cameraId,
                        name = camera.device.name,
                        streamUri = streamUri,
                        device = camera.device,
                        credentials = credentials,
                        isManual = false
                    )
                    Log.d(TAG, "Saved camera to database: ${camera.device.name}")
                } catch (saveError: Exception) {
                    Log.e(TAG, "Failed to save camera to database", saveError)
                }
            } catch (e: Exception) {
                // If ONVIF SOAP auth fails, try common RTSP URL patterns as last resort
                val ip = camera.device.ipAddress
                if (e is OnvifException.AuthenticationException && ip.isNotEmpty()) {
                    Log.w(TAG, "ONVIF SOAP auth failed for $ip, trying common RTSP URL patterns")
                    val fallbackUri = tryRtspUrlPatterns(ip, credentials)
                    if (fallbackUri != null) {
                        Log.d(TAG, "RTSP fallback succeeded for $ip: $fallbackUri")
                        updateCameraState(cameraId) {
                            it.copy(
                                streamUri = fallbackUri,
                                connectionState = ConnectionState.STREAMING
                            )
                        }
                        try {
                            cameraRepository.saveCamera(
                                id = cameraId,
                                name = camera.device.name,
                                streamUri = fallbackUri,
                                device = camera.device,
                                credentials = credentials,
                                isManual = false
                            )
                            Log.d(TAG, "Saved camera (RTSP fallback) to database: ${camera.device.name}")
                        } catch (saveError: Exception) {
                            Log.e(TAG, "Failed to save camera to database", saveError)
                        }
                        return@launch
                    } else {
                        Log.w(TAG, "RTSP fallback also failed for $ip — no known URL pattern matched")
                    }
                }

                val userMessage = when (e) {
                    is OnvifException.AuthenticationException -> {
                        if (ip.isNotEmpty()) {
                            "ONVIF auth failed and no RTSP stream found"
                        } else {
                            "Wrong username or password"
                        }
                    }
                    is OnvifException.NetworkException -> e.message ?: "Network error"
                    is OnvifException.NoProfilesException -> "Camera has no streaming profiles"
                    is OnvifException.TimeoutException -> "Connection timed out - check camera IP"
                    is OnvifException.StreamUriException -> "Failed to get stream URL"
                    else -> e.message ?: "Connection failed"
                }
                updateCameraState(cameraId) { 
                    it.copy(
                        connectionState = ConnectionState.ERROR,
                        errorMessage = userMessage
                    ) 
                }
            }
        }
    }

    /**
     * Tries common RTSP URL patterns for IP cameras.
     * Sends RTSP DESCRIBE to each URL to validate the stream path exists.
     * (OPTIONS gives false positives — many cameras respond 200 to OPTIONS on any path.)
     * Auto-detects TLS by testing the first pattern with plaintext — if connection
     * reset, switches to TLS for all subsequent probes.
     * Returns the first working URI, or null if none work.
     */
    private suspend fun tryRtspUrlPatterns(ip: String, credentials: Credentials): String? {
        val patterns = listOf(
            // Hikvision / HiLook / CP PLUS (Hikvision OEM)
            "rtsp://$ip:554/Streaming/Channels/102",
            "rtsp://$ip:554/Streaming/Channels/101",
            "rtsp://$ip:554/Streaming/channels/102",
            "rtsp://$ip:554/Streaming/channels/101",
            // Dahua / CP PLUS (Dahua OEM) / Amcrest — all known variants
            "rtsp://$ip:554/cam/realmonitor?channel=1&subtype=1",
            "rtsp://$ip:554/cam/realmonitor?channel=1&subtype=0",
            "rtsp://$ip:554/cam/realmonitor",
            "rtsp://$ip:554/live",
            "rtsp://$ip:554/live/av0",
            "rtsp://$ip:554/live/av1",
            "rtsp://$ip:554/live/main",
            "rtsp://$ip:554/live/sub",
            "rtsp://$ip:554/media/video1",
            "rtsp://$ip:554/media/video2",
            "rtsp://$ip:554/VideoInput/1/h264/1",
            "rtsp://$ip:554/VideoInput/1/mpeg4/1",
            "rtsp://$ip:554/videostream.asf",
            "rtsp://$ip:554/videostream.cgi",
            // ONVIF generic / Reolink
            "rtsp://$ip:554/h264Preview_01_sub",
            "rtsp://$ip:554/h264Preview_01_main",
            // Axis
            "rtsp://$ip:554/axis-media/media.amp",
            // Generic paths
            "rtsp://$ip:554/stream1",
            "rtsp://$ip:554/stream2",
            "rtsp://$ip:554/live/ch0",
            "rtsp://$ip:554/live/ch1",
            "rtsp://$ip:554/ch0_0.h264",
            "rtsp://$ip:554/ch0_1.h264",
            "rtsp://$ip:554/1",
            "rtsp://$ip:554/2",
            "rtsp://$ip:554/0",
            // ONVIF default media profiles
            "rtsp://$ip:554/onvif1",
            "rtsp://$ip:554/onvif2",
            "rtsp://$ip:554/onvif/profile1/media.smp",
            "rtsp://$ip:554/onvif/profile2/media.smp",
            "rtsp://$ip:554/MediaInput/h264",
            "rtsp://$ip:554/MediaInput/h264/stream_1",
            "rtsp://$ip:554/video1",
            "rtsp://$ip:554/video2",
            // Bare root (last resort — some cameras serve default stream here)
            "rtsp://$ip:554/"
        )

        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // First check if RTSP port is even reachable
            try {
                val socket = java.net.Socket()
                socket.connect(java.net.InetSocketAddress(ip, 554), 2000)
                socket.close()
                Log.d(TAG, "RTSP port 554 reachable on $ip")
            } catch (e: Exception) {
                Log.w(TAG, "RTSP port 554 not reachable on $ip: ${e.message}")
                return@withContext null
            }

            // Auto-detect TLS: try first pattern with plaintext. If connection reset,
            // the camera likely requires TLS (RTSPS) on port 554.
            var useTls = false
            try {
                val firstResult = probeRtspUrl(ip, patterns[0], credentials, useTls = false)
                when (firstResult) {
                    ProbeResult.OK -> {
                        Log.d(TAG, "RTSP probe (plaintext) succeeded for first pattern: ${patterns[0]}")
                        return@withContext patterns[0]
                    }
                    ProbeResult.AUTH_FAILED -> {
                        // Credentials wrong for RTSP — no point trying more URLs
                        Log.w(TAG, "RTSP Digest auth failed (plaintext) — credentials likely wrong, aborting probe")
                        return@withContext null
                    }
                    ProbeResult.FORBIDDEN -> {
                        // Camera is blocking us (lockout?) — abort to avoid making it worse
                        Log.w(TAG, "RTSP 403 Forbidden (plaintext) — camera may be locked out, aborting probe")
                        return@withContext null
                    }
                    ProbeResult.NOT_FOUND, ProbeResult.ERROR -> { /* continue */ }
                }
            } catch (e: java.net.SocketException) {
                // "Connection reset" — strong signal of RTSPS
                Log.d(TAG, "Plaintext RTSP probe got ${e.javaClass.simpleName}: ${e.message} — switching to TLS")
                useTls = true
            } catch (e: Exception) {
                Log.v(TAG, "RTSP probe failed for ${patterns[0]}: ${e.message}")
            }

            // If we switched to TLS, re-try the first pattern with TLS
            val startIndex = if (useTls) 0 else 1
            var consecutiveAuthFailures = 0

            // Try each pattern (with TLS if detected)
            for (i in startIndex until patterns.size) {
                val pattern = patterns[i]
                try {
                    val result = probeRtspUrl(ip, pattern, credentials, useTls = useTls)
                    when (result) {
                        ProbeResult.OK -> {
                            Log.d(TAG, "RTSP probe succeeded for: $pattern (tls=$useTls)")
                            return@withContext pattern
                        }
                        ProbeResult.AUTH_FAILED -> {
                            consecutiveAuthFailures++
                            // If 3+ consecutive auth failures, credentials are wrong — stop hammering
                            if (consecutiveAuthFailures >= 3) {
                                Log.w(TAG, "RTSP Digest auth failed $consecutiveAuthFailures times consecutively — aborting probe to avoid lockout")
                                return@withContext null
                            }
                        }
                        ProbeResult.FORBIDDEN -> {
                            // 403 = camera is blocking/locked out — abort immediately
                            Log.w(TAG, "RTSP 403 Forbidden on $pattern — camera locked out, aborting probe")
                            return@withContext null
                        }
                        ProbeResult.NOT_FOUND -> {
                            consecutiveAuthFailures = 0  // Auth worked, just wrong path
                        }
                        ProbeResult.ERROR -> {
                            consecutiveAuthFailures = 0
                        }
                    }
                } catch (e: Exception) {
                    Log.v(TAG, "RTSP probe failed for $pattern: ${e.message}")
                    consecutiveAuthFailures = 0
                }
            }
            Log.w(TAG, "No RTSP URL pattern worked for $ip (tls=$useTls)")
            null
        }
    }

    private enum class ProbeResult {
        OK,          // 200 — path exists, stream available
        NOT_FOUND,   // 404 — auth OK but path doesn't exist
        AUTH_FAILED, // 401 after our Digest attempt — credentials wrong
        FORBIDDEN,   // 403 — camera blocking us (lockout)
        ERROR        // any other non-200 response or network error
    }

    /**
     * Probes an RTSP URL by sending authenticated DESCRIBE to verify the path exists.
     *
     * Two-phase approach to eliminate false positives from cameras that return 401
     * for ANY path when unauthenticated, then 404 after auth for non-existent paths:
     *   1. Send DESCRIBE without auth → if 200, path valid (no auth needed)
     *   2. If 401, parse WWW-Authenticate, compute Digest response, send authenticated DESCRIBE
     *      → 200 = valid, 404 = path doesn't exist, anything else = skip
     *
     * @param useTls If true, wraps TCP with TLS (for RTSPS cameras on port 554).
     */
    private fun probeRtspUrl(ip: String, rtspUrl: String, credentials: Credentials, useTls: Boolean = false): ProbeResult {
        val rawSocket = java.net.Socket()
        var tlsSocket: java.net.Socket? = null
        try {
            rawSocket.connect(java.net.InetSocketAddress(ip, 554), 2000)

            val activeSocket = if (useTls) {
                val s = trustAllSslSocketFactory.createSocket(rawSocket, ip, 554, true)
                tlsSocket = s
                s
            } else {
                rawSocket
            }
            activeSocket.soTimeout = 3000

            val out = activeSocket.getOutputStream()
            val input = activeSocket.getInputStream()

            // Phase 1: unauthenticated DESCRIBE
            val cseq1 = 1
            val request1 = "DESCRIBE $rtspUrl RTSP/1.0\r\nCSeq: $cseq1\r\nAccept: application/sdp\r\nUser-Agent: OnvifCameraViewer\r\n\r\n"
            out.write(request1.toByteArray(Charsets.US_ASCII))
            out.flush()

            val buffer1 = ByteArray(2048)
            val bytesRead1 = input.read(buffer1)
            if (bytesRead1 <= 0) return ProbeResult.ERROR

            val response1 = String(buffer1, 0, bytesRead1, Charsets.US_ASCII)
            val statusLine1 = response1.lineSequence().firstOrNull() ?: ""

            // 200 = path exists, no auth required
            if (statusLine1.contains(" 200 ")) {
                Log.d(TAG, "RTSP DESCRIBE $rtspUrl -> 200 OK (no auth)")
                return ProbeResult.OK
            }

            // 403 = camera blocking us
            if (statusLine1.contains(" 403 ")) {
                Log.d(TAG, "RTSP DESCRIBE $rtspUrl (tls=$useTls) -> 403 Forbidden (no auth)")
                return ProbeResult.FORBIDDEN
            }

            // Not 401 = can't proceed with auth (e.g. 404, etc.)
            if (!statusLine1.contains(" 401 ")) {
                Log.v(TAG, "RTSP DESCRIBE $rtspUrl (tls=$useTls) -> $statusLine1")
                if (statusLine1.contains(" 404 ")) return ProbeResult.NOT_FOUND
                return ProbeResult.ERROR
            }

            // Phase 2: parse 401 challenge and send authenticated DESCRIBE
            val wwwAuthLine = response1.lineSequence()
                .firstOrNull { it.startsWith("WWW-Authenticate:", ignoreCase = true) }
            if (wwwAuthLine == null) {
                Log.v(TAG, "RTSP 401 but no WWW-Authenticate header for $rtspUrl")
                return ProbeResult.AUTH_FAILED
            }

            val wwwAuthValue = wwwAuthLine.substringAfter(":", "").trim()
            val challenge = OnvifMediaService.parseDigestChallenge(wwwAuthValue)
            if (challenge == null) {
                Log.v(TAG, "RTSP 401 but couldn't parse Digest challenge for $rtspUrl: ${wwwAuthValue.take(100)}")
                return ProbeResult.AUTH_FAILED
            }

            val username = credentials.effectiveRtspUsername
            val password = credentials.password
            val authHeader = OnvifMediaService.computeDigestResponse(
                challenge, username, password, "DESCRIBE", rtspUrl
            )

            val cseq2 = 2
            val request2 = "DESCRIBE $rtspUrl RTSP/1.0\r\nCSeq: $cseq2\r\nAccept: application/sdp\r\nAuthorization: $authHeader\r\nUser-Agent: OnvifCameraViewer\r\n\r\n"
            out.write(request2.toByteArray(Charsets.US_ASCII))
            out.flush()

            val buffer2 = ByteArray(4096)
            val bytesRead2 = input.read(buffer2)
            if (bytesRead2 <= 0) return ProbeResult.ERROR

            val response2 = String(buffer2, 0, bytesRead2, Charsets.US_ASCII)
            val statusLine2 = response2.lineSequence().firstOrNull() ?: ""

            Log.d(TAG, "RTSP auth DESCRIBE $rtspUrl -> ${statusLine2.trim()}")

            return when {
                statusLine2.contains(" 200 ") -> ProbeResult.OK
                statusLine2.contains(" 404 ") -> ProbeResult.NOT_FOUND
                statusLine2.contains(" 401 ") -> ProbeResult.AUTH_FAILED
                statusLine2.contains(" 403 ") -> ProbeResult.FORBIDDEN
                else -> ProbeResult.ERROR
            }
        } finally {
            if (tlsSocket != null) {
                try { tlsSocket.close() } catch (_: Exception) {}
            } else {
                try { rawSocket.close() } catch (_: Exception) {}
            }
        }
    }
    
    /**
     * Updates the state of a specific camera.
     */
    private fun updateCameraState(cameraId: String, update: (CameraUiState) -> CameraUiState) {
        _uiState.update { state ->
            state.copy(
                cameras = state.cameras.map { camera ->
                    if (camera.id == cameraId) update(camera) else camera
                }
            )
        }
    }
    
    /**
     * Opens the manual camera add dialog.
     */
    fun showManualAddDialog() {
        _uiState.update { it.copy(showManualAddDialog = true) }
    }
    
    /**
     * Dismisses the manual camera add dialog.
     */
    fun dismissManualAddDialog() {
        _uiState.update { it.copy(showManualAddDialog = false) }
    }
    
    /**
     * Adds a camera manually with a direct stream URL.
     * Used for non-ONVIF cameras like IP Webcam.
     */
    fun addManualCamera(name: String, streamUrl: String, username: String, password: String) {
        val id = "manual_${System.currentTimeMillis()}"
        val ipAddress = OnvifDevice.extractIpFromUrl(streamUrl).ifEmpty { "Unknown" }
        
        val device = OnvifDevice(
            id = id,
            name = name,
            manufacturer = "Manual",
            model = "",
            serviceUrl = "",
            ipAddress = ipAddress
        )
        
        val credentials = if (username.isNotBlank()) {
            Credentials(username.trim(), password.trim())
        } else {
            null
        }
        
        val cameraState = CameraUiState(
            id = id,
            device = device,
            credentials = credentials,
            streamUri = streamUrl,
            connectionState = ConnectionState.STREAMING
        )
        
        _uiState.update { state ->
            state.copy(
                cameras = state.cameras + cameraState,
                showManualAddDialog = false
            )
        }
        
        // Save to database
        viewModelScope.launch {
            try {
                cameraRepository.saveCamera(
                    id = id,
                    name = name,
                    streamUri = streamUrl,
                    device = device,
                    credentials = credentials,
                    isManual = true
                )
                Log.d(TAG, "Saved manual camera: $name")
            } catch (e: Exception) {
                Log.e(TAG, "Error saving camera", e)
            }
        }
    }
    
    /**
     * Clears error message.
     */
    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
    
    /**
     * Shows the delete confirmation dialog for a camera.
     */
    fun showDeleteDialog(cameraId: String) {
        _uiState.update { 
            it.copy(showDeleteDialog = true, selectedCameraId = cameraId) 
        }
    }
    
    /**
     * Dismisses the delete confirmation dialog.
     */
    fun dismissDeleteDialog() {
        _uiState.update { 
            it.copy(showDeleteDialog = false, selectedCameraId = null) 
        }
    }
    
    /**
     * Deletes a camera from both UI state and database.
     */
    fun deleteCamera(cameraId: String) {
        viewModelScope.launch {
            try {
                // Remove from database
                cameraRepository.deleteCamera(cameraId)
                
                // Remove from UI state
                _uiState.update { state ->
                    state.copy(
                        cameras = state.cameras.filter { it.id != cameraId },
                        showDeleteDialog = false,
                        selectedCameraId = null
                    )
                }
                Log.d(TAG, "Deleted camera: $cameraId")
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting camera", e)
                _uiState.update { 
                    it.copy(errorMessage = "Failed to delete camera: ${e.message}") 
                }
            }
        }
    }
    
    /**
     * Gets the main stream URI for fullscreen view.
     */
    suspend fun getMainStreamUri(camera: CameraUiState): String? {
        val credentials = camera.credentials ?: return null
        val profiles = camera.profiles.ifEmpty { null }
        val result = cameraRepository.getMainStreamUri(camera.device, credentials, profiles)
        return result.getOrNull()
    }
}
