#include <Arduino.h>
#include <ArduinoBLE.h>
#include <LSM6DS3.h>
#include <MadgwickAHRS.h>
#include <Wire.h>

#include "src/MagBMM150.h"


// Recording mode sampling period:
constexpr uint32_t kRecordPeriodMs = 100;

// Madgwick filter update rate — must match recording rate.
constexpr float kSampleFreqHz = 1000.0f / kRecordPeriodMs;

// Sleep mode sampling period: 
constexpr uint32_t kSleepPeriodMs = 500;

// Total acceleration magnitude threshold (g) that wakes the device.
constexpr float kMotionThresholdG = 1.05f;

// Seconds of no motion before entering sleep.
constexpr uint32_t kSleepAfterMs = 10000;

// LED blink half-period in recording mode (ms).
constexpr uint32_t kLedBlinkHalfMs = 500;

// I2C clock.
constexpr uint32_t kI2CFreqHz = 100000;

// devices

LSM6DS3 imu(I2C_MODE, 0x6A);
MagBMM150 mag(Wire);
Madgwick madgwick;


BLEService        gm1Service("12345678-1234-5678-1234-56789abcdef0");
BLECharacteristic gm1DataChar(
    "12345678-1234-5678-1234-56789abcdef1",
    BLERead | BLENotify,
    28);  // max 28 bytes: uint32 seq + up to 12 × int16; actual length varies with enabled streams

// state machine

enum class State { SLEEPING, RECORDING, PAUSED };
State state = State::PAUSED;

bool mag_ok = false;
bool ble_ok = false;
bool motion_enabled       = true;  // auto sleep/wake from accel motion
bool accel_out_enabled    = true;  // serial/BLE only; IMU still read for Madgwick + motion
bool gyro_out_enabled     = true;  // serial/BLE only; gyro still read for Madgwick
bool orient_out_enabled   = true;  // serial/BLE: roll, pitch, yaw (Madgwick always runs)
bool mag_out_enabled      = true;  // serial/BLE: mx, my, mz

uint32_t last_sample_ms   = 0;
uint32_t last_motion_ms   = 0;
uint32_t last_led_ms      = 0;
bool     led_on           = false;
uint32_t seq              = 0;  // packet sequence counter for loss detection

// helpers

void enableSenseRails() {
  pinMode(D30, OUTPUT); digitalWrite(D30, HIGH);  // VDD_ENV_ENABLE
  pinMode(D29, OUTPUT); digitalWrite(D29, HIGH);  // I2C_PULL
}

void setLed(bool on) {
  // LEDB is active-low on XIAO nRF52840.
  digitalWrite(LEDB, on ? LOW : HIGH);
}

// Returns total acceleration magnitude.
float accelMag(float ax, float ay, float az) {
  return sqrtf(ax * ax + ay * ay + az * az);
}

// roll/pitch/yaw in degrees from Madgwick filter.
void sendSample(float ax, float ay, float az,
                float gx, float gy, float gz,
                float roll, float pitch, float yaw,
                int16_t mx, int16_t my, int16_t mz) {
  // Serial CSV: seq first, then only enabled field groups.
  Serial.print(seq);
  if (accel_out_enabled) {
    Serial.print(",");
    Serial.print(ax, 4);
    Serial.print(",");
    Serial.print(ay, 4);
    Serial.print(",");
    Serial.print(az, 4);
  }
  if (gyro_out_enabled) {
    Serial.print(",");
    Serial.print(gx, 2);
    Serial.print(",");
    Serial.print(gy, 2);
    Serial.print(",");
    Serial.print(gz, 2);
  }
  if (orient_out_enabled) {
    Serial.print(",");
    Serial.print(roll, 2);
    Serial.print(",");
    Serial.print(pitch, 2);
    Serial.print(",");
    Serial.print(yaw, 2);
  }
  if (mag_out_enabled) {
    Serial.print(",");
    Serial.print(mx);
    Serial.print(",");
    Serial.print(my);
    Serial.print(",");
    Serial.print(mz);
  }
  Serial.println();

  // BLE binary: uint32 seq + enabled int16 values (max 28 bytes).
  if (ble_ok && BLE.connected()) {
    uint8_t payload[28];
    size_t offset = 0;
    memcpy(payload + offset, &seq, 4);
    offset += 4;

    int16_t vals[12];
    uint8_t val_count = 0;
    if (accel_out_enabled) {
      vals[val_count++] = static_cast<int16_t>(ax * 1000.0f);
      vals[val_count++] = static_cast<int16_t>(ay * 1000.0f);
      vals[val_count++] = static_cast<int16_t>(az * 1000.0f);
    }
    if (gyro_out_enabled) {
      vals[val_count++] = static_cast<int16_t>(gx * 100.0f);
      vals[val_count++] = static_cast<int16_t>(gy * 100.0f);
      vals[val_count++] = static_cast<int16_t>(gz * 100.0f);
    }
    if (orient_out_enabled) {
      vals[val_count++] = static_cast<int16_t>(roll * 10.0f);
      vals[val_count++] = static_cast<int16_t>(pitch * 10.0f);
      vals[val_count++] = static_cast<int16_t>(yaw * 10.0f);
    }
    if (mag_out_enabled) {
      vals[val_count++] = mx;
      vals[val_count++] = my;
      vals[val_count++] = mz;
    }

    if (val_count > 0) {
      memcpy(payload + offset, vals, static_cast<size_t>(val_count) * sizeof(int16_t));
      offset += static_cast<size_t>(val_count) * sizeof(int16_t);
    }
    gm1DataChar.writeValue(payload, offset);
  }

  ++seq;
}

