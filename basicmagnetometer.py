import time
import struct
from machine import Pin, I2C

# 1. The Power "Gotcha": Turn on the IMU
# Port 1, Pin 8 translates to integer 40
imu_pwr = Pin(40, Pin.OUT)
imu_pwr.value(1)
time.sleep(0.2) # Increased sleep slightly to ensure the chip is fully awake

# 2. Initialize Hardware I2C
# Hardware I2C automatically handles the internal pull-ups!
i2c = I2C(1, scl=Pin(27), sda=Pin(7), freq=400000)

class LSM6DS3:
    def __init__(self, i2c, address=0x6A):
        self.i2c = i2c
        self.address = address
        
        # Verify the sensor is actually responding on the bus
        if self.address not in self.i2c.scan():
            raise RuntimeError("IMU not found. Double-check the power pin!")
            
        # Initialize Accelerometer: 104 Hz, +/- 2g scale
        self.i2c.writeto_mem(self.address, 0x10, b'\x40')
        # Initialize Gyroscope: 104 Hz, +/- 2000 dps scale
        self.i2c.writeto_mem(self.address, 0x11, b'\x40')

    def read_accel(self):
        # Read 6 bytes of acceleration data starting from OUTX_L_XL (0x28)
        data = self.i2c.readfrom_mem(self.address, 0x28, 6)
        x, y, z = struct.unpack('<hhh', data)
        # Convert raw LSB to 'g' force (using 0.061 mg/LSB for the 2g scale)
        return (x * 0.000061, y * 0.000061, z * 0.000061)

    def read_gyro(self):
        # Read 6 bytes of rotational data starting from OUTX_L_G (0x22)
        data = self.i2c.readfrom_mem(self.address, 0x22, 6)
        x, y, z = struct.unpack('<hhh', data)
        # Convert raw LSB to degrees per second (using 70 mdps/LSB for 2000 dps scale)
        return (x * 0.07, y * 0.07, z * 0.07)

# 3. Create the sensor object
imu = LSM6DS3(i2c)

print("Streaming IMU Data...")

# 4. Main loop to stream the telemetry
while True:
    ax, ay, az = imu.read_accel()
    
    # Formatted so Thonny's Plotter can instantly graph the vectors
    print("AX:{:5.2f} AY:{:5.2f} AZ:{:5.2f}".format(ax, ay, az))
    
    time.sleep(0.05) # 20 Hz streaming rate
