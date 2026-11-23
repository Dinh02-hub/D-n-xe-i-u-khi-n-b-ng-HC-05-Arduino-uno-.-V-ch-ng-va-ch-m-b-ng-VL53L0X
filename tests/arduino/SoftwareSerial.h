#pragma once
#include "Arduino.h"
#include <deque>
#include <string>

// Host-only shim. The production sketch still builds against real SoftwareSerial.
class SoftwareSerial {
 public:
  int rx, tx, baud = 0;
  std::deque<char> input;
  std::string output;
  SoftwareSerial(int rx, int tx) : rx(rx), tx(tx) { }
  void begin(int value) { baud = value; }
  int available() { return static_cast<int>(input.size()); }
  char read() { char value = input.front(); input.pop_front(); return value; }
  void write(uint8_t value) { output += static_cast<char>(value); }
};
