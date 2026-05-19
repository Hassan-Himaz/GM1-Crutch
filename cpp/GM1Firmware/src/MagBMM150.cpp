#include "MagBMM150.h"

namespace {
constexpr uint8_t REG_CHIP_ID = 0x40;
constexpr uint8_t REG_POWER = 0x4B;
constexpr uint8_t REG_OPMODE = 0x4C;
constexpr uint8_t REG_REP_XY = 0x51;
constexpr uint8_t REG_REP_Z = 0x52;
constexpr uint8_t REG_DATA = 0x42;
constexpr uint8_t CHIP_ID_BMM150 = 0x32;
}  // namespace

MagBMM150::MagBMM150(TwoWire& wire, uint8_t address)
    : wire_(wire), address_(address) {}

bool MagBMM150::begin() {
  // Try currently configured address first, then all valid addresses.
  const uint8_t preferred = address_;
  bool tried[128] = {false};

  for (uint8_t pass = 0; pass < 2; ++pass) {
    const uint8_t start = (pass == 0) ? preferred : kMinAddress;
    const uint8_t end = (pass == 0) ? preferred : kMaxAddress;
    for (uint8_t candidate = start; candidate <= end; ++candidate) {
      if (candidate < kMinAddress || candidate > kMaxAddress) continue;
      if (tried[candidate]) continue;
      tried[candidate] = true;
      address_ = candidate;

      // BMM150 starts in suspend mode. Wake it before CHIP_ID read.
      if (!writeReg(REG_POWER, 0x01)) continue;
      delay(3);
      if (!isPresent()) continue;

      // Put device in normal mode so data registers update continuously.
      // 0x00 => normal mode, default ODR.
      if (!writeReg(REG_OPMODE, 0x00)) continue;
      // Minimal repetition settings for valid XYZ updates.
      if (!writeReg(REG_REP_XY, 0x04)) continue;
      if (!writeReg(REG_REP_Z, 0x0F)) continue;
      delay(10);
      return true;
    }
  }
  return false;
}

bool MagBMM150::readRaw(int16_t& mx, int16_t& my, int16_t& mz) {
  uint8_t data[8] = {0};
  if (!readRegs(REG_DATA, data, sizeof(data))) {
    return false;
  }

  // Raw bit packing from datasheet / common drivers:
  // X/Y are 13-bit values in bits [15:3], Z is 15-bit in bits [15:1]
  int16_t raw_x = static_cast<int16_t>(data[1] << 8 | data[0]);
  int16_t raw_y = static_cast<int16_t>(data[3] << 8 | data[2]);
  int16_t raw_z = static_cast<int16_t>(data[5] << 8 | data[4]);

  mx = raw_x >> 3;
  my = raw_y >> 3;
  mz = raw_z >> 1;
  return true;
}

bool MagBMM150::writeReg(uint8_t reg, uint8_t value) {
  wire_.beginTransmission(address_);
  wire_.write(reg);
  wire_.write(value);
  return wire_.endTransmission() == 0;
}

bool MagBMM150::readRegs(uint8_t start_reg, uint8_t* out, size_t len) {
  wire_.beginTransmission(address_);
  wire_.write(start_reg);
  if (wire_.endTransmission(false) != 0) {
    return false;
  }

  const size_t count = wire_.requestFrom(static_cast<int>(address_), static_cast<int>(len));
  if (count != len) {
    return false;
  }

  for (size_t i = 0; i < len; ++i) {
    out[i] = static_cast<uint8_t>(wire_.read());
  }
  return true;
}

bool MagBMM150::isPresent() {
  // Check device responds and chip ID is expected.
  uint8_t id = 0;
  if (!readRegs(REG_CHIP_ID, &id, 1)) {
    return false;
  }
  return id == CHIP_ID_BMM150;
}

