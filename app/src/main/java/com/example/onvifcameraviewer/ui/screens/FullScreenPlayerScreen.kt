package com.example.onvifcameraviewer.ui.screens

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Error
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.onvifcameraviewer.data.onvif.OnvifMediaService
import com.example.onvifcameraviewer.data.player.RtspPlayerManager
import com.example.onvifcameraviewer.domain.model.CameraUiState

/**
 * Fullscreen video player screen for a single camera.
 * Uses main-stream (higher quality) for better viewing experience.
 */
@Composable
fun FullScreenPlayerScreen(
    camera: CameraUiState,
    mainStreamUri: String?,
    playerManager: RtspPlayerManager,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    
    // Hide system bars for immersive fullscreen
    DisposableEffect(Unit) {
        val window = (context as Activity).window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    
    // Initialize player with main stream and clean up on dispose.
    // Using DisposableEffect (not LaunchedEffect) ensures the player created inside
    // is always released in onDispose, avoiding a race where LaunchedEffect sets
    // the player reference after a separate DisposableEffect's onDispose already ran.
    val scope = rememberCoroutineScope()
    DisposableEffect(mainStreamUri) {
        var activePlayer: ExoPlayer? = null
        
        val job = scope.launch {
            val cleanUri = mainStreamUri ?: camera.streamUri
            if (cleanUri != null) {
                val uri = if (camera.credentials != null) {
                    OnvifMediaService.embedCredentialsInUri(cleanUri, camera.credentials)
                } else {
                    cleanUri
                }
                // Small delay to allow grid player to fully release decoder
                delay(300)
                try {
                    val newPlayer = playerManager.createFullscreenPlayer(uri) { playbackError ->
                        error = playbackError.message
                        isLoading = false
                    }
                    activePlayer = newPlayer
                    player = newPlayer
                    isLoading = false
                } catch (e: Exception) {
                    error = "Failed to start player: ${e.message}"
                    isLoading = false
                }
            } else {
                error = "No stream available"
                isLoading = false
            }
        }
        
        onDispose {
            job.cancel()
            // Release whichever player was created (activePlayer is set in the
            // same scope, and player may have been reassigned by another effect)
            val p = activePlayer ?: player
            if (p != null) {
                playerManager.releasePlayer(p)
                player = null
            }
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        when {
            isLoading -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White
                )
            }
            
            error != null -> {
                ErrorContent(
                    message = error!!,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            
            else -> {
                // Video player
                player?.let { exoPlayer ->
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                this.player = exoPlayer
                                useController = true
                                controllerShowTimeoutMs = 3000
                                setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                            }
                        },
                        update = { view ->
                            view.player = exoPlayer
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        
        // Back button
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }
        
        // Camera name
        Text(
            text = camera.device.name,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 20.dp)
        )
    }
}

@Composable
private fun ErrorContent(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Error,
            contentDescription = null,
            tint = Color.Red,
            modifier = Modifier.size(48.dp)
        )
        Text(
            text = message,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 64.dp)
        )
    }
}
