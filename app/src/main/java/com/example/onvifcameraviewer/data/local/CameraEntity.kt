package com.example.onvifcameraviewer.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for persisting camera data.
 * Stores both ONVIF and manually added cameras.
 */
@Entity(tableName = "cameras")
data class CameraEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val streamUri: String?,
    val manufacturer: String,
    val model: String,
    val serviceUrl: String,
    val ipAddress: String,
    val username: String?,
    val password: String?,
    val rtspUsername: String? = null,
    val isManual: Boolean = false
)
