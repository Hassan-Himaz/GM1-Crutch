#include <Arduino.h>
#include <DFRobot_BMM150.h>
#include <LSM6DS3.h>
#include <MadgwickAHRS.h>
#include <Wire.h>


// Raw capture sampling period.
constexpr uint32_t kRecordPeriodMs = 33;  // ~30 Hz
constexpr float kSampleFreqHz = 1000.0f / kRecordPeriodMs;

// Sleep mode sampling period.
constexpr uint32_t kSleepPeriodMs = 500;

// Total acceleration magnitude threshold (g) that wakes the device.
constexpr float kMotionThresholdG = 1.05f;

// Seconds of no motion before entering sleep.
constexpr uint32_t kSleepAfterMs = 10000;

// LED blink half-period in recording mode (ms).
constexpr uint32_t kLedBlinkHalfMs = 500;

// I2C clock.
constexpr uint32_t kI2CFreqHz = 100000;


LSM6DS3 imu(I2C_MODE, 0x6A);
DFRobot_BMM150_I2C mag(&Wire, I2C_ADDRESS_4);
Madgwick madgwick;

enum class State { SLEEPING, RECORDING, PAUSED };
State state = State::PAUSED;

bool mag_ok = false;
bool motion_enabled = false;

uint32_t last_sample_ms = 0;
uint32_t last_motion_ms = 0;
uint32_t last_led_ms = 0;
bool led_on = false;
uint32_t seq = 0;


void handleSerialCommand();

void enableSenseRails() {
  pinMode(D30, OUTPUT);
  digitalWrite(D30, HIGH);  // VDD_ENV_ENABLE
  pinMode(D29, OUTPUT);
  digitalWrite(D29, HIGH);  // I2C_PULL
}

void setLed(bool on) {
  // LEDB is active-low on XIAO nRF52840.
  digitalWrite(LEDB, on ? LOW : HIGH);
}

float accelMag(float ax, float ay, float az) {
  return sqrtf(ax * ax + ay * ay + az * az);
}

void sendRawSample(float ax, float ay, float az,
                   float gx, float gy, float gz,
                   float roll, float pitch, float yaw,
                   float mx, float my, float mz) {
  Serial.print(seq);
  Serial.print(",");
  Serial.print(millis());
  Serial.print(",");
  Serial.print(ax, 4);
  Serial.print(",");
  Serial.print(ay, 4);
  Serial.print(",");
  Serial.print(az, 4);
  Serial.print(",");
  Serial.print(gx, 2);
  Serial.print(",");
  Serial.print(gy, 2);
  Serial.print(",");
  Serial.print(gz, 2);
  Serial.print(",");
  Serial.print(roll, 2);
  Serial.print(",");
  Serial.print(pitch, 2);
  Serial.print(",");
  Serial.print(yaw, 2);
  Serial.print(",");
  Serial.print(mx, 2);
  Serial.print(",");
  Serial.print(my, 2);
  Serial.print(",");
  Serial.print(mz, 2);
  Serial.println();

  ++seq;
}

void handleSerialCommand() {
  if (!Serial.available()) return;

  const char cmd = static_cast<char>(Serial.read());
  while (Serial.available() && Serial.peek() != '\n') Serial.read();
  if (Serial.available()) Serial.read();

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
      last_led_ms = millis();
      led_on = true;
      setLed(true);
    } else if (motion_enabled) {
      last_motion_ms = millis();
    }
    Serial.println(motion_enabled ? "# MOTION ON" : "# MOTION OFF");
  }
}

void setup() {
  Serial.begin(115200);
  delay(300);

  enableSenseRails();

#ifdef PIN_LSM6DS3TR_C_POWER
  pinMode(PIN_LSM6DS3TR_C_POWER, OUTPUT);
  digitalWrite(PIN_LSM6DS3TR_C_POWER, HIGH);
#else
  pinMode(D14, OUTPUT);
  digitalWrite(D14, HIGH);
#endif
  delay(500);

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

  mag_ok = (mag.begin() == 0);
  if (mag_ok) {
    mag.setOperationMode(BMM150_POWERMODE_NORMAL);
    mag.setPresetMode(BMM150_PRESETMODE_HIGHACCURACY);
    mag.setRate(BMM150_DATA_RATE_30HZ);
    mag.setMeasurementXYZ();
  }
  if (!mag_ok) {
    Serial.println("# WARN: BMM150 init failed; mag columns will stream as 0");
  }

  madgwick.begin(kSampleFreqHz);

  Serial.println("# BOOT: raw capture firmware ready - send 's' to start");
  Serial.println("seq,t_ms,ax_g,ay_g,az_g,gx_dps,gy_dps,gz_dps,roll_deg,pitch_deg,yaw_deg,mx_uT,my_uT,mz_uT");
  Serial.println("# Commands: s=start  p=pause  m=motion sleep/wake");

  const uint32_t now = millis();
  last_sample_ms = now;
  last_motion_ms = now;
  last_led_ms = now;
}

void loop() {
  handleSerialCommand();

  const uint32_t now = millis();

  if (state == State::PAUSED) {
    setLed(false);
    delay(50);
    return;
  }

  if (state == State::SLEEPING) {
    if (!motion_enabled) {
      state = State::RECORDING;
      last_motion_ms = now;
      last_led_ms = now;
      led_on = true;
      setLed(true);
      return;
    }

    setLed(false);

    if (static_cast<int32_t>(now - last_sample_ms) < static_cast<int32_t>(kSleepPeriodMs)) {
      return;
    }
    last_sample_ms = now;

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
      last_led_ms = now;
      led_on = true;
      setLed(true);
    }
    return;
  }

  if (static_cast<int32_t>(now - last_led_ms) >= static_cast<int32_t>(kLedBlinkHalfMs)) {
    last_led_ms = now;
    led_on = !led_on;
    setLed(led_on);
  }

  if (static_cast<int32_t>(now - last_sample_ms) < static_cast<int32_t>(kRecordPeriodMs)) {
    return;
  }
  last_sample_ms = now;

  const float ax = imu.readFloatAccelX();
  const float ay = imu.readFloatAccelY();
  const float az = imu.readFloatAccelZ();
  const float gx = imu.readFloatGyroX();
  const float gy = imu.readFloatGyroY();
  const float gz = imu.readFloatGyroZ();
  madgwick.updateIMU(gx, gy, gz, ax, ay, az);
  const float roll = madgwick.getRoll();
  const float pitch = madgwick.getPitch();
  const float yaw = madgwick.getYaw();

  float mx = 0.0f, my = 0.0f, mz = 0.0f;
  if (mag_ok) {
    const sBmm150MagData_t magData = mag.getGeomagneticData();
    mx = magData.xx;
    my = magData.yy;
    mz = magData.zz;
  }

  sendRawSample(ax, ay, az, gx, gy, gz, roll, pitch, yaw, mx, my, mz);

  if (motion_enabled) {
    if (accelMag(ax, ay, az) >= kMotionThresholdG) {
      last_motion_ms = now;
    }

    if (static_cast<int32_t>(now - last_motion_ms) >= static_cast<int32_t>(kSleepAfterMs)) {
      state = State::SLEEPING;
      setLed(false);
    }
  }
}
