#include <Arduino.h>
#include <ArduinoBLE.h>
#include <LSM6DS3.h>
#include <Wire.h>
#include <MadgwickAHRS.h> 

#include <DFRobot_BMM150.h> // <-- Replaced custom Mag header with DFRobot library

// --- User Constants ---
constexpr uint32_t kRecordPeriodMs   = 33; // <-- Changed to 33ms for ~30Hz
constexpr uint32_t kSleepPeriodMs    = 500;
constexpr float    kMotionThresholdG = 1.05f;
constexpr uint32_t kSleepAfterMs     = 10000;
constexpr uint32_t kLedBlinkHalfMs   = 500;
constexpr uint32_t kI2CFreqHz        = 100000;

// --- PicoRelay Protocol Constants ---
const char* kPicoRelayServiceUuid = "8a3e4d2f-1b6c-4f9e-a7d8-3e5b2c1f4a01";
const char* kPicoRelayDataInUuid  = "8a3e4d2f-1b6c-4f9e-a7d8-3e5b2c1f4a02";

constexpr uint8_t kFlagStart       = 0x80;
constexpr uint8_t kFlagEnd         = 0x40;
constexpr uint8_t kHeaderBytes     = 4;
constexpr uint8_t kPayloadPerFrame = 16;

struct __attribute__((packed)) SensorPacket {
  float ax; float ay; float az;
  float gx; float gy; float gz;
  int16_t mx; int16_t my; int16_t mz;
  float roll; float pitch; float yaw; 
};
static_assert(sizeof(SensorPacket) == 42, "SensorPacket must be 42 bytes"); 

// --- Devices ---
LSM6DS3 imu(I2C_MODE, 0x6A);
DFRobot_BMM150_I2C mag(&Wire, I2C_ADDRESS_4); // <-- Updated to use DFRobot class
Madgwick filter; 

BLEDevice phone;
BLECharacteristic dataIn;

// --- State Machine & Globals ---
enum class State { SLEEPING, RECORDING, PAUSED };
State state = State::RECORDING;

bool imu_ok = false;
bool mag_ok = false;
bool ble_ok = false;
bool motion_enabled = true;

uint32_t last_sample_ms = 0;
uint32_t last_motion_ms = 0;
uint32_t last_led_ms    = 0;
bool     led_on         = false;
uint32_t seq            = 0; 
uint8_t  nextMsgId      = 1;

// --- Helpers ---

void enableSenseRails() {
  pinMode(D30, OUTPUT); digitalWrite(D30, HIGH); 
  pinMode(D29, OUTPUT); digitalWrite(D29, HIGH); 
}

void setLed(bool on) {
  // LEDB is active-low on XIAO nRF52840.
  digitalWrite(LEDB, on ? LOW : HIGH);
}

float accelMag(float ax, float ay, float az) {
  return sqrtf(ax * ax + ay * ay + az * az);
}

// --- BLE Central Logic ---

void startScan() {
  Serial.println("[INFO] Scanning for PicoRelay (service 8a3e4d2f-...-4a01)");
  BLE.scanForUuid(kPicoRelayServiceUuid);
}

bool tryConnect() {
  BLEDevice candidate = BLE.available();
  if (!candidate) return false;

  Serial.print("[INFO] Found PicoRelay @ ");
  Serial.println(candidate.address());
  BLE.stopScan();

  if (!candidate.connect()) {
    Serial.println("[WARN] Connect failed, rescanning");
    startScan();
    return false;
  }

  if (!candidate.discoverAttributes()) {
    Serial.println("[WARN] Discover failed, disconnecting");
    candidate.disconnect();
    startScan();
    return false;
  }

  BLECharacteristic c = candidate.characteristic(kPicoRelayDataInUuid);
  if (!c || !c.canWrite()) {
    Serial.println("[WARN] Writable DataIn characteristic not found, disconnecting");
    candidate.disconnect();
    startScan();
    return false;
  }

  phone = candidate;
  dataIn = c;
  Serial.println("[INFO] Connected to phone, ready to stream via PicoRelay");
  return true;
}

bool sendPicoRelayMessage(BLECharacteristic& target, const uint8_t* data, size_t len, uint8_t msgId) {
  uint8_t frame[kHeaderBytes + kPayloadPerFrame];

  if (len <= kPayloadPerFrame) {
    frame[0] = kFlagStart | kFlagEnd;
    frame[1] = msgId;
    frame[2] = 0; frame[3] = 0;
    memcpy(&frame[kHeaderBytes], data, len);
    return target.writeValue(frame, kHeaderBytes + len);
  }

  uint16_t seq = 0;
  size_t offset = 0;
  while (offset < len) {
    const size_t remaining = len - offset;
    const size_t chunk = remaining < kPayloadPerFrame ? remaining : kPayloadPerFrame;
    
    uint8_t flags = 0;
    if (offset == 0) flags |= kFlagStart;
    if (offset + chunk >= len) flags |= kFlagEnd;

    frame[0] = flags;
    frame[1] = msgId;
    frame[2] = static_cast<uint8_t>(seq & 0xFF);
    frame[3] = static_cast<uint8_t>((seq >> 8) & 0xFF);
    memcpy(&frame[kHeaderBytes], &data[offset], chunk);

    if (!target.writeValue(frame, kHeaderBytes + chunk)) return false;

    offset += chunk;
    seq++;
  }
  return true;
}

// --- Main Data Pipeline ---

