# Project Plan

An Android app with two modes: Server and Client. 
- Client Mode: 
    - Generates a unique key and QR code for linking.
    - Requires permissions for Accessibility, Notification access, and Screen Recording.
    - Runs in background and starts on boot.
    - Shares notifications, messages, and screen with the Server.
    - Allows remote taps from the Server.
- Server Mode:
    - Links to Client via QR/key.
    - Receives and displays Client's notifications and messages.
    - Views Client's screen and performs remote taps.
    - Signals Client to connect when opened.
- Persistent mode selection on first startup.
- Always-on connectivity for Client.

## Project Brief

# Project Brief: Family Link (Mobile Connector)

## Features
1. **Dual-Mode Configuration & Pairing**: A setup wizard to choose between "Server" and "Client" modes, with secure linking via unique key generation and QR code scanning.
2. **Background Persistence & Auto-Start**: Client-side background service that automatically initiates on device boot to maintain an "always-on" connection.
3. **Real-time Screen Mirroring & Remote Interaction**: Streaming of the Client's screen to the Server with the ability for the Server user to perform remote taps using Accessibility Services.
4. **Notification & Message Synchronization**: Immediate relay and display of incoming notifications and messages from the Client device to the Server's dashboard.
5. **Unified Permission Management**: A centralized interface for the Client mode to request and manage critical permissions (Accessibility, Notification Access, and Screen Recording).

## High-Level Technical Stack
- **Kotlin**: The core programming language for robust Android development.
- **Jetpack Compose**: Used for building all UI components declaratively.
- **Jetpack Navigation 3**: State-driven navigation for managing the transition between setup, Server, and Client flows.
- **Compose Material Adaptive**: To ensure the UI (especially the Server dashboard) responds gracefully to different screen sizes and orientations.
- **Kotlin Coroutines & Flow**: For efficient background processing and real-time data streaming.
- **MediaProjection API**: Required for capturing the Client's screen for mirroring.
- **Accessibility Service API**: Essential for intercepting notifications and injecting remote tap events on the Client device.

> [!NOTE]
> This MVP focuses on the core connectivity and control loop between two devices, prioritizing reliability and persistent background operation.

## Implementation Steps
**Total Duration:** 1h 49m 10s

### Task_1_FoundationAndModeSelection: Initialize project structure, integrate Jetpack Navigation 3, and implement the persistent Mode Selection (Server vs Client) setup wizard using DataStore.
- **Status:** COMPLETED
- **Updates:** Completed Task 1: Foundation and Mode Selection.
- **Acceptance Criteria:**
  - Persistent mode selection on restart
  - Navigation 3 implemented
  - Setup wizard UI functional
- **Duration:** 38m 52s

### Task_2_ClientCoreServices: Implement Client-side Accessibility Service for remote interaction and notification interception, setup MediaProjection for screen capture, and configure a BootReceiver for background persistence.
- **Status:** COMPLETED
- **Updates:** Completed Task 2: Client Core Services.
- **Acceptance Criteria:**
  - Accessibility Service active
  - MediaProjection permission handled
  - App starts on boot
  - Notification access granted
- **Duration:** 8m 51s

### Task_3_PairingAndDataStreaming: Create the QR/Key linking system and establish a real-time communication bridge (e.g., WebSockets) to stream screen frames and notifications from Client to Server.
- **Status:** COMPLETED
- **Updates:** Completed Task 3: Pairing and Data Streaming.
- **Acceptance Criteria:**
  - Successful QR pairing
  - Live data connection established
  - Notifications relayed to Server
- **Duration:** 35m 7s

### Task_4_ServerDashboardAndControl: Build the Server Dashboard using Compose Material Adaptive, implementing screen mirroring display and the ability to send remote tap events back to the Client.
- **Status:** COMPLETED
- **Updates:** Completed Task 4: Server Dashboard and Control.
- **Acceptance Criteria:**
  - Adaptive dashboard UI
  - Real-time screen mirroring
  - Remote taps working on Client device
- **Duration:** 13m 31s

### Task_5_FinalVerification: Conduct final integration tests, verify application stability, and ensure all features align with requirements.
- **Status:** COMPLETED
- **Updates:** Final verification completed successfully. The Setup Wizard is now fully functional and scrollable. Core features (QR pairing, screen mirroring, remote taps, and notification streaming) are implemented and stable. The app adheres to the project brief.
- **Acceptance Criteria:**
  - App builds successfully
  - App does not crash
  - All existing tests pass
  - Critic agent verified requirement alignment
- **Duration:** 12m 49s

