pu# ONVIF Camera Viewer

A vendor-neutral Android application for discovering and streaming from ONVIF-compliant IP cameras on your local network.

## Features

- **Auto-Discovery**: Finds ONVIF cameras using WS-Discovery (UDP Multicast)
- **Secure Authentication**: WS-UsernameToken with PasswordDigest
- **Low-Latency Streaming**: RTSP over TCP with optimized buffering
- **Responsive Grid**: Adaptive layout for multiple camera feeds
- **Fullscreen Mode**: High-quality main-stream viewing

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    Presentation Layer                    │
│  ┌─────────────┐  ┌────────────────┐  ┌──────────────┐  │
│  │ GridScreen  │  │ FullscreenView │  │  AuthDialog  │  │
│  └─────────────┘  └────────────────┘  └──────────────┘  │
│                         │                                │
│                  ┌──────▼──────┐                        │
│                  │  ViewModel  │                        │
│                  └──────┬──────┘                        │
└─────────────────────────┼───────────────────────────────┘
                          │
┌─────────────────────────┼───────────────────────────────┐
│                    Domain Layer                         │
│                  ┌──────▼──────┐                        │
│                  │ Repository  │                        │
│                  └──────┬──────┘                        │
└─────────────────────────┼───────────────────────────────┘
                          │
┌─────────────────────────┼───────────────────────────────┐
│                     Data Layer                          │
│  ┌─────────────┐  ┌─────▼─────┐  ┌──────────────────┐   │
│  │  Discovery  │  │   ONVIF   │  │  Player Manager  │   │
│  │   Service   │  │   Client  │  │    (Media3)      │   │
│  └─────────────┘  └───────────┘  └──────────────────┘   │
└─────────────────────────────────────────────────────────┘
```

## Tech Stack

| Component | Technology |
|-----------|------------|
| Language | Kotlin |
| UI | Jetpack Compose |
| Streaming | AndroidX Media3 (ExoPlayer) |
| Networking | OkHttp |
| DI | Hilt |
| Architecture | MVVM + Clean Architecture |

## Requirements

- Android 8.0 (API 26) or higher
- Local network with ONVIF-compliant cameras
- Camera credentials (username/password)

## Building

```bash
./gradlew assembleDebug
```

## Usage

1. **Launch the app** and tap "Discover Cameras"
2. **Wait** for cameras to appear in the grid
3. **Tap a camera** to enter credentials
4. **View streams** in the grid or tap fullscreen

## Permissions

- `INTERNET` - Network communication
- `ACCESS_WIFI_STATE` - Wi-Fi status
- `CHANGE_WIFI_MULTICAST_STATE` - UDP multicast for discovery
- `ACCESS_NETWORK_STATE` - Network connectivity

## Supported Cameras

Compatible with all ONVIF Profile S cameras including:
- Hikvision
- Dahua
- Axis
- Amcrest
- Reolink
- And many more...

## License

MIT License - See LICENSE file for details.
