# Native BLE Direct Connection Implementation

This plan outlines the steps to add a direct Bluetooth Low Energy (BLE) connection feature to the SmartCrutch app, allowing it to connect directly to the "GM1-Node" microcontroller and decode the real-time IMU/Magnetometer data stream.

## User Review Required

- **Permissions**: The app will require `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` permissions. The user will need to grant these at runtime.
- **UI Layout**: A new "Direct" tab will be added to the bottom navigation bar to host the BLE connection interface.

## Proposed Changes

### Android Manifest

#### [AndroidManifest.xml](file:///C:/Users/sinam/AndroidStudioProjects/SmartCrutch/app/src/main/AndroidManifest.xml)

- Add necessary BLE permissions:
    - `android.permission.BLUETOOTH`
    - `android.permission.BLUETOOTH_ADMIN`
    - `android.permission.BLUETOOTH_SCAN`
    - `android.permission.BLUETOOTH_CONNECT`
    - `android.permission.ACCESS_FINE_LOCATION` (Required for BLE scanning on some Android versions)

---

### Data Models & State

#### [DashboardViewModel.kt](file:///C:/Users/sinam/AndroidStudioProjects/SmartCrutch/app/src/main/java/com/example/smartcrutch/ui/DashboardViewModel.kt)

- Update `DashboardUiState` to include BLE-related fields:
    - `bleStatus`: String
    - `isBleConnected`: Boolean
    - `isScanning`: Boolean
    - `discoveredDevices`: List of Bluetooth devices
- Implement BLE logic using `BluetoothAdapter`, `BluetoothLeScanner`, and `BluetoothGatt`.
- Implement parsing for the CSV ASCII format: `ax,ay,az,gx,gy,gz,mx,my,mz`.

---

### UI Components

#### [MainActivity.kt](file:///C:/Users/sinam/AndroidStudioProjects/SmartCrutch/app/src/main/java/com/example/smartcrutch/MainActivity.kt)

- Add `Screen.Direct` to the `Screen` enum.
- Implement `DirectConnectScreen` composable:
    - Scan button.
    - List of discovered devices.
    - Connection status indicator.
    - Real-time data preview from BLE.
- Update `MainAppContainer` to handle the new screen.
- Update `BottomNavBar` to include the "Direct" tab.
- Add runtime permission request logic.

## Verification Plan

### Manual Verification
- Deploy the app to a physical device.
- Grant Bluetooth and Location permissions.
- Navigate to the "Direct" tab.
- Tap "Scan" and verify "GM1-Node" appears in the list.
- Connect to "GM1-Node" and verify real-time data updates in the UI (Accel, Gyro, Mag).
- Verify that disconnecting works and the UI updates accordingly.
