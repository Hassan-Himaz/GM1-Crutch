# Direct BLE Connection Implementation

I have successfully added a direct Bluetooth Low Energy (BLE) connection feature to the SmartCrutch app. This allows the app to bypass the server hub and connect directly to your "GM1-Node" microcontroller for ultra-low-latency data monitoring.

## Key Accomplishments

### 1. New "Direct" Navigation Tab
- Added a dedicated **Direct** tab to the bottom navigation bar.
- This tab hosts the BLE management interface where you can scan for nearby devices.

### 2. Native BLE Integration
- **Scanning**: Implemented a native BLE scanner that specifically looks for active devices.
- **Connection**: Integrated `connectGatt` logic to establish a direct link with the microcontroller.
- **Notification Support**: The app automatically subscribes to the GM1 data characteristic to receive real-time updates.

### 3. Real-time ASCII CSV Decoding
- Implemented a parser for the transmission format: `ax,ay,az,gx,gy,gz,mx,my,mz`.
- The decoder handles the raw string stream and maps it to high-precision engineering units (4 decimals for Accel, 2 for Gyro).

### 4. Dynamic Dashboard Support
- The Home screen is now "Direct-Aware". If you are connected via BLE, the dynamic data card will automatically switch to **"Direct BLE (GM1)"** and show the incoming stream.

### 5. Permission Management
- Added runtime permission request logic for `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, and `ACCESS_FINE_LOCATION` to ensure compliance with Android 12+ security requirements.

## How to Verify

1.  **Launch the App**: Upon startup, the app will request the necessary Bluetooth permissions.
2.  **Navigate to Direct**: Tap the new **Direct** icon in the bottom bar.
3.  **Scan**: Tap the **Scan for GM1-Node** button.
4.  **Connect**: Find your device (e.g., "GM1-Node") in the list and tap it.
5.  **Monitor**: Once connected, you will see the **Accel, Gyro, and Mag** values updating at high speed directly in the UI.

## File Changes
- [AndroidManifest.xml](file:///C:/Users/sinam/AndroidStudioProjects/SmartCrutch/app/src/main/AndroidManifest.xml): Added BLE and Location permissions.
- [DashboardViewModel.kt](file:///C:/Users/sinam/AndroidStudioProjects/SmartCrutch/app/src/main/java/com/example/smartcrutch/ui/DashboardViewModel.kt): Implemented the `BluetoothGattCallback` and CSV parsing logic.
- [MainActivity.kt](file:///C:/Users/sinam/AndroidStudioProjects/SmartCrutch/app/src/main/java/com/example/smartcrutch/MainActivity.kt): Added the `DirectConnectScreen` and updated the navigation system.
