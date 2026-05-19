# C++ / Arduino Firmware

This folder contains the Arduino firmware for **Seeed XIAO nRF52840 Sense**
that streams:

- internal LSM6DS3 IMU accel + gyro
- external BMM150 magnetometer
- serial CSV output
- BLE notify output (same CSV payload as serial)

## Current sketch

- `GM1Firmware/GM1Firmware.ino` - main firmware (IMU + mag + BLE)
- `GM1Firmware/src/MagBMM150.*` - BMM150 driver

## Required Arduino core and libraries

### Board core (required)

- `Seeed nRF52 mbed-enabled Boards`  
  FQBN used by this project:
  - `Seeeduino:mbed:xiaonRF52840Sense`

### Libraries (required)

- `ArduinoBLE`
- `Seeed_Arduino_LSM6DS3`

## Arduino IDE setup

1. Install board core:
   - Boards Manager -> search `Seeed nRF52 mbed-enabled`
2. Install libraries:
   - Library Manager -> `ArduinoBLE`
   - Library Manager -> `Seeed_Arduino_LSM6DS3`
3. Select board:
   - `XIAO nRF52840 Sense` (mbed-enabled)
4. Open:
   - `cpp/GM1Firmware/GM1Firmware.ino`
5. Upload.

## Run in Arduino IDE (GUI)

1. Connect XIAO nRF52840 Sense over USB.
2. Open Arduino IDE.
3. Go to **Boards Manager** and install:
   - `Seeed nRF52 mbed-enabled Boards`
4. Go to **Library Manager** and install:
   - `ArduinoBLE`
   - `Seeed_Arduino_LSM6DS3`
5. Select board:
   - **Tools -> Board -> XIAO nRF52840 Sense (mbed-enabled)**
6. Select the correct USB port:
   - **Tools -> Port -> /dev/cu.usbmodem...**
7. Open:
   - `cpp/GM1Firmware/GM1Firmware.ino`
8. Click **Upload**.
9. Open **Serial Monitor** at `115200` baud to view CSV output.

## Serial output format

Header:

`ax_g,ay_g,az_g,gx_dps,gy_dps,gz_dps,mx_raw,my_raw,mz_raw`

## BLE test (nRF Connect)

1. Scan for device name: `GM1-Node`
2. Connect
3. Service UUID:
   - `12345678-1234-5678-1234-56789abcdef0`
4. Characteristic UUID:
   - `12345678-1234-5678-1234-56789abcdef1`
5. Enable notifications to view streamed CSV payload.

