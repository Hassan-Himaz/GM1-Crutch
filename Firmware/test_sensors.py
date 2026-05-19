"""Hardware test for IMU + magnetometer classes."""

import time

from sensors.bus import make_i2c1, scan
from sensors.imu_lsm6ds3 import LSM6DS3


def main():
    # Use 100kHz while hardware is still being validated.
    i2c1 = make_i2c1(freq=100000)
    print("i2c1:", [hex(a) for a in scan(i2c1)])

    imu = LSM6DS3(i2c1)

    print("Streaming accel only for Thonny Plotter (Ctrl+C to stop)...")
    while True:
        imu_data = imu.read_all()
        print(
            "AX:{ax:7.3f} AY:{ay:7.3f} AZ:{az:7.3f}".format(
                ax=imu_data["ax"],
                ay=imu_data["ay"],
                az=imu_data["az"],
            )
        )
        time.sleep(0.1)


main()

