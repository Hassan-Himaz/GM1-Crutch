# C++ / Arduino Firmware

This folder contains the Arduino firmware for the **Seeed XIAO nRF52840 Sense**.
The current demo sketch streams crutch sensor data from:

- internal LSM6DS3 IMU acceleration + gyro
- external 7Semi ICM-20948 magnetometer over I2C
- Madgwick roll, pitch, and yaw estimates
- load-step force estimate from the magnetometer Z axis
- serial CSV output
- BLE writes to a PicoRelay-compatible phone/app service

## Current Sketch

- `demo_sketch/demo/demo.ino` - main demo firmware
- `demo_sketch/demo/src/LoadStepDetector.*` - magnetometer-to-force load detector
- `demo_sketch/demo/src/MagBMM150.*` - legacy BMM150 driver kept with the sketch, but not used by `demo.ino`
- `first_cpp_port/first_cpp_port.ino` - earlier port/reference sketch

## Required Arduino Core And Libraries

### Board Core

- `Seeed nRF52 mbed-enabled Boards`
- FQBN used by this project:
  - `Seeeduino:mbed:xiaonRF52840Sense`

Before installing the core, add this URL in **Settings -> Additional boards manager URLs**:

`https://files.seeedstudio.com/arduino/package_seeeduino_boards_index.json`

### Libraries

- `ArduinoBLE` (Library Manager)
- `Seeed_Arduino_LSM6DS3` (ZIP in this folder, or Library Manager)
- `Madgwick` / `MadgwickAHRS` (Library Manager)
- `7Semi_ICM20948` library for the external magnetometer

## Arduino IDE Setup

1. Install the board core:
   - **Settings -> Additional boards manager URLs** -> add the Seeed URL above
   - **Tools -> Board -> Boards Manager** -> search `Seeed nRF52 mbed-enabled` -> install
2. Install libraries:
   - **Tools -> Manage Libraries** -> search `ArduinoBLE` -> install
   - **Tools -> Manage Libraries** -> search `Madgwick` -> install
   - Install the `7Semi_ICM20948` library used by the demo sketch
   - **Sketch -> Include Library -> Add .ZIP Library...** -> select `cpp/Seeed_Arduino_LSM6DS3-master.zip`
3. Select board:
   - **Tools -> Board -> XIAO nRF52840 Sense (mbed-enabled)**
4. Select the correct USB port:
   - macOS: **Tools -> Port ->** `/dev/cu.usbmodem...`
   - Windows: `COMx` (name may include "Seeed Studio XIAO nRF52840 Sense")
   - Linux: `/dev/ttyACM0` or similar
5. Open:
   - `cpp/demo_sketch/demo/demo.ino`
6. Click **Upload**.
7. Open **Serial Monitor** at **115200** baud to view CSV output.

## Serial Output Format

Header:

`seq,ax_g,ay_g,az_g,gx_dps,gy_dps,gz_dps,mx_uT,my_uT,mz_uT,roll,pitch,yaw,force_kg`

Each row starts with a monotonic `seq` counter for packet-loss detection. The demo samples at about 30 Hz (`kRecordPeriodMs = 33`).

Serial commands, typed as one letter plus Enter:

- `s` - start recording from paused state
- `p` - pause recording
- `m` - toggle motion sleep/wake mode

## BLE PicoRelay Mode

The demo acts as a BLE central. It scans for a PicoRelay peripheral and writes framed binary sensor packets to its writable characteristic.

PicoRelay UUIDs:

- Service: `8a3e4d2f-1b6c-4f9e-a7d8-3e5b2c1f4a01`
- DataIn characteristic: `8a3e4d2f-1b6c-4f9e-a7d8-3e5b2c1f4a02`

The payload is a packed 46-byte `SensorPacket`:

- `float ax, ay, az`
- `float gx, gy, gz`
- `int16_t mx, my, mz`
- `float roll, pitch, yaw`
- `float force_kg`

Large payloads are split into PicoRelay frames with a 4-byte frame header and up to 16 payload bytes per frame.