void handleSerialCommand() {
  if (!Serial.available()) return;
  const char cmd = static_cast<char>(Serial.read());
  // Flush rest of line.
  while (Serial.available() && Serial.peek() != '\n') Serial.read();
  if (Serial.available()) Serial.read();  // consume '\n'

  if (cmd == 's' || cmd == 'S') {
    if (state == State::PAUSED) {
      state = State::RECORDING;
      last_motion_ms = millis();
      Serial.println("# RECORDING");
    }
  } else if (cmd == 'p' || cmd == 'P') {
    if (state == State::RECORDING || state == State::SLEEPING) {
      state = State::PAUSED;
      setLed(false);
      Serial.println("# PAUSED");
    }
  } else if (cmd == 'm' || cmd == 'M') {
    motion_enabled = !motion_enabled;
    if (!motion_enabled && state == State::SLEEPING) {
      state = State::RECORDING;
      last_motion_ms = millis();
      last_led_ms    = millis();
      led_on         = true;
      setLed(true);
    } else if (motion_enabled) {
      last_motion_ms = millis();
    }
    Serial.println(motion_enabled ? "# MOTION ON" : "# MOTION OFF");
  } else if (cmd == 'a' || cmd == 'A') {
    accel_out_enabled = !accel_out_enabled;
    Serial.println(accel_out_enabled ? "# ACCEL OUT ON" : "# ACCEL OUT OFF");
  } else if (cmd == 'y' || cmd == 'Y') {
    gyro_out_enabled = !gyro_out_enabled;
    Serial.println(gyro_out_enabled ? "# GYRO OUT ON" : "# GYRO OUT OFF");
  } else if (cmd == 'o' || cmd == 'O') {
    orient_out_enabled = !orient_out_enabled;
    Serial.println(orient_out_enabled ? "# ORIENT OUT ON" : "# ORIENT OUT OFF");
  } else if (cmd == 'g' || cmd == 'G') {
    mag_out_enabled = !mag_out_enabled;
    Serial.println(mag_out_enabled ? "# MAG OUT ON" : "# MAG OUT OFF");
  }
}

// setup

