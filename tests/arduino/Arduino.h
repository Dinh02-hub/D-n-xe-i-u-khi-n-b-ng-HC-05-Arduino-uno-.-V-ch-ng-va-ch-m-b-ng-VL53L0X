#pragma once
#include <cstdint>
#include <cassert>

// Host-only Arduino API. Production uses the header from the AVR core.
using byte = uint8_t;
const int LOW = 0, HIGH = 1, OUTPUT = 1, INPUT = 0;
extern unsigned long testNow;
extern unsigned long testMicros;
extern int testPins[20];
extern int testPwm[20];
inline unsigned long millis() { return testNow; }
inline unsigned long micros() { return testMicros; }
inline void pinMode(byte, int) { }
void digitalWrite(byte pin, int value);
inline void analogWrite(byte pin, int value) {
  assert((pin == 3 || pin == 6) && value >= 0 && value <= 255);
  testPwm[pin] = value;
}
#define F(text) text
struct DebugSerial { void begin(int) { } void println(const char *) { } };
static DebugSerial Serial;
