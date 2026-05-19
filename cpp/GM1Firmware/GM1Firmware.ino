#include <Arduino.h>
#include <ArduinoBLE.h>
#include <LSM6DS3.h>
#include <Wire.h>

#include "src/MagBMM150.h"

// Internal IMU on 0x6A using the known-working LSM6DS3 library scaffold.
LSM6DS3 imu(I2C_MODE, 0x6A);
// External mag on D4/D5 via Wire.
MagBMM150 mag(Wire);

constexpr uint32_t kI2CFreqHz = 100000;
constexpr uint16_t kSampleHz = 10;
constexpr uint32_t kPeriodMs = 1000 / kSampleHz;
constexpr bool kDebugLogs = false;

bool mag_ok = false;
bool ble_ok = false;

BLEService gm1Service("12345678-1234-5678-1234-56789abcdef0");
BLEStringCharacteristic gm1DataChar(
    "12345678-1234-5678-1234-56789abcdef1",
    BLERead | BLENotify,
    120);

void enableSenseRails() {
  // Needed on mbed core for onboard sensor rail and pullups.
  pinMode(D30, OUTPUT);  // VDD_ENV_ENABLE
  digitalWrite(D30, HIGH);
  pinMode(D29, OUTPUT);  // I2C_PULL
  digitalWrite(D29, HIGH);
}

void setup() {
  Serial.begin(115200);
  delay(300);

  enableSenseRails();

#ifdef PIN_LSM6DS3TR_C_POWER
  pinMode(PIN_LSM6DS3TR_C_POWER, OUTPUT);
  digitalWrite(PIN_LSM6DS3TR_C_POWER, HIGH);
#else
  pinMode(D14, OUTPUT);  // 6D_PWR fallback
  digitalWrite(D14, HIGH);
#endif
  delay(500);

  Wire.begin();
  Wire.setClock(kI2CFreqHz);

  if (imu.begin() != 0) {
    while (true) {
      Serial.println("[FATAL] IMU init failed");
      delay(1000);
    }
  }

  mag_ok = mag.begin();
  if (kDebugLogs && !mag_ok) {
    Serial.println("[WARN] Mag init failed, streaming IMU only");
  }

  if (!BLE.begin()) {
    if (kDebugLogs) {
      Serial.println("[WARN] BLE init failed, streaming serial only");
    }
    ble_ok = false;
  } else {
    BLE.setLocalName("GM1-Node");
    BLE.setDeviceName("GM1-Node");
    BLE.setAdvertisedService(gm1Service);
    gm1Service.addCharacteristic(gm1DataChar);
    BLE.addService(gm1Service);
    gm1DataChar.writeValue("ready");
    BLE.advertise();
    ble_ok = true;
    if (kDebugLogs) {
      Serial.println("[INFO] BLE advertising as GM1-Node");
    }
  }

  Serial.println("ax_g,ay_g,az_g,gx_dps,gy_dps,gz_dps,mx_raw,my_raw,mz_raw");
}

void loop() {
  if (ble_ok) {
    BLE.poll();
  }

  const float ax = imu.readFloatAccelX();
  const float ay = imu.readFloatAccelY();
  const float az = imu.readFloatAccelZ();
  const float gx = imu.readFloatGyroX();
  const float gy = imu.readFloatGyroY();
  const float gz = imu.readFloatGyroZ();

  int16_t mx = 0;
  int16_t my = 0;
  int16_t mz = 0;
  if (mag_ok && !mag.readRaw(mx, my, mz)) {
    mag_ok = false;
    if (kDebugLogs) {
      Serial.println("[WARN] Mag read failed, disabling mag stream");
    }
  }

  char line[128];
  snprintf(
      line,
      sizeof(line),
      "%.4f,%.4f,%.4f,%.2f,%.2f,%.2f,%d,%d,%d",
      ax,
      ay,
      az,
      gx,
      gy,
      gz,
      mx,
      my,
      mz);
  Serial.println(line);

  if (ble_ok && BLE.connected()) {
    gm1DataChar.writeValue(line);
  }

  delay(kPeriodMs);
}