void sendSample(float ax, float ay, float az,
                float gx, float gy, float gz,
                int16_t mx, int16_t my, int16_t mz,
                float roll, float pitch, float yaw) { 
                  
  // 1. Output Serial CSV
  char line[150]; 
  snprintf(line, sizeof(line),
           "%lu,%.4f,%.4f,%.4f,%.2f,%.2f,%.2f,%d,%d,%d,%.2f,%.2f,%.2f",
           seq, ax, ay, az, gx, gy, gz, mx, my, mz, roll, pitch, yaw);
  Serial.println(line);

  // 2. Output to BLE (If connected)
  if (ble_ok && phone && phone.connected() && dataIn) {
    SensorPacket packet;
    packet.ax = ax; packet.ay = ay; packet.az = az;
    packet.gx = gx; packet.gy = gy; packet.gz = gz;
    packet.mx = mx; packet.my = my; packet.mz = mz;
    packet.roll = roll; packet.pitch = pitch; packet.yaw = yaw;

    const uint8_t msgId = nextMsgId++;
    const bool sent = sendPicoRelayMessage(dataIn, 
                                           reinterpret_cast<const uint8_t*>(&packet), 
                                           sizeof(packet), 
                                           msgId);
    
    // Drop connection and start scanning if write fails
    if (!sent) {
      Serial.println("[WARN] BLE write failed. Phone disconnected. Rescanning...");
      phone.disconnect();
      phone = BLEDevice();
      startScan();
    }
  }

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
      last_led_ms    = millis();
      led_on         = true;
      setLed(true);
    } else if (motion_enabled) {
      last_motion_ms = millis();
    }
    Serial.println(motion_enabled ? "# MOTION ON" : "# MOTION OFF");
  }
}

// --- Setup & Loop ---

void setup() {
  Serial.begin(115200);
  while (!Serial && millis() < 5000) { delay(10); }

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
    Serial.println("[ERROR] IMU init failed! Bypassing to allow BLE testing.");
    imu_ok = false;
  } else {
    imu_ok = true;
  }

  // --- DFRobot Magnetometer Init Block ---
  mag_ok = (mag.begin() == 0);
  if (mag_ok) {
    mag.setOperationMode(BMM150_POWERMODE_NORMAL);
    mag.setPresetMode(BMM150_PRESETMODE_HIGHACCURACY);
    mag.setRate(BMM150_DATA_RATE_30HZ);
    mag.setMeasurementXYZ();
  } else {
    Serial.println("[WARN] BMM150 init failed; mag columns will stream as 0");
  }
  
  // Set the filter sample rate based on your recording loop interval
  filter.begin(1000.0f / kRecordPeriodMs); 

  if (!BLE.begin()) {
    Serial.println("[FATAL] BLE init failed.");
    ble_ok = false;
  } else {
    ble_ok = true;
    startScan();
  }

  Serial.println("seq,ax_g,ay_g,az_g,gx_dps,gy_dps,gz_dps,mx_raw,my_raw,mz_raw,roll,pitch,yaw");
  Serial.println("# Commands: s=start  p=pause  m=toggle motion sleep/wake");

  const uint32_t now = millis();
  last_sample_ms = now;
  last_motion_ms = now;
  last_led_ms    = now;
}

void loop() {
  // Handle BLE background reconnect logic
  if (ble_ok && (!phone || !phone.connected())) {
    tryConnect();
  }

  handleSerialCommand();
  const uint32_t now = millis();

  // --- PAUSED ---
  if (state == State::PAUSED) {
    setLed(false);
    delay(50);
    return;
  }

  // --- SLEEPING ---
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

    // Light accel poll to detect motion
    float ax = 0, ay = 0, az = 0;
    if (imu_ok) {
      ax = imu.readFloatAccelX();
      ay = imu.readFloatAccelY();
      az = imu.readFloatAccelZ();
    }

    if (accelMag(ax, ay, az) >= kMotionThresholdG) {
      state = State::RECORDING;
      last_motion_ms = now;
      last_led_ms    = now;
      led_on         = true;
      setLed(true);
    }
    return;
  }

  // --- RECORDING ---
  
  if (static_cast<int32_t>(now - last_led_ms) >= static_cast<int32_t>(kLedBlinkHalfMs)) {
    last_led_ms = now;
    led_on = !led_on;
    setLed(led_on);
  }

  if (static_cast<int32_t>(now - last_sample_ms) < static_cast<int32_t>(kRecordPeriodMs)) {
    return;
  }
  last_sample_ms = now;

  float ax = 0, ay = 0, az = 0, gx = 0, gy = 0, gz = 0;
  float roll = 0, pitch = 0, yaw = 0; 

  if (imu_ok) {
    ax = imu.readFloatAccelX();
    ay = imu.readFloatAccelY();
    az = imu.readFloatAccelZ();
    gx = imu.readFloatGyroX();
    gy = imu.readFloatGyroY();
    gz = imu.readFloatGyroZ();

    // Update 6-axis Madgwick filter (Gyro in deg/s, Accel in g's)
    filter.updateIMU(gx, gy, gz, ax, ay, az);
    roll = filter.getRoll();
    pitch = filter.getPitch();
    yaw = filter.getYaw();
  }

  // --- DFRobot Magnetometer Read Block ---
  int16_t mx = 0, my = 0, mz = 0;
  if (mag_ok) {
    const sBmm150MagData_t magData = mag.getGeomagneticData();
    // Casting to int16_t to match the exact size requirements of your original BLE SensorPacket
    mx = static_cast<int16_t>(magData.x);
    my = static_cast<int16_t>(magData.y);
    mz = static_cast<int16_t>(magData.z);
  }

  sendSample(ax, ay, az, gx, gy, gz, mx, my, mz, roll, pitch, yaw);

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
