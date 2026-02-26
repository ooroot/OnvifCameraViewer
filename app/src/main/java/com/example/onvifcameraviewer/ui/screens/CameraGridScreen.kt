package com.example.onvifcameraviewer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.onvifcameraviewer.R
import com.example.onvifcameraviewer.domain.model.ConnectionState
import com.example.onvifcameraviewer.ui.components.AuthDialog
import com.example.onvifcameraviewer.ui.components.CameraCell
import com.example.onvifcameraviewer.ui.components.ManualCameraDialog
import com.example.onvifcameraviewer.ui.viewmodel.CameraViewModel

/**
 * Main camera grid screen showing all discovered cameras.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraGridScreen(
    viewModel: CameraViewModel = hiltViewModel(),
    onCameraFullscreen: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    
    // Show error messages
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearError()
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineMedium
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.End
            ) {
                // Manual add camera FAB
                FloatingActionButton(
                    onClick = { viewModel.showManualAddDialog() },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.add_camera_manually)
                    )
                }
                
                // Discover cameras FAB
                ExtendedFloatingActionButton(
                    onClick = { viewModel.startDiscovery() },
                    icon = {
                        if (uiState.isScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = if (uiState.cameras.isEmpty()) 
                                    Icons.Filled.Search 
                                else 
                                    Icons.Filled.Refresh,
                                contentDescription = null
                            )
                        }
                    },
                    text = {
                        Text(
                            text = if (uiState.isScanning) 
                                stringResource(R.string.scanning) 
                            else 
                                stringResource(R.string.discover_cameras)
                        )
                    },
                    expanded = !uiState.isScanning
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (uiState.cameras.isEmpty() && !uiState.isScanning) {
                // Empty state
                EmptyState()
            } else {
                // Camera grid
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 280.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(
                        items = uiState.cameras,
                        key = { it.id }
                    ) { camera ->
                        CameraCell(
                            camera = camera,
                            playerManager = viewModel.playerManager,
                            onTap = {
                                val isOnvif = camera.device.serviceUrl.isNotEmpty()
                                val needsAuth = camera.credentials == null
                                val hasError = camera.connectionState == ConnectionState.ERROR
                                
                                // Only show auth dialog for ONVIF cameras that need credentials
                                // or when any camera has a connection error (to allow retry)
                                if ((isOnvif && needsAuth) || hasError) {
                                    viewModel.requestAuthentication(camera.id)
                                }
                            },
                            onFullscreen = { onCameraFullscreen(camera.id) },
                            onDelete = { viewModel.showDeleteDialog(camera.id) }
                        )
                    }
                }
            }
        }
    }
    
    // Authentication dialog
    if (uiState.showAuthDialog && uiState.selectedCameraId != null) {
        val selectedCamera = uiState.cameras.find { it.id == uiState.selectedCameraId }
        selectedCamera?.let { camera ->
            AuthDialog(
                cameraName = camera.device.name,
                onDismiss = { viewModel.dismissAuthDialog() },
                onConnect = { username, password, rtspUsername ->
                    viewModel.connectCamera(camera.id, username, password, rtspUsername)
                }
            )
        }
    }
    
    // Manual camera dialog
    if (uiState.showManualAddDialog) {
        ManualCameraDialog(
            onDismiss = { viewModel.dismissManualAddDialog() },
            onAdd = { config ->
                viewModel.addManualCamera(
                    name = config.name,
                    streamUrl = config.streamUrl,
                    username = config.username,
                    password = config.password
                )
            }
        )
    }
    
    // Delete confirmation dialog
    if (uiState.showDeleteDialog && uiState.selectedCameraId != null) {
        val selectedCamera = uiState.cameras.find { it.id == uiState.selectedCameraId }
        selectedCamera?.let { camera ->
            AlertDialog(
                onDismissRequest = { viewModel.dismissDeleteDialog() },
                title = { Text("Remove Camera") },
                text = { 
                    Text("Are you sure you want to remove \"${camera.device.name}\"?") 
                },
                confirmButton = {
                    TextButton(
                        onClick = { viewModel.deleteCamera(camera.id) }
                    ) {
                        Text("Remove", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { viewModel.dismissDeleteDialog() }
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Videocam,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.no_cameras_found),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.tap_to_scan),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
