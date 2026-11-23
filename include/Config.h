#pragma once

#include <Arduino.h>

// Cầu H và thời gian an toàn khi lái.
constexpr byte IN1 = 8, IN2 = 9, IN3 = 10, IN4 = 11;
constexpr byte ENA = 3, ENB = 6;
constexpr bool INVERT_LEFT = false, INVERT_RIGHT = true;
constexpr unsigned long COMMAND_TIMEOUT_MS = 500;
constexpr unsigned long DIRECTION_SETTLE_MS = 100;
constexpr byte MIN_BRAKE_MS = 1, MAX_BRAKE_MS = 60;

// Cảm biến phía trước.
constexpr byte MIN_SENSOR_INTERVAL_MS = 40, MAX_SENSOR_INTERVAL_MS = 200;
constexpr byte MIN_STOP_DISTANCE_CM = 10, MAX_STOP_DISTANCE_CM = 150;
constexpr byte CLEAR_MARGIN_CM = 5;
constexpr int MIN_SENSOR_DISTANCE_MM = 20;
constexpr int MAX_SENSOR_DISTANCE_CM = 200;

// Bluetooth và nhịp gửi trạng thái.
constexpr byte BT_RX = 4, BT_TX = 5;
constexpr unsigned long CONFIG_TIMEOUT_MS = 100, TELEMETRY_INTERVAL_MS = 250;
constexpr unsigned long RX_QUIET_US = 1500, TELEMETRY_BYTE_US = 1000;
constexpr unsigned long TELEMETRY_WINDOW_MS = 100;

struct Settings
{
    bool avoidanceEnabled = true;
    byte speedPercent = 60;
    byte stopDistanceCm = 40;
    byte sensorIntervalMs = 60;
    byte slowdownZoneCm = 60;
    byte brakeMs = 30;
};

extern Settings settings;
