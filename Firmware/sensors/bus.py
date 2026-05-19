"""I2C bus helpers for Seeed XIAO nRF52840."""

from machine import Pin, I2C


def make_i2c0(freq=100000):
    """External bus on D5(SCL)/D4(SDA)."""
    return I2C(0, scl=Pin(5), sda=Pin(4), freq=freq)


def make_i2c1(freq=100000):
    """Internal/shared bus on SCL=27, SDA=7."""
    return I2C(1, scl=Pin(27), sda=Pin(7), freq=freq)


def scan(i2c):
    """Return sorted list of detected device addresses."""
    return sorted(i2c.scan())

