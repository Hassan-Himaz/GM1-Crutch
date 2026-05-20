# C++ / Arduino Firmware

This folder contains the Arduino firmware for **Seeed XIAO nRF52840 Sense**
that streams:

- internal LSM6DS3 IMU accel + gyro
- external BMM150 magnetometer
- serial CSV output
- BLE notify output (22-byte binary payload; not CSV)

## Current sketch

- `GM1Firmware/GM1Firmware.ino` - main firmware (IMU + mag + BLE)
- `GM1Firmware/src/MagBMM150.*` - BMM150 driver

## Required Arduino core and libraries

### Board core (required)

- `Seeed nRF52 mbed-enabled Boards`  
  FQBN used by this project:
  - `Seeeduino:mbed:xiaonRF52840Sense`

Before installing the core, add this URL in **Settings → Additional boards manager URLs**:

`https://files.seeedstudio.com/arduino/package_seeeduino_boards_index.json`

### Libraries (required)

- `ArduinoBLE` (Library Manager)
- `Seeed_Arduino_LSM6DS3` (ZIP in this folder, or Library Manager)

## Arduino IDE setup

1. Install board core:
   - **Settings → Additional boards manager URLs** → add the Seeed URL above
   - **Tools → Board → Boards Manager** → search `Seeed nRF52 mbed-enabled` → install
2. Install libraries:
   - **Tools → Manage Libraries** → search `ArduinoBLE` → install
   - **Sketch → Include Library → Add .ZIP Library…** → select `cpp/Seeed_Arduino_LSM6DS3-master.zip`
3. Select board:
   - **Tools → Board → XIAO nRF52840 Sense (mbed-enabled)**
4. Open:
   - `cpp/GM1Firmware/GM1Firmware.ino`
5. Upload.

## Run in Arduino IDE (GUI)

1. Connect XIAO nRF52840 Sense over USB.
2. Open Arduino IDE.
3. Add the Seeed boards manager URL (see above) if not already added.
4. Go to **Boards Manager** and install:
   - `Seeed nRF52 mbed-enabled Boards`
5. Go to **Library Manager** and install:
   - `ArduinoBLE`
6. Go to **Sketch → Include Library → Add .ZIP Library…** and select:
   - `cpp/Seeed_Arduino_LSM6DS3-master.zip`
7. Select board:
   - **Tools → Board → XIAO nRF52840 Sense (mbed-enabled)**
8. Select the correct USB port:
   - macOS: **Tools → Port →** `/dev/cu.usbmodem...`
   - Windows: `COMx` (name may include "Seeed Studio XIAO nRF52840 Sense")
   - Linux: `/dev/ttyACM0` (or similar)
9. Open:
   - `cpp/GM1Firmware/GM1Firmware.ino`
10. Click **Upload**.
11. Open **Serial Monitor** at **115200** baud to view CSV output.

## Serial output format

Header:

`seq,ax_g,ay_g,az_g,gx_dps,gy_dps,gz_dps,mx_raw,my_raw,mz_raw`

Each row starts with a monotonic `seq` counter for packet-loss detection.

Serial commands (type one letter + Enter): `s` start, `p` pause, `m` toggle motion sleep/wake.

## BLE test (nRF Connect)

1. Scan for device name: `GM1-Node`
2. Connect
3. Service UUID:
   - `12345678-1234-5678-1234-56789abcdef0`
4. Characteristic UUID:
   - `12345678-1234-5678-1234-56789abcdef1`
5. Enable notifications to view streamed binary payload (22 bytes: `uint32` seq + 9 × `int16`).
