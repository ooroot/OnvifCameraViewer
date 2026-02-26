package com.example.onvifcameraviewer.data.player

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Manages ExoPlayer instances for RTSP and HTTP streaming.
 * 
 * Optimized for ONVIF camera streams with:
 * - Low-latency Live Configuration
 * - Reliable RTSP over TCP
 * - Custom LoadControl for high-res main streams
 */
@Singleton
class RtspPlayerManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    companion object {
        private const val TAG = "RtspPlayerManager"

        // Trust-all SSLSocketFactory for cameras with self-signed certs on local LAN.
        // RTSPS cameras (like CP PLUS / Dahua) require TLS on port 554.
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
    
    /**
     * Creates a media source based on the URI scheme.
     * Configured for ultra-low-latency live streaming.
     */
    @OptIn(UnstableApi::class)
    private fun createMediaSource(uri: String): MediaSource {
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setMaxPlaybackSpeed(1.02f)
                    .setMinPlaybackSpeed(0.98f)
                    .setTargetOffsetMs(100)
                    .setMinOffsetMs(50)
                    .setMaxOffsetMs(500)
                    .build()
            )
            .build()
        
        return if (uri.startsWith("rtsp://")) {
            Log.d(TAG, "Creating RtspMediaSource with TCP transport + TLS socket")
            RtspMediaSource.Factory()
                .setForceUseRtpTcp(true)
                .setTimeoutMs(10_000)
                .setSocketFactory(trustAllSslSocketFactory)
                .createMediaSource(mediaItem)
        } else {
            Log.d(TAG, "Creating ProgressiveMediaSource for: $uri")
            val dataSourceFactory = DefaultDataSource.Factory(context)
            ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(mediaItem)
        }
    }
    
    /**
     * Creates a player configured for grid view (sub-stream quality).
     * Focuses on minimal latency and resource usage.
     */
    @OptIn(UnstableApi::class)
    fun createGridPlayer(
        streamUri: String,
        onError: ((PlaybackException) -> Unit)? = null
    ): ExoPlayer {
        val maskedUri = streamUri.replace(Regex("://[^:]+:[^@]+@"), "://*****:*****@")
        Log.d(TAG, "Creating grid player for: $maskedUri")
        
        return try {
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    1000,
                    3000,
                    500,
                    1000
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
            
            val mediaSource = createMediaSource(streamUri)
            
            ExoPlayer.Builder(context)
                .setLoadControl(loadControl)
                .build()
                .apply {
                    setMediaSource(mediaSource)
                    playWhenReady = true
                    volume = 0f
                    
                    addListener(object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
                            Log.e(TAG, "Grid player error: ${error.errorCodeName} - ${error.message}", error)
                            onError?.invoke(error)
                        }
                    })
                    
                    prepare()
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating grid player for $maskedUri", e)
            throw e
        }
    }
    
    /**
     * Creates a player configured for fullscreen view (main-stream quality).
     * Focuses on quality and stability for high-resolution streams.
     */
    @OptIn(UnstableApi::class)
    fun createFullscreenPlayer(
        streamUri: String,
        onError: ((PlaybackException) -> Unit)? = null
    ): ExoPlayer {
        val maskedUri = streamUri.replace(Regex("://[^:]+:[^@]+@"), "://*****:*****@")
        Log.d(TAG, "Creating fullscreen player for: $maskedUri")
        
        return try {
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    1500,
                    5000,
                    750,
                    1500
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
            
            val mediaSource = createMediaSource(streamUri)
            
            ExoPlayer.Builder(context)
                .setLoadControl(loadControl)
                .build()
                .apply {
                    setMediaSource(mediaSource)
                    playWhenReady = true
                    
                    addListener(object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
                            Log.e(TAG, "Fullscreen player error: ${error.errorCodeName} - ${error.message}", error)
                            onError?.invoke(error)
                        }
                    })
                    
                    prepare()
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating fullscreen player for $maskedUri", e)
            throw e
        }
    }
    
    /**
     * Safely releases a player instance.
     */
    fun releasePlayer(player: ExoPlayer?) {
        try {
            player?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing player: ${e.message}")
        }
    }
}