void setup() {
  Serial.begin(115200);
  delay(300);

  enableSenseRails();

  // IMU power rail.
#ifdef PIN_LSM6DS3TR_C_POWER
  pinMode(PIN_LSM6DS3TR_C_POWER, OUTPUT);
  digitalWrite(PIN_LSM6DS3TR_C_POWER, HIGH);
#else
  pinMode(D14, OUTPUT);
  digitalWrite(D14, HIGH);
#endif
  delay(500);

  // LED pin.
  pinMode(LEDB, OUTPUT);
  setLed(false);

  Wire.begin();
  Wire.setClock(kI2CFreqHz);

  if (imu.begin() != 0) {
    while (true) {
      Serial.println("[FATAL] IMU init failed");
      delay(1000);
    }
  }

  mag_ok = mag.begin();

  madgwick.begin(kSampleFreqHz);

  if (!BLE.begin()) {
    ble_ok = false;
  } else {
    BLE.setLocalName("GM1-Node");
    BLE.setDeviceName("GM1-Node");
    BLE.setAdvertisedService(gm1Service);
    gm1Service.addCharacteristic(gm1DataChar);
    BLE.addService(gm1Service);
    BLE.advertise();
    ble_ok = true;
  }

  Serial.println("# BOOT: init complete — send 's' to start recording");
  Serial.println("seq,[ax_g,ay_g,az_g if accel ON],[gx_dps,gy_dps,gz_dps if gyro ON],"
                 "[roll_deg,pitch_deg,yaw_deg if orient ON],[mx_raw,my_raw,mz_raw if mag ON]");
  Serial.println("# Commands: s=start  p=pause  m=motion sleep/wake");
  Serial.println("#            a=accel out  y=gyro out  o=orient(roll/pitch/yaw) out  g=mag out");

  const uint32_t now = millis();
  last_sample_ms = now;
  last_motion_ms = now;
  last_led_ms    = now;
}

// loop

void loop() {
  if (ble_ok) BLE.poll();
  handleSerialCommand();

  const uint32_t now = millis();

  // paused
  if (state == State::PAUSED) {
    setLed(false);
    delay(50);
    return;
  }

  // sleep mode
  if (state == State::SLEEPING) {
    if (!motion_enabled) {
      state = State::RECORDING;
      last_motion_ms = now;
      last_led_ms    = now;
      led_on         = true;
      setLed(true);
      return;
    }

    setLed(false);

    if (static_cast<int32_t>(now - last_sample_ms) < static_cast<int32_t>(kSleepPeriodMs)) {
      return;
    }
    last_sample_ms = now;

    // Poll IMU for motion detection and keep Madgwick filter fresh during sleep.
    const float ax = imu.readFloatAccelX();
    const float ay = imu.readFloatAccelY();
    const float az = imu.readFloatAccelZ();
    const float gx = imu.readFloatGyroX();
    const float gy = imu.readFloatGyroY();
    const float gz = imu.readFloatGyroZ();
    madgwick.updateIMU(gx, gy, gz, ax, ay, az);

    if (accelMag(ax, ay, az) >= kMotionThresholdG) {
      state = State::RECORDING;
      last_motion_ms = now;
      last_led_ms    = now;
      led_on         = true;
      setLed(true);
    }
    return;
  }

  // recording mode

  // LED blink.
  if (static_cast<int32_t>(now - last_led_ms) >= static_cast<int32_t>(kLedBlinkHalfMs)) {
    last_led_ms = now;
    led_on = !led_on;
    setLed(led_on);
  }

  // Rate-limit samples.
  if (static_cast<int32_t>(now - last_sample_ms) < static_cast<int32_t>(kRecordPeriodMs)) {
    return;
  }
  last_sample_ms = now;

  // Always read IMU — output toggles (a/y/o) only affect serial/BLE, not Madgwick.
  const float ax = imu.readFloatAccelX();
  const float ay = imu.readFloatAccelY();
  const float az = imu.readFloatAccelZ();
  const float gx = imu.readFloatGyroX();
  const float gy = imu.readFloatGyroY();
  const float gz = imu.readFloatGyroZ();

  // Madgwick always uses accel + gyro regardless of which streams are enabled.
  madgwick.updateIMU(gx, gy, gz, ax, ay, az);
  const float roll  = madgwick.getRoll();
  const float pitch = madgwick.getPitch();
  const float yaw   = madgwick.getYaw();

  int16_t mx = 0, my = 0, mz = 0;
  if (mag_ok && mag_out_enabled) mag.readRaw(mx, my, mz);

  sendSample(ax, ay, az, gx, gy, gz, roll, pitch, yaw, mx, my, mz);

  if (motion_enabled) {
    // Update motion timer.
    if (accelMag(ax, ay, az) >= kMotionThresholdG) {
      last_motion_ms = now;
    }

    // Enter sleep if no motion for kSleepAfterMs.
    if (static_cast<int32_t>(now - last_motion_ms) >= static_cast<int32_t>(kSleepAfterMs)) {
      state = State::SLEEPING;
      setLed(false);
    }
  }
}
