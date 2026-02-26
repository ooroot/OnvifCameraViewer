package com.example.onvifcameraviewer.ui.components

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.onvifcameraviewer.data.onvif.OnvifMediaService
import com.example.onvifcameraviewer.data.player.RtspPlayerManager
import com.example.onvifcameraviewer.domain.model.CameraUiState
import com.example.onvifcameraviewer.domain.model.ConnectionState
import com.example.onvifcameraviewer.ui.theme.ErrorRed
import com.example.onvifcameraviewer.ui.theme.OfflineGrey
import com.example.onvifcameraviewer.ui.theme.StreamingGreen

/**
 * A camera cell for the grid view.
 * Shows video stream when connected, status indicators otherwise.
 */
@Composable
fun CameraCell(
    camera: CameraUiState,
    playerManager: RtspPlayerManager,
    onTap: () -> Unit,
    onFullscreen: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var isResumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }

    // Track lifecycle resumed state via a stable observer (keyed on camera.id — never restarts)
    DisposableEffect(camera.id) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> { isResumed = true }
                Lifecycle.Event.ON_PAUSE -> { isResumed = false }
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
        }
    }

    // Single player creation/release effect.
    // Keys: camera.id (identity), isResumed (lifecycle), connectionState + streamUri (readiness).
    // Only camera.id is stable — the others cause restart when they change, which is exactly
    // what we want: create player when all conditions met, release when any condition lost.
    DisposableEffect(camera.id, isResumed, camera.connectionState, camera.streamUri) {
        var activePlayer: ExoPlayer? = null

        if (isResumed && camera.connectionState == ConnectionState.STREAMING && camera.streamUri != null) {
            try {
                val streamUri = camera.streamUri
                val creds = camera.credentials
                val playUri = if (creds != null) {
                    OnvifMediaService.embedCredentialsInUri(streamUri, creds)
                } else {
                    streamUri
                }
                activePlayer = playerManager.createGridPlayer(playUri)
                player = activePlayer
            } catch (t: Throwable) {
                Log.e("CameraCell", "Player creation failed for ${camera.id}", t)
                activePlayer = null
                player = null
            }
        }

        onDispose {
            if (activePlayer != null) {
                playerManager.releasePlayer(activePlayer)
                player = null
            }
        }
    }
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clickable { onTap() },
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            when (camera.connectionState) {
                ConnectionState.STREAMING -> {
                    // Video player
                    player?.let { exoPlayer ->
                        AndroidView(
                            factory = { context ->
                                PlayerView(context).apply {
                                    this.player = exoPlayer
                                    useController = false
                                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                                }
                            },
                            update = { view ->
                                view.player = exoPlayer
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    
                    // Top row with delete and fullscreen buttons
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Delete button
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier
                                .size(32.dp)
                                .background(
                                    Color.Black.copy(alpha = 0.5f),
                                    CircleShape
                                )
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Remove camera",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        
                        // Fullscreen button
                        IconButton(
                            onClick = onFullscreen,
                            modifier = Modifier
                                .size(32.dp)
                                .background(
                                    Color.Black.copy(alpha = 0.5f),
                                    CircleShape
                                )
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Fullscreen,
                                contentDescription = "Fullscreen",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                
                ConnectionState.DISCONNECTED -> {
                    DisconnectedOverlay(camera.device.name) { onTap() }
                    // Delete button for disconnected cameras
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                            .size(32.dp)
                            .background(
                                Color.Black.copy(alpha = 0.5f),
                                CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Remove camera",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                
                ConnectionState.CONNECTING,
                ConnectionState.AUTHENTICATING,
                ConnectionState.LOADING_PROFILES -> {
                    ConnectingOverlay()
                }
                
                ConnectionState.ERROR -> {
                    ErrorOverlay(camera.errorMessage ?: "Error") { onTap() }
                    // Delete button for error state
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                            .size(32.dp)
                            .background(
                                Color.Black.copy(alpha = 0.5f),
                                CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Remove camera",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            
            // Camera info overlay
            CameraInfoBar(
                name = camera.device.name,
                state = camera.connectionState,
                modifier = Modifier.align(Alignment.BottomStart)
            )
        }
    }
}

@Composable
private fun DisconnectedOverlay(@Suppress("UNUSED_PARAMETER") cameraName: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "Connect",
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                        CircleShape
                    )
                    .padding(8.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Tap to connect",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ConnectingOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorOverlay(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.errorContainer)
            .clickable { onRetry() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = "Retry",
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = 2
            )
        }
    }
}

@Composable
private fun CameraInfoBar(
    name: String,
    state: ConnectionState,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Status indicator
        val (icon, color) = when (state) {
            ConnectionState.STREAMING -> Icons.Filled.Videocam to StreamingGreen
            ConnectionState.ERROR -> Icons.Filled.Error to ErrorRed
            ConnectionState.DISCONNECTED -> Icons.Filled.VideocamOff to OfflineGrey
            else -> Icons.Filled.Videocam to Color.Yellow
        }
        
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = color
        )
        
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}
