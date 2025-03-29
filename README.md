# Secure Remote Control (Android Client)

## Overview
The Android client app enables secure remote control of Android devices by IT administrators via a web-based panel. It handles device registration, real-time screen sharing, and remote input control while maintaining end-to-end encryption.

## Key Features
- **Device Registration**: Unique device pairing with backend gateway
- **Secure Communication**: TLS 1.2+ encrypted WebSocket/WebRTC channels
- **Real-Time Screen Sharing**: MediaProjection API with WebRTC streaming
- **Remote Control**: Touch/keyboard input via Accessibility Services
- **Session Management**: User consent prompts and auto-termination

## Technical Stack
| Component               | Technology                          |
|-------------------------|-------------------------------------|
| Language                | Kotlin                              |
| Architecture            | MVVM + Clean Architecture           |
| Dependency Injection    | Hilt                                |
| Networking              | WebSockets, WebRTC (Google's libwebrtc) |
| Security                | TLS 1.2, Certificate Pinning        |
| Background Operation    | Foreground Service + WorkManager    |

## Installation
### Prerequisites
- Android Studio Flamingo (2023.2.1)+
- Android SDK 34 (Android 14)
- Minimum API Level: 24 (Android 7.0)

### Build Steps
1. Clone the repository:
   ```bash
   git clone https://github.com/SI-SecureRemoteControl/Client-Side-Android-app.git