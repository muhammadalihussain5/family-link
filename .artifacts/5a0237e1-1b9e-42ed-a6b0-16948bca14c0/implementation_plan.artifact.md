# Task 3: Pairing and Data Streaming Implementation Plan

Implement QR code pairing and real-time data streaming between Client and Server using WebSockets.

## User Review Required

- **Network Permissions**: The app will require `INTERNET` and `ACCESS_WIFI_STATE` / `CHANGE_WIFI_STATE` permissions. `CAMERA` permission is also needed for the Server to scan QR codes.
- **Local Network Discovery**: The plan assumes both devices are on the same local network. The QR code will contain the Server's IP address and port.

## Proposed Changes

### Dependencies

#### [MODIFY] [libs.versions.toml](file:///C:/Users/muham/Downloads/Code/Mobile%20connector/gradle/libs.versions.toml)
Add ZXing and Ktor dependencies.

#### [MODIFY] [build.gradle.kts (app)](file:///C:/Users/muham/Downloads/Code/Mobile%20connector/app/build.gradle.kts)
Apply the new dependencies.

### Data Models

#### [NEW] [ConnectionInfo.kt](file:///C:/Users/muham/Downloads/Code/Mobile%20connector/app/src/main/java/com/hashmi/familylink/data/ConnectionInfo.kt)
Data class to hold IP, Port, and Device ID for pairing.

#### [NEW] [StreamMessage.kt](file:///C:/Users/muham/Downloads/Code/Mobile%20connector/app/src/main/java/com/hashmi/familylink/data/StreamMessage.kt)
Sealed class for different types of data streamed (Notifications, Heartbeat, etc.).

### Pairing Logic

#### [NEW] [QRGenerator.kt](file:///C:/Users/muham/Downloads/Code/Mobile%20connector/app/src/main/java/com/hashmi/familylink/ui/QRGenerator.kt)
Composable to generate and display a QR code on the Client.

#### [NEW] [QRScanner.kt](file:///C:/Users/muham/Downloads/Code/Mobile%20connector/app/src/main/java/com/hashmi/familylink/ui/QRScanner.kt)
Composable using ZXing to scan QR codes on the Server.

### Communication Bridge

#### [NEW] [SocketServer.kt](file:///C:/Users/muham/Downloads/Code/Mobile%20connector/app/src/main/java/com/hashmi/familylink/network/SocketServer.kt)
Ktor server implementation to run on the Server device.

#### [NEW] [SocketClient.kt](file:///C:/Users/muham/Downloads/Code/Mobile%20connector/app/src/main/java/com/hashmi/familylink/network/SocketClient.kt)
Ktor client implementation to run on the Client device.

### Integration

#### [MODIFY] [ClientMainScreen](file:///C:/Users/muham/Downloads/Code/Mobile%20connector/app/src/main/java/com/hashmi/familylink/ui/MainContent.kt)
Add QR display and connection status.

#### [MODIFY] [ServerMainScreen](file:///C:/Users/muham/Downloads/Code/Mobile%20connector/app/src/main/java/com/hashmi/familylink/ui/MainContent.kt)
Add QR scanner and list of connected devices.

#### [MODIFY] [ClientNotificationListenerService](file:///C:/Users/muham/Downloads/Code/Mobile%20connector/app/src/main/java/com/hashmi/familylink/service/ClientNotificationListenerService.kt)
Hook into the SocketClient to stream notifications.

## Verification Plan

### Automated Tests
- Unit tests for `ConnectionInfo` serialization/deserialization.
- Mock server/client connection test.

### Manual Verification
- Run the app in Server mode on one device/emulator.
- Run the app in Client mode on another device/emulator.
- Scan the QR code.
- Verify "Connected" status.
- Trigger a notification on the Client and verify it appears on the Server.
