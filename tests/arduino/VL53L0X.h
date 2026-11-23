#pragma once
#include "Wire.h"

struct VL53L0X {
  enum { RESULT_INTERRUPT_STATUS = 0x13, RESULT_RANGE_STATUS = 0x14, SYSTEM_INTERRUPT_CLEAR = 0x0b };
  byte last_status = 0;
  bool init() const { return Wire.connected; }
  byte getAddress() const { return 0x29; }
  void setTimeout(unsigned int) {}
  void startContinuous() { ++Wire.continuousStarts; }
  void writeReg(byte, byte) { last_status = Wire.connected ? 0 : 2; }
};
