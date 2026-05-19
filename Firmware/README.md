# Python / MicroPython Scripts

This folder contains MicroPython-side scripts and sensor helpers.

Current scripts in this folder:

- `read_sensors.py` - fixed-rate BMM150 serial CSV stream
- `sensors/` - reusable sensor bus and sensor classes

## Prerequisites

- Board flashed with **MicroPython** firmware
- Python 3 on host machine
- `mpremote` installed on host machine

## Run in Thonny (GUI)

1. Connect the board over USB and open Thonny.
2. In Thonny, select interpreter:
   - **MicroPython (generic)** (or matching nRF52840 MicroPython option)
   - Select the board USB port.
3. Open `read_sensors.py` from this folder.
4. Open each file in `sensors/` and save to device so the same folder
   structure exists on the board:
   - `sensors/__init__.py`
   - `sensors/bus.py`
   - `sensors/mag_bmm150.py`
   - `sensors/imu_lsm6ds3.py`
5. Save `read_sensors.py` to the device filesystem.
6. Press **Run** in Thonny.

Expected header:

`mx,my,mz`

## Notes

- If the board does not appear in Thonny, it is likely running Arduino firmware
  instead of MicroPython.
- Arduino and MicroPython are different firmware modes on the board.
  Flash the mode you intend to use before running scripts.
