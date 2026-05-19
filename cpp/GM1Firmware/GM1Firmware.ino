#include <Arduino.h>
#include <ArduinoBLE.h>
#include <LSM6DS3.h>
#include <Wire.h>

#include "src/MagBMM150.h"


// Recording mode sampling period:
constexpr uint32_t kRecordPeriodMs = 100;

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


BLEService        gm1Service("12345678-1234-5678-1234-56789abcdef0");
BLECharacteristic gm1DataChar(
    "12345678-1234-5678-1234-56789abcdef1",
    BLERead | BLENotify,
    22);  // uint32 seq + 9 × int16

// state machine

enum class State { SLEEPING, RECORDING, PAUSED };
State state = State::RECORDING;

bool mag_ok = false;
bool ble_ok = false;
bool motion_enabled       = true;  // auto sleep/wake from accel motion

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

void sendSample(float ax, float ay, float az,
                float gx, float gy, float gz,
                int16_t mx, int16_t my, int16_t mz) {
  // Serial CSV: seq first so packet loss is visible as a gap in seq numbers.
  char line[112];
  snprintf(line, sizeof(line),
           "%lu,%.4f,%.4f,%.4f,%.2f,%.2f,%.2f,%d,%d,%d",
           seq, ax, ay, az, gx, gy, gz, mx, my, mz);
  Serial.println(line);

  // BLE binary: uint32 seq + 9 × int16 = 22 bytes.
  if (ble_ok && BLE.connected()) {
    uint8_t payload[22];
    uint32_t s = seq;
    memcpy(payload, &s, 4);
    int16_t vals[9] = {
      static_cast<int16_t>(ax * 1000),
      static_cast<int16_t>(ay * 1000),
      static_cast<int16_t>(az * 1000),
      static_cast<int16_t>(gx * 100),
      static_cast<int16_t>(gy * 100),
      static_cast<int16_t>(gz * 100),
      mx, my, mz
    };
    memcpy(payload + 4, vals, 18);
    gm1DataChar.writeValue(payload, sizeof(payload));
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

  Serial.println("seq,ax_g,ay_g,az_g,gx_dps,gy_dps,gz_dps,mx_raw,my_raw,mz_raw");
  Serial.println("# Commands: s=start  p=pause  m=toggle motion sleep/wake");

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

    // Light accel poll to detect motion.
    const float ax = imu.readFloatAccelX();
    const float ay = imu.readFloatAccelY();
    const float az = imu.readFloatAccelZ();

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

  // Read sensors.
  const float ax = imu.readFloatAccelX();
  const float ay = imu.readFloatAccelY();
  const float az = imu.readFloatAccelZ();
  const float gx = imu.readFloatGyroX();
  const float gy = imu.readFloatGyroY();
  const float gz = imu.readFloatGyroZ();

  int16_t mx = 0, my = 0, mz = 0;
  if (mag_ok) mag.readRaw(mx, my, mz);

  sendSample(ax, ay, az, gx, gy, gz, mx, my, mz);

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
