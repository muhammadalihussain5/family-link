# Task 2: Client Core Services Implementation Plan

Implement the core background services and permission handling for the Client mode, enabling accessibility features, screen capture, and persistence.

## Proposed Changes

### Service Layer
#### [NEW] [ClientAccessibilityService.kt](file:///C:/Users/muham/Downloads/Code/Mobile connector/app/src/main/java/com/hashmi/familylink/service/ClientAccessibilityService.kt)
Implement `AccessibilityService` to intercept notifications and inject tap events.

#### [NEW] [BootReceiver.kt](file:///C:/Users/muham/Downloads/Code/Mobile connector/app/src/main/java/com/hashmi/familylink/service/BootReceiver.kt)
Implement `BroadcastReceiver` for `BOOT_COMPLETED` to ensure the app/service starts after reboot.

#### [NEW] [accessibility_service_config.xml](file:///C:/Users/muham/Downloads/Code/Mobile connector/app/src/main/res/xml/accessibility_service_config.xml)
Configuration for the `AccessibilityService`.

### UI Layer
#### [NEW] [PermissionScreen.kt](file:///C:/Users/muham/Downloads/Code/Mobile connector/app/src/main/java/com/hashmi/familylink/ui/PermissionScreen.kt)
A screen to request and track all required permissions:
- Accessibility Service
- Notification Access
- MediaProjection (Screen Recording)
- Post Notifications (Android 13+)
- Ignore Battery Optimizations

#### [MODIFY] [MainContent.kt](file:///C:/Users/muham/Downloads/Code/Mobile connector/app/src/main/java/com/hashmi/familylink/ui/MainContent.kt)
Update `ClientMainScreen` to navigate to `PermissionScreen` if permissions are missing.

#### [MODIFY] [NavKey.kt](file:///C:/Users/muham/Downloads/Code/Mobile connector/app/src/main/java/com/hashmi/familylink/ui/NavKey.kt)
Add `Permissions` to `NavKey`.

#### [MODIFY] [MainActivity.kt](file:///C:/Users/muham/Downloads/Code/Mobile connector/app/src/main/java/com/hashmi/familylink/MainActivity.kt)
Handle the new `Permissions` navigation key.

### Manifest and Resources
#### [MODIFY] [AndroidManifest.xml](file:///C:/Users/muham/Downloads/Code/Mobile connector/app/src/main/AndroidManifest.xml)
- Declare `ClientAccessibilityService`.
- Declare `BootReceiver` with `RECEIVE_BOOT_COMPLETED` permission.
- Add necessary permissions: `ACCESSIBILITY_SERVICE`, `RECEIVE_BOOT_COMPLETED`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `POST_NOTIFICATIONS`.

## Verification Plan

### Automated Tests
- Build the project to ensure all new components are correctly integrated.
- `gradle_build(":app:assembleDebug")`

### Manual Verification
- In Client mode, verify the permission screen appears and shows the status of each permission.
- Activate the Accessibility Service and verify the UI updates.
- Grant Notification Access and verify status.
- Grant MediaProjection permission and verify status.
- Verify the app starts after a simulated reboot (ADB command: `adb shell am broadcast -a android.intent.action.BOOT_COMPLETED`).
