#pragma once

#include <Arduino.h>
#include <Wire.h>

class MagBMM150 {
 public:
  static constexpr uint8_t kDefaultAddress = 0x13;
  static constexpr uint8_t kMinAddress = 0x10;
  static constexpr uint8_t kMaxAddress = 0x13;

  MagBMM150(TwoWire& wire, uint8_t address = kDefaultAddress);

  bool begin();
  bool readRaw(int16_t& mx, int16_t& my, int16_t& mz);
  uint8_t address() const { return address_; }

 private:
  bool writeReg(uint8_t reg, uint8_t value);
  bool readRegs(uint8_t start_reg, uint8_t* out, size_t len);
  bool isPresent();

  TwoWire& wire_;
  uint8_t address_;
};

