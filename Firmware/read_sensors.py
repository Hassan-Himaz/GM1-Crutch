"""Fixed-rate magnetometer sampling with CSV serial output."""

import time

from sensors.bus import make_i2c0, scan
from sensors.mag_bmm150 import BMM150

# Tunable runtime settings
SAMPLE_HZ = 50
I2C_FREQ = 100000


def main():
    # External bus on D4/D5.
    i2c0 = make_i2c0(freq=I2C_FREQ)

    print("i2c0:", [hex(a) for a in scan(i2c0)])
    mag = BMM150(i2c0)
    print("BMM150 on i2c0 @", hex(mag.address))

    period_s = 1.0 / SAMPLE_HZ

    # CSV header for serial logging/host parsers.
    print("mx,my,mz")
    print("Streaming fixed-rate data @ {} Hz".format(SAMPLE_HZ))

    while True:
        sample = mag.read_mag()

        # One compact CSV row per sample (good for serial capture).
        print(
            "{mx:.3f},{my:.3f},{mz:.3f}".format(
                mx=sample["mx"], my=sample["my"], mz=sample["mz"]
            )
        )
        time.sleep(period_s)


main()

