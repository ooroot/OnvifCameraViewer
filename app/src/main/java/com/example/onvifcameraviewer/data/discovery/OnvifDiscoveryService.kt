package com.example.onvifcameraviewer.data.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.wifi.WifiManager
import android.util.Log
import com.example.onvifcameraviewer.domain.model.OnvifDevice
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

@Singleton
class OnvifDiscoveryService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "OnvifDiscovery"
        private const val WS_DISCOVERY_ADDRESS = "239.255.255.250"
        private const val WS_DISCOVERY_PORT = 3702
        private const val SOCKET_TIMEOUT_MS = 2000
        private const val DISCOVERY_TIMEOUT_MS = 8000L
        private const val BUFFER_SIZE = 65535
        private const val MULTICAST_PROBE_COUNT = 3
        private const val MULTICAST_PROBE_DELAY_MS = 500L
        private const val SUBNET_SCAN_CONNECT_TIMEOUT_MS = 800
        private const val SUBNET_SCAN_READ_TIMEOUT_MS = 2000
        private const val SUBNET_SCAN_PARALLELISM = 20
        private val ONVIF_PORTS = intArrayOf(80, 8080, 8899)
        private const val ONVIF_DEVICE_PATH = "/onvif/device_service"
    }

    private val scanHttpClient = OkHttpClient.Builder()
        .connectTimeout(SUBNET_SCAN_CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(SUBNET_SCAN_READ_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .writeTimeout(2000L, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    fun discoverDevices(timeoutMs: Long = DISCOVERY_TIMEOUT_MS): Flow<OnvifDevice> = flow {
        Log.d(TAG, "Starting ONVIF discovery (multicast + subnet scan)...")

        val discoveredDevices = mutableSetOf<String>()

        // Phase 1: multicast WS-Discovery with retries
        val multicastDevices = runMulticastDiscovery(timeoutMs)
        for (device in multicastDevices) {
            if (device.serviceUrl !in discoveredDevices) {
                discoveredDevices.add(device.serviceUrl)
                Log.d(TAG, "Multicast found: ${device.name} at ${device.ipAddress}")
                emit(device)
            }
        }

        // Phase 2: subnet scan fallback (always run to catch devices that ignore multicast)
        Log.d(TAG, "Starting subnet scan fallback...")
        val subnetDevices = runSubnetScan()
        for (device in subnetDevices) {
            if (device.serviceUrl !in discoveredDevices) {
                discoveredDevices.add(device.serviceUrl)
                Log.d(TAG, "Subnet scan found: ${device.name} at ${device.ipAddress}")
                emit(device)
            }
        }

        Log.d(TAG, "Discovery complete. Found ${discoveredDevices.size} devices total.")
    }.flowOn(Dispatchers.IO)

    // --- Phase 1: Multicast WS-Discovery ---

    private suspend fun runMulticastDiscovery(timeoutMs: Long): List<OnvifDevice> {
        val devices = mutableListOf<OnvifDevice>()
        val seen = mutableSetOf<String>()
        var socket: DatagramSocket? = null
        var multicastLock: WifiManager.MulticastLock? = null

        try {
            multicastLock = acquireMulticastLock()

            socket = DatagramSocket().apply {
                broadcast = true
                soTimeout = SOCKET_TIMEOUT_MS
                reuseAddress = true
            }

            val multicastAddress = InetAddress.getByName(WS_DISCOVERY_ADDRESS)
            val buffer = ByteArray(BUFFER_SIZE)
            val inPacket = DatagramPacket(buffer, buffer.size)
            val startTime = System.currentTimeMillis()

            // Send multiple probes with delays
            for (i in 1..MULTICAST_PROBE_COUNT) {
                if (!coroutineContext.isActive) break
                val probeMessage = buildProbeMessage()
                val outPacket = DatagramPacket(
                    probeMessage.toByteArray(),
                    probeMessage.length,
                    multicastAddress,
                    WS_DISCOVERY_PORT
                )
                Log.d(TAG, "Sending WS-Discovery probe $i/$MULTICAST_PROBE_COUNT")
                socket.send(outPacket)
                if (i < MULTICAST_PROBE_COUNT) delay(MULTICAST_PROBE_DELAY_MS)
            }

            // Listen for responses
            while (coroutineContext.isActive &&
                (System.currentTimeMillis() - startTime) < timeoutMs
            ) {
                try {
                    socket.receive(inPacket)
                    val response = String(inPacket.data, 0, inPacket.length)
                    val senderIp = inPacket.address?.hostAddress ?: ""

                    parseProbeMatch(response, senderIp)?.let { device ->
                        if (device.serviceUrl !in seen) {
                            seen.add(device.serviceUrl)
                            devices.add(device)
                        }
                    }
                } catch (_: SocketTimeoutException) {
                    // Normal timeout, continue
                }
            }

            Log.d(TAG, "Multicast phase found ${devices.size} devices.")
        } catch (e: Exception) {
            Log.e(TAG, "Multicast discovery error: ${e.message}", e)
        } finally {
            closeSocketSafely(socket)
            releaseMulticastLockSafely(multicastLock)
        }

        return devices
    }

    // --- Phase 2: Subnet scan ---

    private suspend fun runSubnetScan(): List<OnvifDevice> {
        val subnet = detectSubnet()
        if (subnet == null) {
            Log.w(TAG, "Could not detect local subnet, skipping subnet scan")
            return emptyList()
        }

        Log.d(TAG, "Scanning subnet ${subnet.network}/${subnet.prefixLength} (${subnet.hostCount} hosts)")

        val devices = mutableListOf<OnvifDevice>()

        coroutineScope {
            // Generate all host IPs for the subnet, skip network/broadcast/self
            val hostIps = subnet.generateHostIps()
            Log.d(TAG, "Probing ${hostIps.size} IPs on ports ${ONVIF_PORTS.joinToString(",")}")

            // Scan in batches for controlled parallelism
            hostIps.chunked(SUBNET_SCAN_PARALLELISM).forEach { batch ->
                if (!coroutineContext.isActive) return@forEach
                val results = batch.map { ip ->
                    async {
                        probeOnvifDevice(ip)
                    }
                }.awaitAll()
                for (device in results.filterNotNull()) {
                    devices.add(device)
                }
            }
        }

        Log.d(TAG, "Subnet scan found ${devices.size} devices.")
        return devices
    }

    private suspend fun probeOnvifDevice(ip: String): OnvifDevice? {
        for (port in ONVIF_PORTS) {
            if (!coroutineContext.isActive) return null

            // Quick TCP connect check before sending SOAP
            if (!isTcpOpen(ip, port)) continue

            try {
                val device = probeOnvifPort(ip, port)
                if (device != null) return device
            } catch (e: Exception) {
                // Not an ONVIF device on this port, continue
            }
        }
        return null
    }

    private fun isTcpOpen(ip: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), SUBNET_SCAN_CONNECT_TIMEOUT_MS)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun probeOnvifPort(ip: String, port: Int): OnvifDevice? {
        val url = "http://$ip:$port$ONVIF_DEVICE_PATH"
        val soapBody = buildGetSystemDateAndTimeRequest()

        val request = Request.Builder()
            .url(url)
            .post(soapBody.toRequestBody("application/soap+xml; charset=utf-8".toMediaType()))
            .header("Connection", "close")
            .build()

        return withTimeoutOrNull(3000L) {
            try {
                val response = scanHttpClient.newCall(request).execute()
                response.use { resp ->
                    if (!resp.isSuccessful) return@withTimeoutOrNull null
                    val body = resp.body?.string() ?: return@withTimeoutOrNull null

                    // Validate this is actually an ONVIF response
                    if (!body.contains("GetSystemDateAndTimeResponse", ignoreCase = true) &&
                        !body.contains("SystemDateAndTime", ignoreCase = true)
                    ) {
                        return@withTimeoutOrNull null
                    }

                    val serviceUrl = if (port == 80) {
                        "http://$ip$ONVIF_DEVICE_PATH"
                    } else {
                        "http://$ip:$port$ONVIF_DEVICE_PATH"
                    }

                    OnvifDevice(
                        id = serviceUrl.hashCode().toString(),
                        name = "Camera @ $ip",
                        manufacturer = "",
                        model = "",
                        serviceUrl = serviceUrl,
                        ipAddress = ip
                    )
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun buildGetSystemDateAndTimeRequest(): String {
        return """<?xml version="1.0" encoding="UTF-8"?>
<soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope"
    xmlns:tds="http://www.onvif.org/ver10/device/wsdl">
    <soap:Body>
        <tds:GetSystemDateAndTime/>
    </soap:Body>
</soap:Envelope>""".trimIndent()
    }

    // --- Subnet detection ---

    private fun detectSubnet(): SubnetInfo? {
        // Method 1: ConnectivityManager LinkProperties (preferred, API 23+)
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = cm?.activeNetwork
            val linkProps = cm?.getLinkProperties(network)
            if (linkProps != null) {
                for (linkAddr in linkProps.linkAddresses) {
                    val addr = linkAddr.address
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val prefix = linkAddr.prefixLength
                        val ip = addr.hostAddress ?: continue
                        Log.d(TAG, "Detected subnet via LinkProperties: $ip/$prefix")
                        return SubnetInfo(ip, prefix)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "LinkProperties subnet detection failed: ${e.message}")
        }

        // Method 2: NetworkInterface enumeration
        try {
            for (netIf in NetworkInterface.getNetworkInterfaces()) {
                if (netIf.isLoopback || !netIf.isUp) continue
                for (ifAddr in netIf.interfaceAddresses) {
                    val addr = ifAddr.address
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        val prefix = ifAddr.networkPrefixLength.toInt()
                        Log.d(TAG, "Detected subnet via NetworkInterface: $ip/$prefix")
                        return SubnetInfo(ip, prefix)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "NetworkInterface subnet detection failed: ${e.message}")
        }

        // Method 3: WifiManager (deprecated but reliable fallback)
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val dhcp = wm?.dhcpInfo
            if (dhcp != null && dhcp.ipAddress != 0) {
                val ip = intToIp(dhcp.ipAddress)
                val mask = dhcp.netmask
                val prefix = if (mask != 0) Integer.bitCount(mask) else 24
                Log.d(TAG, "Detected subnet via WifiManager: $ip/$prefix")
                return SubnetInfo(ip, prefix)
            }
        } catch (e: Exception) {
            Log.w(TAG, "WifiManager subnet detection failed: ${e.message}")
        }

        return null
    }

    private fun intToIp(ip: Int): String {
        return "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
    }

    // --- Helpers ---

    private fun acquireMulticastLock(): WifiManager.MulticastLock? {
        return try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiManager?.createMulticastLock("onvif_discovery")?.apply {
                setReferenceCounted(true)
                acquire()
                Log.d(TAG, "Multicast lock acquired")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire multicast lock: ${e.message}")
            null
        }
    }

    private fun closeSocketSafely(socket: DatagramSocket?) {
        try {
            socket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing socket: ${e.message}")
        }
    }

    private fun releaseMulticastLockSafely(lock: WifiManager.MulticastLock?) {
        try {
            if (lock?.isHeld == true) {
                lock.release()
                Log.d(TAG, "Multicast lock released")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing multicast lock: ${e.message}")
        }
    }

    private fun buildProbeMessage(): String {
        val messageId = UUID.randomUUID().toString()
        return """<?xml version="1.0" encoding="UTF-8"?>
<soap:Envelope 
    xmlns:soap="http://www.w3.org/2003/05/soap-envelope"
    xmlns:wsa="http://schemas.xmlsoap.org/ws/2004/08/addressing"
    xmlns:wsd="http://schemas.xmlsoap.org/ws/2005/04/discovery"
    xmlns:dn="http://www.onvif.org/ver10/network/wsdl">
    <soap:Header>
        <wsa:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</wsa:Action>
        <wsa:MessageID>uuid:$messageId</wsa:MessageID>
        <wsa:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</wsa:To>
    </soap:Header>
    <soap:Body>
        <wsd:Probe>
            <wsd:Types>dn:NetworkVideoTransmitter</wsd:Types>
        </wsd:Probe>
    </soap:Body>
</soap:Envelope>""".trimIndent()
    }

    private fun parseProbeMatch(response: String, senderIp: String): OnvifDevice? {
        if (!response.contains("ProbeMatch")) return null

        return try {
            val xAddrsRegex = Regex("""<[^:]*:?XAddrs>([^<]+)</[^:]*:?XAddrs>""")
            val xAddrsMatch = xAddrsRegex.find(response) ?: return null
            val xAddrs = xAddrsMatch.groupValues[1].trim()

            val serviceUrl = xAddrs.split(" ").firstOrNull()?.trim()
                ?.replace("https://", "http://")
                ?: return null

            val ipAddress = OnvifDevice.extractIpFromUrl(serviceUrl).ifEmpty { senderIp }

            val scopesRegex = Regex("""<[^:]*:?Scopes>([^<]+)</[^:]*:?Scopes>""")
            val scopesMatch = scopesRegex.find(response)
            val scopes = scopesMatch?.groupValues?.get(1) ?: ""

            val manufacturer = extractScopeValue(scopes, "hardware")
                ?: extractScopeValue(scopes, "mfr")
                ?: ""
            val model = extractScopeValue(scopes, "name") ?: ""
            val name = extractScopeValue(scopes, "location")
                ?: model.ifEmpty { "Camera @ $ipAddress" }

            OnvifDevice(
                id = serviceUrl.hashCode().toString(),
                name = name,
                manufacturer = manufacturer,
                model = model,
                serviceUrl = serviceUrl,
                ipAddress = ipAddress
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse probe match: ${e.message}")
            null
        }
    }

    private fun extractScopeValue(scopes: String, key: String): String? {
        return try {
            val regex = Regex("""onvif://www\.onvif\.org/$key/([^\s]+)""", RegexOption.IGNORE_CASE)
            regex.find(scopes)?.groupValues?.get(1)?.let {
                java.net.URLDecoder.decode(it, "UTF-8")
            }
        } catch (e: Exception) {
            null
        }
    }

    // --- Subnet model ---

    private data class SubnetInfo(
        val localIp: String,
        val prefixLength: Int
    ) {
        val network: String
            get() {
                val parts = localIp.split(".")
                if (parts.size != 4) return localIp
                return when {
                    prefixLength >= 24 -> "${parts[0]}.${parts[1]}.${parts[2]}.0"
                    prefixLength >= 16 -> "${parts[0]}.${parts[1]}.0.0"
                    else -> "${parts[0]}.0.0.0"
                }
            }

        val hostCount: Int
            get() {
                val hostBits = 32 - prefixLength.coerceIn(8, 30)
                return (1 shl hostBits) - 2  // minus network + broadcast
            }

        fun generateHostIps(): List<String> {
            val parts = localIp.split(".").map { it.toInt() }
            if (parts.size != 4) return emptyList()

            // For /24 networks (most common home/office): scan .1 to .254
            // For larger subnets: cap at /24 to avoid scanning thousands of IPs
            val effectivePrefix = prefixLength.coerceAtLeast(24)
            val hostBits = 32 - effectivePrefix
            val totalHosts = (1 shl hostBits) - 2
            val baseIp = (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
            val mask = -1 shl hostBits
            val networkAddr = baseIp and mask
            val selfIp = localIp

            val ips = mutableListOf<String>()
            for (i in 1..totalHosts) {
                val hostIp = networkAddr or i
                val ip = "${(hostIp shr 24) and 0xFF}.${(hostIp shr 16) and 0xFF}.${(hostIp shr 8) and 0xFF}.${hostIp and 0xFF}"
                if (ip != selfIp) {
                    ips.add(ip)
                }
            }
            return ips
        }
    }
}
