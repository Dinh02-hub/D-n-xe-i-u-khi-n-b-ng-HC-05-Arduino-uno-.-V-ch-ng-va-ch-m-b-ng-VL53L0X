#include <cassert>
#include <cstdlib>
#include <iostream>
#include <limits>
#include "SoftwareSerial.h"
unsigned long testNow = 0, testMicros = 0;
uint64_t testClockUs = 0;
int testPins[20] = {}, testPwm[20] = {};
#include "../../src/Motor.cpp"
#include "../../src/Sensor.cpp"
#include "../../src/Bluetooth.cpp"
#include "../../xe_dieu_khien.ino"
using namespace Motor;
using namespace Sensor;
using namespace Bluetooth;
#undef assert
#define assert(condition) do { if (!(condition)) { std::cerr << "FAIL line " << __LINE__ << ": " << #condition << "\n"; std::exit(1); } } while (0)

void setClockUs(uint64_t value) {
  testClockUs = value;
  testMicros = static_cast<unsigned long>(value);
  testNow = static_cast<unsigned long>(value / 1000);
}
void advanceUs(unsigned long value) { setClockUs(testClockUs + value); }
void advanceMs(unsigned long value) { advanceUs(value * 1000UL); }
void digitalWrite(byte pin, int value) {
  // Chỉ đổi chân hướng khi cầu H đã ngắt.
  if (pin == IN1 || pin == IN2) assert(testPwm[ENA] == 0);
  if (pin == IN3 || pin == IN4) assert(testPwm[ENB] == 0);
  testPins[pin] = value;
}
void expect(int left, int right, int percent = -1) {
  assert(testPins[IN1] == (left > 0)); assert(testPins[IN2] == (left < 0));
  assert(testPins[IN3] == (right < 0)); assert(testPins[IN4] == (right > 0));
  if (percent < 0) percent = settings.speedPercent;
  int pwm = (percent * 255U + 50U) / 100U;
  assert(testPwm[ENA] == (left == 0 ? 0 : pwm));
  assert(testPwm[ENB] == (right == 0 ? 0 : pwm));
}
void expectBrake() {
  assert(stage == BRAKING);
  assert(testPins[IN1] == LOW && testPins[IN2] == LOW);
  assert(testPins[IN3] == LOW && testPins[IN4] == LOW);
  assert(testPwm[ENA] == 255 && testPwm[ENB] == 255);
}
void reset(bool connected = true) {
  setClockUs(0);
  for (int &pin : testPins) pin = LOW;
  for (int &pwm : testPwm) pwm = 0;
  BT.input.clear(); BT.output.clear();
  settings = Settings{};
  motorLeft = motorRight = 0;
  lastCommand = stageStarted = brakeStartedUs = 0;
  stage = READY;
  pending = 'S';
  needReleaseForward = false;
  configLength = position = length = 0;
  configStarted = lastRx = lastTx = lastReport = 0;
  invalid = false; dirty = true; lastState = nullptr;
  started = frontBlocked = false;
  distance = -1; lastPoll = lastResult = lastStart = 0;
  waitingForResult = true;
  Wire = TestWire{};
  Wire.connected = connected;
  Wire.rangeMm = 1200;
  setup();
  loop();
}
void send(char c) { BT.input.push_back(c); loop(); }
void text(const std::string &value) { BT.input.insert(BT.input.end(), value.begin(), value.end()); loop(); }
void manual(char c) {
  send(c);
  if (stage == TURNAROUND) { advanceMs(DIRECTION_SETTLE_MS); loop(); }
}
void sample(unsigned int mm) {
  Wire.rangeMm = mm;
  advanceMs(settings.sensorIntervalMs);
  Sensor::service(false);
  Motor::checkFront();
}
void obstacle() {
  reset(); sample(1102); assert(distance == 1102 && !blocked()); send('F'); expect(1, 1);
  sample(200); expectBrake(); assert(needReleaseForward);
}
void finishBrake() {
  advanceMs(settings.brakeMs - 1); loop(); expectBrake();
  advanceMs(1); loop(); expect(0, 0); assert(stage == READY);
}
void pumpTelemetry() { for (int i = 0; i < 300; ++i) { advanceUs(TELEMETRY_BYTE_US); sendTelemetry(); } }

