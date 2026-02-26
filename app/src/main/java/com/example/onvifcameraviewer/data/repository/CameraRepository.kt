package com.example.onvifcameraviewer.data.repository

import com.example.onvifcameraviewer.data.discovery.OnvifDiscoveryService
import com.example.onvifcameraviewer.data.local.CameraDao
import com.example.onvifcameraviewer.data.local.CameraEntity
import com.example.onvifcameraviewer.data.onvif.OnvifMediaService
import com.example.onvifcameraviewer.domain.exception.OnvifException
import com.example.onvifcameraviewer.domain.model.Credentials
import com.example.onvifcameraviewer.domain.model.MediaProfile
import com.example.onvifcameraviewer.domain.model.OnvifDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for camera operations.
 * Provides a clean API for the domain layer to interact with cameras.
 */
@Singleton
class CameraRepository @Inject constructor(
    private val discoveryService: OnvifDiscoveryService,
    private val mediaService: OnvifMediaService,
    private val cameraDao: CameraDao
) {
    
    /**
     * Gets all saved cameras from the database as a Flow.
     */
    fun getSavedCameras(): Flow<List<SavedCamera>> {
        return cameraDao.getAllCameras().map { entities ->
            entities.map { entity -> entity.toSavedCamera() }
        }
    }
    
    /**
     * Saves a camera to the database.
     */
    suspend fun saveCamera(
        id: String,
        name: String,
        streamUri: String?,
        device: OnvifDevice,
        credentials: Credentials?,
        isManual: Boolean
    ) {
        val entity = CameraEntity(
            id = id,
            name = name,
            streamUri = streamUri,
            manufacturer = device.manufacturer,
            model = device.model,
            serviceUrl = device.serviceUrl,
            ipAddress = device.ipAddress,
            username = credentials?.username,
            password = credentials?.password,
            rtspUsername = credentials?.rtspUsername,
            isManual = isManual
        )
        cameraDao.insertCamera(entity)
    }
    
    /**
     * Deletes a camera from the database.
     */
    suspend fun deleteCamera(id: String) {
        cameraDao.deleteCameraById(id)
    }
    
    /**
     * Discovers ONVIF devices on the local network.
     */
    fun discoverCameras(timeoutMs: Long = 8000): Flow<OnvifDevice> {
        return discoveryService.discoverDevices(timeoutMs)
    }
    
    /**
     * Retrieves available media profiles from a camera.
     */
    suspend fun getProfiles(
        device: OnvifDevice,
        credentials: Credentials
    ): Result<List<MediaProfile>> {
        return mediaService.getProfiles(device.serviceUrl, credentials)
    }
    
    /**
     * Retrieves the RTSP stream URI for a camera profile.
     */
    suspend fun getStreamUri(
        device: OnvifDevice,
        credentials: Credentials,
        profileToken: String
    ): Result<String> {
        return mediaService.getStreamUri(
            deviceUrl = device.serviceUrl,
            credentials = credentials,
            profileToken = profileToken,
            useUdp = false  // Use TCP for reliability
        )
    }
    
    /**
     * Gets the sub-stream URI (lower quality, for grid view).
     * Accepts pre-fetched profiles to avoid a redundant SOAP round-trip.
     */
    suspend fun getSubStreamUri(
        device: OnvifDevice,
        credentials: Credentials,
        profiles: List<MediaProfile>? = null
    ): Result<String> {
        val resolvedProfiles = profiles ?: run {
            val profilesResult = getProfiles(device, credentials)
            if (profilesResult.isFailure) {
                return Result.failure(
                    profilesResult.exceptionOrNull()
                        ?: OnvifException.NetworkException("Unknown error getting profiles")
                )
            }
            profilesResult.getOrNull() ?: emptyList()
        }

        if (resolvedProfiles.isEmpty()) {
            return Result.failure(Exception("No profiles available"))
        }
        
        val subStreamProfile = resolvedProfiles.find { profile ->
            val config = profile.videoEncoderConfig
            config != null && config.width <= 720 && config.height <= 576
        } ?: resolvedProfiles.last()
        
        return getStreamUri(device, credentials, subStreamProfile.token)
    }
    
    /**
     * Gets the main-stream URI (highest quality, for fullscreen view).
     * Accepts pre-fetched profiles to avoid a redundant SOAP round-trip.
     */
    suspend fun getMainStreamUri(
        device: OnvifDevice,
        credentials: Credentials,
        profiles: List<MediaProfile>? = null
    ): Result<String> {
        val resolvedProfiles = profiles ?: run {
            val profilesResult = getProfiles(device, credentials)
            if (profilesResult.isFailure) {
                return Result.failure(
                    profilesResult.exceptionOrNull()
                        ?: OnvifException.NetworkException("Unknown error getting profiles")
                )
            }
            profilesResult.getOrNull() ?: emptyList()
        }

        if (resolvedProfiles.isEmpty()) {
            return Result.failure(Exception("No profiles available"))
        }
        
        return getStreamUri(device, credentials, resolvedProfiles.first().token)
    }
    
    private fun CameraEntity.toSavedCamera() = SavedCamera(
        id = id,
        name = name,
        streamUri = streamUri,
        device = OnvifDevice(
            id = id,
            name = name,
            manufacturer = manufacturer,
            model = model,
            serviceUrl = serviceUrl,
            ipAddress = ipAddress
        ),
        credentials = if (username != null) Credentials(username, password ?: "", rtspUsername = rtspUsername) else null,
        isManual = isManual
    )
}

/**
 * Data class representing a camera loaded from the database.
 */
data class SavedCamera(
    val id: String,
    val name: String,
    val streamUri: String?,
    val device: OnvifDevice,
    val credentials: Credentials?,
    val isManual: Boolean
)

