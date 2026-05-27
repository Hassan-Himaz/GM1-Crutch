// PicoRelay-aware variant of BLE_Binary.ino.
//
// What changed from the original:
//   1. The Nano now acts as a BLE *central*: it scans for the PicoRelay
//      service on the phone, connects, and writes into DataIn.
//      (The original was advertising as a peripheral, which is why the
//      phone's Bytes In counter never moved.)
//   2. Each BLE write is wrapped in the 4-byte PicoRelay frame header
//      (FLAGS / MSG_ID / SEQ). A 30-byte SensorPacket is sent as either
//      one SOLO frame (if the MTU allows) or a START + END pair at the
//      default 23-byte ATT MTU.
//   3. Reconnect logic in the main loop; if the link drops, the sketch
//      goes back to scanning.

#include <Arduino.h>
#include <ArduinoBLE.h>
#include <LSM6DS3.h>
#include <Wire.h>

#include "src/MagBMM150.h"

LSM6DS3 imu(I2C_MODE, 0x6A);
MagBMM150 mag(Wire);

constexpr uint32_t kI2CFreqHz = 100000;
constexpr uint16_t kSampleHz  = 10;
constexpr uint32_t kPeriodMs  = 1000 / kSampleHz;

// PicoRelay BLE identifiers (must match the phone side; do not change).
const char* kPicoRelayServiceUuid = "8a3e4d2f-1b6c-4f9e-a7d8-3e5b2c1f4a01";
const char* kPicoRelayDataInUuid  = "8a3e4d2f-1b6c-4f9e-a7d8-3e5b2c1f4a02";

// PicoRelay frame header bits. See Maui/Docs/Pico_Quickstart.md §2.
constexpr uint8_t kFlagStart       = 0x80;
constexpr uint8_t kFlagEnd         = 0x40;
constexpr uint8_t kHeaderBytes     = 4;
// Payload capacity per frame at default 23-byte ATT MTU.
// (MTU 23 minus 3 bytes ATT overhead minus 4-byte PicoRelay header = 16.)
constexpr uint8_t kPayloadPerFrame = 16;

struct __attribute__((packed)) SensorPacket {
  float ax;
  float ay;
  float az;
  float gx;
  float gy;
  float gz;
  int16_t mx;
  int16_t my;
  int16_t mz;
};
static_assert(sizeof(SensorPacket) == 30, "SensorPacket must be 30 bytes");

bool mag_ok = false;
bool ble_ok = false;
uint8_t nextMsgId = 1;

BLEDevice phone;
BLECharacteristic dataIn;

void enableSenseRails() {
  pinMode(D30, OUTPUT); // VDD_ENV_ENABLE
  digitalWrite(D30, HIGH);
  pinMode(D29, OUTPUT); // I2C_PULL
  digitalWrite(D29, HIGH);
}

// Wraps `data` in one or more PicoRelay frames and writes them to `target`.
// Returns true if every write succeeded.
bool sendPicoRelayMessage(BLECharacteristic& target,
                          const uint8_t* data,
                          size_t len,
                          uint8_t msgId) {
  uint8_t frame[kHeaderBytes + kPayloadPerFrame];

  if (len <= kPayloadPerFrame) {
    // SOLO frame: whole message in one write.
    frame[0] = kFlagStart | kFlagEnd;
    frame[1] = msgId;
    frame[2] = 0;
    frame[3] = 0;
    memcpy(&frame[kHeaderBytes], data, len);
    return target.writeValue(frame, kHeaderBytes + len);
  }

  uint16_t seq = 0;
  size_t offset = 0;
  while (offset < len) {
    const size_t remaining = len - offset;
    const size_t chunk = remaining < kPayloadPerFrame ? remaining : kPayloadPerFrame;
    const bool isFirst = (offset == 0);
    const bool isLast  = (offset + chunk >= len);

    uint8_t flags = 0;
    if (isFirst) flags |= kFlagStart;
    if (isLast)  flags |= kFlagEnd;

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

void startScan() {
  Serial.println("[INFO] Scanning for PicoRelay (service 8a3e4d2f-...-4a01)");
  BLE.scanForUuid(kPicoRelayServiceUuid);
}

bool tryConnect() {
  BLEDevice candidate = BLE.available();
  if (!candidate) return false;

  Serial.print("[INFO] Found ");
  Serial.print(candidate.localName());
  Serial.print(" @ ");
  Serial.println(candidate.address());

  BLE.stopScan();

  Serial.println("[INFO] Connecting");
  if (!candidate.connect()) {
    Serial.println("[WARN] Connect failed, rescanning");
    startScan();
    return false;
  }

  Serial.println("[INFO] Discovering attributes");
  if (!candidate.discoverAttributes()) {
    Serial.println("[WARN] Discover failed, disconnecting");
    candidate.disconnect();
    startScan();
    return false;
  }

  BLECharacteristic c = candidate.characteristic(kPicoRelayDataInUuid);
  if (!c) {
    Serial.println("[WARN] DataIn characteristic not found, disconnecting");
    candidate.disconnect();
    startScan();
    return false;
  }
  if (!c.canWrite()) {
    Serial.println("[WARN] DataIn characteristic is not writable, disconnecting");
    candidate.disconnect();
    startScan();
    return false;
  }

  phone = candidate;
  dataIn = c;
  Serial.println("[INFO] Connected and ready to stream");
  return true;
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

  Wire.begin();
  Wire.setClock(kI2CFreqHz);

  if (imu.begin() != 0) {
    while (true) {
      Serial.println("[FATAL] IMU init failed");
      delay(1000);
    }
  }

  mag_ok = mag.begin();
  if (!mag_ok) {
    Serial.println("[WARN] Mag init failed, streaming IMU only");
  }

  if (!BLE.begin()) {
    Serial.println("[FATAL] BLE init failed");
    while (true) delay(1000);
  }
  ble_ok = true;
  Serial.println("[INFO] BLE initialised in central mode");
  startScan();
}

void loop() {
  if (!ble_ok) {
    delay(100);
    return;
  }

  // No live connection: keep scanning and attempt to connect.
  if (!phone || !phone.connected()) {
    tryConnect();
    delay(50);
    return;
  }

  SensorPacket packet;
  packet.ax = imu.readFloatAccelX();
  packet.ay = imu.readFloatAccelY();
  packet.az = imu.readFloatAccelZ();
  packet.gx = imu.readFloatGyroX();
  packet.gy = imu.readFloatGyroY();
  packet.gz = imu.readFloatGyroZ();
  packet.mx = 0;
  packet.my = 0;
  packet.mz = 0;
  // if (mag_ok && !mag.readRaw(packet.mx, packet.my, packet.mz)) {
  //   mag_ok = false;
  //   Serial.println("[WARN] Mag read failed, disabling mag stream");
  // }

  const uint8_t msgId = nextMsgId++;
  const bool sent = sendPicoRelayMessage(dataIn,
                                         reinterpret_cast<const uint8_t*>(&packet),
                                         sizeof(packet),
                                         msgId);
  if (!sent) {
    Serial.println("[WARN] Send failed; phone may have disconnected");
    phone.disconnect();
    phone = BLEDevice();
    startScan();
    return;
  }

  Serial.print("[INFO] Sent ");
  Serial.print(sizeof(packet));
  Serial.print(" bytes, msg_id=0x");
  Serial.println(msgId, HEX);

  delay(kPeriodMs);
}
