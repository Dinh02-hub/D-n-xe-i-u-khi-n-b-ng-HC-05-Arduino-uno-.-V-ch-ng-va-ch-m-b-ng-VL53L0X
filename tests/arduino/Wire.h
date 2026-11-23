#pragma once
#include "Arduino.h"

// Mô phỏng các thanh ghi sẵn sàng và khoảng cách của cảm biến.
struct TestWire {
  bool connected = true, ready = true, timeout = false, timeoutOnRead = false, shortRead = false;
  byte rangeStatus = 11;
  unsigned int rangeMm = 600;
  unsigned int continuousStarts = 0;
  unsigned long timeoutUs = 0;
  byte reg = 0, offset = 0;
  void (*onRequest)() = nullptr;

  void begin() {}
  void setWireTimeout(unsigned long us, bool) { timeoutUs = us; }
  bool getWireTimeoutFlag() const { return timeout; }
  void clearWireTimeoutFlag() { timeout = false; }
  void beginTransmission(byte) {}
  void write(byte address) { reg = address; }
  byte endTransmission(bool) { return connected ? 0 : 2; }
  byte requestFrom(byte, byte count) {
    if (onRequest) onRequest();
    offset = 0;
    timeout = timeoutOnRead;
    return shortRead ? count - 1 : count;
  }
  int read() {
    if (reg == 0x13) return ready ? 4 : 0;
    switch (offset++) {
      case 0: return rangeStatus << 3;
      case 10: return rangeMm >> 8;
      case 11: return rangeMm & 0xff;
      default: return 0;
    }
  }
};
static TestWire Wire;
