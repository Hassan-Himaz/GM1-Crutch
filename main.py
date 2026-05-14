"""Minimal MicroPython app for Seeed XIAO nRF52840."""

from machine import Pin
from time import sleep


def blink_loop():
    # On many boards, LED is available as "LED". Adjust pin if needed.
    led = Pin("LED", Pin.OUT)
    while True:
        led.toggle()
        sleep(0.5)


blink_loop()