int main() {
  reset(); expect(0, 0);
  assert(ENA == 3 && ENB == 6 && IN1 == 8 && IN2 == 9 && IN3 == 10 && IN4 == 11);
  assert(Wire.timeoutUs == 2500 && BT.rx == 4 && BT.tx == 5 && BT.baud == 38400);
  assert(settings.avoidanceEnabled && settings.speedPercent == 60 && settings.stopDistanceCm == 40);
  assert(settings.sensorIntervalMs == 60 && settings.slowdownZoneCm == 60 && settings.brakeMs == 30);
  for (char direction : {'F', 'B', 'L', 'R'}) {
    reset(); manual(direction);
    int left = direction == 'B' || direction == 'R' ? -1 : 1;
    int right = direction == 'B' || direction == 'L' ? -1 : 1;
    for (int speed : {30, 60, 100}) {
      text("V:" + std::to_string(speed) + "\n"); expect(left, right);
      assert(testPwm[ENA] == (speed == 30 ? 77 : speed == 60 ? 153 : 255));
    }
    text("V:0\n"); expect(0, 0); send(direction); expect(0, 0);
    text("V:60\n"); expect(0, 0); manual(direction); expect(left, right);
    send('S'); expect(0, 0); text("SV:100\nF"); expect(0, 0);
  }
  // Chờ trước khi đảo chiều, kể cả khi STOP đi ngay trước lệnh hướng ngược.
  reset(); send('F'); send('B'); expect(0, 0); assert(stage == TURNAROUND);
  advanceMs(50); send('B'); expect(0, 0);
  advanceMs(50); loop(); expect(-1, -1);
  send('S'); send('F'); expect(0, 0); advanceMs(DIRECTION_SETTLE_MS); loop(); expect(1, 1);
  send('B'); send('S'); advanceMs(DIRECTION_SETTLE_MS); loop(); expect(0, 0);

  reset(); text("Z:50\nD:1"); assert(settings.stopDistanceCm == 40); text("50\r\nI:40\n");
  assert(settings.stopDistanceCm == 150 && settings.sensorIntervalMs == 40);
  for (const std::string bad : {"D:0\n", "D:9\n", "D:151\n", "D:-1\n", "I:39\n", "I:201\n",
       "V:101\n", "V:9999999\n", "V:2x\n", "A:2\n", "A:01\n"}) {
    text(bad);
    assert(settings.stopDistanceCm == 150 && settings.sensorIntervalMs == 40);
    assert(settings.speedPercent == 60 && settings.avoidanceEnabled); expect(0, 0);
  }
  text("T:F\nP:B\nU:L\n"); expect(0, 0); text("D:8S0\nF"); expect(0, 0);
  text("I:1"); advanceMs(CONFIG_TIMEOUT_MS); manual('R'); expect(-1, 1);

  // Kiểm tra giới hạn ghép D/Z, khung dài và lệnh phanh hợp lệ.
  reset(); text("D:150\nZ:161\nE:0\nE:61\nZ:0000\nZ:-1\n");
  assert(settings.stopDistanceCm == 40 && settings.slowdownZoneCm == 60 && settings.brakeMs == 30);
  text("Z:160\nE:60\n"); assert(settings.slowdownZoneCm == 160 && settings.brakeMs == 60);
  text("D:41\nZ:1S0\nF"); expect(0, 0);
  assert(settings.stopDistanceCm == 40 && settings.slowdownZoneCm == 160);
  text("D:10\nZ:190\nI:200\nV:100\n");
  pumpTelemetry();
  assert(BT.output.find("CFG:1,100\nSAFE:10,200,190,60\n") != std::string::npos);

  // Mẫu mới cập nhật PWM qua loop, không cần heartbeat mới.
  reset(); text("V:100\n"); send('F');
  for (int mm : {1000, 850, 700, 550, 401, 1000}) {
    Wire.rangeMm = mm;
    advanceMs(settings.sensorIntervalMs);
    loop();
    int pwm = mm == 850 ? 210 : mm == 700 ? 166 : mm == 550 ? 121 : mm == 401 ? 77 : 255;
    assert(testPwm[ENA] == pwm && testPwm[ENB] == pwm);
    assert(settings.speedPercent == 100 && lastCommand == 0);
  }
  send('F');
  sample(850); assert(testPwm[ENA] == 210);
  text("V:60\n"); assert(testPwm[ENA] == 126 && testPwm[ENB] == 126);
  text("Z:30\n"); expect(1, 1);
  text("Z:60\n"); assert(testPwm[ENA] == 126);
  sample(8190); expect(1, 1);
  sample(850); assert(testPwm[ENA] == 126);
  text("A:0\n"); expect(1, 1);
  text("A:1\nZ:0\n"); expect(1, 1);
  sample(401); expect(1, 1);
  sample(400); expectBrake(); finishBrake();

  for (char direction : {'B', 'L', 'R'}) {
    reset(); send(direction); sample(700); sample(400);
    expect(direction == 'B' || direction == 'R' ? -1 : 1,
           direction == 'B' || direction == 'L' ? -1 : 1);
  }
  for (int duration : {1, 47, 60}) {
    reset(); text("E:" + std::to_string(duration) + "\n"); send('F');
    sample(400); expectBrake();
    text("E:30\n"); expectBrake();
    lastPoll -= settings.sensorIntervalMs;
    Wire.onRequest = [] { assert(!braking()); };
    advanceUs(duration * 1000UL - 1); loop(); expectBrake();
    advanceUs(1); loop(); expect(0, 0);
    sample(1200); send('F'); expect(0, 0);
  }

  // Các lệnh cấu hình cũ và hướng gửi trong lúc phanh không tạo chuyển động sau đó.
  for (char direction : {'F', 'B', 'L', 'R'}) {
    obstacle(); text("U:1\nT:500\nP:60\nV:70\n"); send(direction); expectBrake();
    finishBrake();
    advanceMs(COMMAND_TIMEOUT_MS); loop(); expect(0, 0);
    sample(8190); send('F'); expect(0, 0);
    sample(1102); send('F'); expect(0, 0);
    send('S'); manual('F'); expect(1, 1);
  }
  obstacle(); finishBrake();
  send('B'); expect(0, 0); assert(stage == TURNAROUND);
  advanceMs(DIRECTION_SETTLE_MS - 1); loop(); expect(0, 0);
  advanceMs(1); loop(); expect(-1, -1);

  // Phanh ngay cả khi còn dữ liệu Bluetooth chưa đọc hoặc chưa phát.
  reset(); send('F'); BT.input.assign(300, '?'); size_t sent = BT.output.size();
  sample(300); expectBrake(); assert(BT.available() == 300 && BT.output.size() == sent);
  advanceMs(settings.brakeMs); Motor::service(); expect(0, 0);
  advanceMs(DIRECTION_SETTLE_MS); Motor::service(); expect(0, 0);
  BT.input.push_front('S'); loop(); expect(0, 0);

  obstacle(); unsigned long brakeBegan = stageStarted;
  advanceMs(1); text("T:S"); expectBrake();
  advanceMs(10); send('S'); expectBrake(); assert(stageStarted == brakeBegan);
  advanceMs(settings.brakeMs - 11); loop(); expect(0, 0);
  advanceMs(500); loop(); expect(0, 0);

  // Mẫu vật cản không gia hạn watchdog sắp hết hạn.
  reset(); send('F'); advanceMs(430); sample(200); expectBrake();
  advanceMs(10); loop(); expectBrake(); assert(pending == 'S');
  advanceMs(settings.brakeMs - 10); loop(); expect(0, 0);

  reset(); text("A:0\n"); send('F'); sample(20); expect(1, 1);
  text("A:1\n"); expectBrake();
  finishBrake();
  advanceMs(300); send('F'); expect(0, 0);
  obstacle(); text("V:0\n"); expectBrake(); finishBrake();
  text("V:100\n"); expect(0, 0);

  reset(); text("D:10\n"); assert(settings.stopDistanceCm == 10);
  sample(101); send('F');
  assert(testPwm[ENA] == 46 && testPwm[ENB] == 46);
  sample(100); assert(frontBlocked); expectBrake();
  sample(8190); assert(!blocked());
  sample(140); assert(frontBlocked);
  sample(150); assert(!frontBlocked);
  reset(); text("I:40\n"); unsigned long first = lastPoll;
  advanceMs(39); loop(); assert(lastPoll == first);
  advanceMs(1); loop(); assert(lastPoll - first == 40);

  for (char direction : {'F', 'B', 'L', 'R'}) {
    reset(false); assert(distance == -1 && Wire.continuousStarts == 0);
    send(direction);
    expect(direction == 'B' || direction == 'R' ? -1 : 1,
           direction == 'B' || direction == 'L' ? -1 : 1);
    send('S'); expect(0, 0);
  }

  for (unsigned int bad : {0U, 19U, 2001U, 8190U, 65535U}) {
    reset(); send('F'); sample(bad); expect(1, 1);
    assert(distance == -1 && !blocked() && !braking());
    sample(bad); send('F'); expect(1, 1);
    send('S'); manual('L'); expect(1, -1);
    send('S'); manual('B'); expect(-1, -1);
    send('S'); manual('R'); expect(-1, 1);
  }
  reset(); sample(8190); send('F'); expect(1, 1);
  sample(8190); send('F'); expect(1, 1);
  reset(); send('L'); Wire.rangeStatus = 4; sample(1200); expect(1, -1); assert(distance == -1);
  reset(); send('R'); Wire.shortRead = true; sample(1200); expect(-1, 1); assert(distance == -1);
  reset(); send('F'); Wire.timeoutOnRead = true; sample(1200); expect(1, 1); assert(distance == -1);
  reset(); send('F'); Wire.connected = false; sample(1200); expect(1, 1); assert(distance == -1);
  reset(); text("D:10\n"); send('F'); sample(8190); expect(1, 1); assert(distance == -1);

  // Rút cảm biến khi đứng yên và cắm lại sau khi module mất cấu hình.
  reset(); sample(1200); pumpTelemetry();
  Wire.connected = false; loop(); expect(0, 0);
  pumpTelemetry();
  assert(BT.output.find("DIST:-1\nOBSTACLE:0\nSTATE:SENSOR_FAULT\n") != std::string::npos);
  Wire.connected = true; Wire.ready = false;
  advanceMs(RESTART_INTERVAL_MS); loop();
  assert(distance == -1 && Wire.continuousStarts == 2); expect(0, 0);
  Wire.ready = true; Wire.rangeMm = 1102; loop(); expect(0, 0);
  assert(distance == 1102);
  BT.output.clear(); pumpTelemetry();
  assert(BT.output.find("DIST:110.2\nOBSTACLE:0\nSTATE:READY\n") != std::string::npos);
  send('F'); expect(1, 1); sample(300); expectBrake();

  // Một cảm biến chưa khởi tạo được sẽ được thử lại sau khi xe dừng.
  reset(false); send('F'); expect(1, 1);
  Wire.connected = true;
  for (int i = 0; i < 6; i++) { advanceMs(175); send('F'); expect(1, 1); }
  assert(distance == -1 && Wire.continuousStarts == 0);
  send('S'); loop(); expect(0, 0); assert(Wire.continuousStarts == 1);
  loop(); assert(distance == 1200); expect(0, 0);

  // Nếu module còn cấu hình, số đo hợp lệ tự trở lại trong khi xe đang chạy.
  reset(); send('F'); Wire.connected = false; sample(1200); expect(1, 1);
  Wire.connected = true; sample(1200); expect(1, 1);
  assert(distance == 1200 && Wire.continuousStarts == 1);
  send('S'); expect(0, 0);

  reset(false); send('F');
  advanceMs(COMMAND_TIMEOUT_MS - 1); loop(); expect(1, 1);
  advanceMs(1); loop(); expect(0, 0);

  reset(); send('F'); Wire.ready = false;
  advanceMs(settings.sensorIntervalMs); loop();
  assert(distance == 1200); expect(1, 1);
  auto beforeStop = testClockUs;
  text("V:SF"); expect(0, 0);
  send('F'); expect(0, 0);  // S loại phần còn lại của khung, kể cả ở lượt đọc sau.
  assert(testClockUs == beforeStop && waitingForResult);
  text("\n");
  reset(); send('F'); Wire.ready = false;
  advanceMs(settings.sensorIntervalMs + SAMPLE_GRACE_MS); loop(); expect(1, 1);
  assert(distance == -1 && !frontBlocked && pending == 'S');
  obstacle(); sample(8190); expectBrake();
  loop(); expect(0, 0); assert(stage == READY && distance == -1);
  pumpTelemetry();
  assert(BT.output.find("DIST:-1\nOBSTACLE:0\nSTATE:SENSOR_FAULT\n") != std::string::npos);

  // STOP đã vào bộ đệm phải ngắt PWM trước khi chờ I²C.
  reset(); send('F');
  advanceMs(settings.sensorIntervalMs);
  Wire.onRequest = [] { expect(0, 0); advanceUs(2500); };
  beforeStop = testClockUs;
  text("BLSFR"); expect(0, 0);
  assert(testClockUs > beforeStop && stage == READY);

  // STOP đến trong lúc thăm dò I²C được xử lý ngay sau lượt đọc.
  reset(); send('F'); Wire.ready = false;
  advanceMs(settings.sensorIntervalMs);
  Wire.onRequest = [] { advanceUs(1000); BT.input.push_back('S'); advanceUs(1500); };
  beforeStop = testClockUs;
  loop(); expect(0, 0);
  assert(testClockUs - beforeStop == 2500);

  // STOP đến trong lúc đọc I²C hủy lệnh lùi thủ công đang chờ đảo chiều.
  reset(); send('F'); send('B'); assert(stage == TURNAROUND);
  advanceMs(DIRECTION_SETTLE_MS);
  Wire.onRequest = [] { BT.input.push_back('S'); };
  loop(); expect(0, 0); assert(stage == READY);
  reset(); send('F');
  for (int i = 0; i < 4; i++) { advanceMs(100); text("D:40\nI:40\nT:500\nP:30\nV:60\nA:1\n"); }
  assert(lastCommand == 0); advanceMs(100); loop(); expect(0, 0);
  for (char direction : {'F', 'B', 'L', 'R'}) {
    reset(); manual(direction);
    for (int i = 0; i < 8; i++) { advanceMs(175); send(direction); assert(motorLeft != 0); }
    advanceMs(499); send('?'); assert(motorLeft != 0);
    advanceMs(1); send('\n'); expect(0, 0);
  }

  for (uint64_t time : {static_cast<uint64_t>(std::numeric_limits<unsigned long>::max()) - 300,
       (static_cast<uint64_t>(std::numeric_limits<unsigned long>::max()) - 20) * 1000}) {
    reset(); setClockUs(time); stageStarted = millis() - DIRECTION_SETTLE_MS;
    Wire.rangeMm = 1200; Sensor::lastPoll = testNow - settings.sensorIntervalMs; loop();
    send('F'); sample(300); expectBrake(); finishBrake();
    advanceMs(200); send('F'); expect(0, 0);
  }
  reset(); sample(1200); pumpTelemetry();
  assert(BT.output.find("CFG:1,60\nSAFE:40,60,60,30\n") != std::string::npos);
  text("D:10\nI:40\nV:100\nA:0\n");
  sample(102); advanceMs(TELEMETRY_INTERVAL_MS);
  pumpTelemetry();
  assert(BT.output.find("DIST:10.2\nOBSTACLE:0\nSTATE:READY\nCFG:0,100\nSAFE:10,40,60,30\n") != std::string::npos);
  sample(8190); pumpTelemetry();
  assert(BT.output.find("DIST:-1\nOBSTACLE:0\nSTATE:SENSOR_FAULT\n") != std::string::npos);
  reset(); sample(167); pumpTelemetry();
  assert(BT.output.find("DIST:16.7\nOBSTACLE:1\nSTATE:BLOCKED\nCFG:1,60\nSAFE:40,60,60,30\n")
         != std::string::npos);
  // Telemetry phát theo nhịp và nhường lượt nhận cấu hình hoặc pha phanh.
  reset();
  sendTelemetry(); assert(BT.output.empty());
  advanceUs(RX_QUIET_US); sendTelemetry(); assert(BT.output.size() == 1);
  sendTelemetry(); assert(BT.output.size() == 1);
  advanceUs(TELEMETRY_BYTE_US - 1); sendTelemetry(); assert(BT.output.size() == 1);
  advanceUs(1); sendTelemetry(); assert(BT.output.size() == 2);
  configLength = 1; advanceUs(5000); sendTelemetry(); assert(BT.output.size() == 2);
  configLength = 0; lastRx = micros(); sendTelemetry(); assert(BT.output.size() == 2);
  advanceUs(RX_QUIET_US); sendTelemetry(); assert(BT.output.size() == 3);
  drive(1,1); lastCommand = millis();
  advanceMs(TELEMETRY_WINDOW_MS + 1); sendTelemetry(); assert(BT.output.size() == 3);
  lastCommand = millis(); sendTelemetry(); assert(BT.output.size() == 4);
  sample(200); expectBrake();
  advanceUs(5000); sendTelemetry(); assert(BT.output.size() == 4);

  reset(); send('F'); Wire.ready = false;
  pumpTelemetry();
  assert(BT.output.find("DIST:120.0\n") != std::string::npos);
  std::cout << "PASS: PWM ramp, electric brake timing, configuration/telemetry, GPIO, STOP, watchdog, reversal, sensor faults, release latch and clock wrap\n";
  return 0;
}
