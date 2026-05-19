# C++ / Arduino Firmware Port

This folder contains the Arduino C++ port of the MicroPython firmware.

## Target

- Board: Seeed XIAO nRF52840 Sense
- Sensors:
  - Internal LSM6DS3 IMU accel on `Wire1`
  - External BMM150 magnetometer on `Wire`
- BLE:
  - Peripheral with one notify characteristic
  - Use nRF Connect to test notifications

## Structure

- `GM1Firmware/GM1Firmware.ino` - app entrypoint
- `GM1Firmware/src/ImuLSM6DS3.*` - IMU driver (accel)
- `GM1Firmware/src/MagBMM150.*` - BMM150 raw read driver
- `GM1Firmware/src/BleTransport.*` - BLE service + notify setup
- `GM1Firmware/src/Sample.h` - shared sample struct

## Arduino IDE setup

1. Install Seeed nRF52 board package and select:
   - `Seeed XIAO nRF52840 Sense`
2. Open `cpp/GM1Firmware/GM1Firmware.ino`
3. Upload to board.

## nRF Connect test

1. Scan for BLE device name `GM1-Node`
2. Connect
3. Find custom service + notify characteristic
4. Enable notifications

The sketch also prints CSV debug on serial:

`seq,ax_g,ay_g,az_g,mx_raw,my_raw,mz_raw`

