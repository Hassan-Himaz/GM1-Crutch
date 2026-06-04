"""LSM6DS3 IMU driver (accel + gyro, minimal setup)."""

import struct
import time
from machine import Pin


class LSM6DS3:
    def __init__(self, i2c, address=0x6A, auto_power_on=True):
        self.i2c = i2c
        self.address = address
        if auto_power_on:
            self._power_on()
        self._ensure_present()
        self._configure()

    def _power_on(self):
        # XIAO nRF52840 Sense IMU power gate pin.
        imu_pwr = Pin(40, Pin.OUT)
        imu_pwr.value(1)
        time.sleep(0.2)

    def _ensure_present(self):
        if self.address not in self.i2c.scan():
            raise RuntimeError("LSM6DS3 not found at {}".format(hex(self.address)))

    def _configure(self):
        # CTRL1_XL: accel 104 Hz, +/-2g
        self.i2c.writeto_mem(self.address, 0x10, b"\x40")
        # CTRL2_G: gyro 104 Hz, +/-2000 dps
        self.i2c.writeto_mem(self.address, 0x11, b"\x40")

    def read_accel(self):
        data = self.i2c.readfrom_mem(self.address, 0x28, 6)
        x, y, z = struct.unpack("<hhh", data)
        # 0.061 mg/LSB at +/-2g
        return (x * 0.000061, y * 0.000061, z * 0.000061)

    def read_gyro(self):
        data = self.i2c.readfrom_mem(self.address, 0x22, 6)
        x, y, z = struct.unpack("<hhh", data)
        # 70 mdps/LSB at +/-2000 dps
        return (x * 0.07, y * 0.07, z * 0.07)

    def read_all(self):
        ax, ay, az = self.read_accel()
        gx, gy, gz = self.read_gyro()
        return {
            "ax": ax,
            "ay": ay,
            "az": az,
            "gx": gx,
            "gy": gy,
            "gz": gz,
        }

