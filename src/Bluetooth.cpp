#include "../include/Bluetooth.h"
#include "../include/Config.h"
#include "../include/Motor.h"
#include "../include/Sensor.h"
#include <SoftwareSerial.h>
#include <stdio.h>
#include <string.h>

Settings settings;

namespace Bluetooth
{
    namespace
    {
        SoftwareSerial BT(BT_RX, BT_TX);
        char configLine[5];
        char telemetry[96];
        byte configLength = 0, position = 0, length = 0;
        unsigned long configStarted = 0, lastRx = 0, lastTx = 0, lastReport = 0;
        bool invalid = false, dirty = true;
        const char *lastState = nullptr;

        bool applyConfig()
        {
            if (invalid || configLength < 3 || configLine[1] != ':')
            {
                return false;
            }
            unsigned int value = 0;
            for (byte i = 2; i < configLength; ++i)
            {
                char digit = configLine[i];
                if (digit < '0' || digit > '9')
                {
                    return false;
                }
                value = value * 10 + (digit - '0');
            }

            switch (configLine[0])
            {
            case 'A':
                if (configLength != 3 || value > 1)
                {
                    return false;
                }
                settings.avoidanceEnabled = value == 1;
                break;
            case 'V':
                if (value > 100)
                {
                    return false;
                }
                settings.speedPercent = value;
                if (value == 0)
                {
                    Motor::cancelMotion();
                    dirty = true;
                    return true;
                }
                break;
            case 'D':
                if (value < MIN_STOP_DISTANCE_CM || value > MAX_STOP_DISTANCE_CM ||
                    value + settings.slowdownZoneCm > MAX_SENSOR_DISTANCE_CM)
                {
                    return false;
                }
                settings.stopDistanceCm = value;
                Sensor::updateObstacle();
                break;
            case 'I':
                if (value < MIN_SENSOR_INTERVAL_MS || value > MAX_SENSOR_INTERVAL_MS)
                {
                    return false;
                }
                settings.sensorIntervalMs = value;
                break;
            case 'Z':
                if (value + settings.stopDistanceCm > MAX_SENSOR_DISTANCE_CM)
                {
                    return false;
                }
                settings.slowdownZoneCm = value;
                break;
            case 'E':
                if (value < MIN_BRAKE_MS || value > MAX_BRAKE_MS)
                {
                    return false;
                }
                settings.brakeMs = value;
                break;
            default:
                return false;
            }

            Motor::checkFront();
            dirty = true;
            return false;
        }
    }

    void begin()
    {
        Serial.begin(9600);
        BT.begin(38400);
    }

    char receive()
    {
        if (configLength && millis() - configStarted >= CONFIG_TIMEOUT_MS)
        {
            configLength = 0;
        }

        bool stopped = false;
        char newest = '\0';
        for (byte budget = 32; budget && BT.available(); --budget)
        {
            char c = static_cast<char>(BT.read());
            lastRx = micros();
            if (c == 'S')
            {
                // Bỏ phần còn lại của khung bị STOP ngắt để khỏi đọc nhầm lệnh lái.
                invalid |= configLength != 0;
                stopped = true;
                Motor::command('S');
            }
            else if (configLength)
            {
                if (c == '\n')
                {
                    stopped |= applyConfig();
                    configLength = 0;
                }
                else if (c != '\r')
                {
                    if (configLength < sizeof(configLine))
                    {
                        configLine[configLength++] = c;
                    }
                    else
                    {
                        invalid = true;
                    }
                }
            }
            else if (c && strchr("AVDIZETPU", c))
            {
                // Tiêu thụ cả khung T/P/U cũ để tránh nhận nhầm lệnh hướng.
                invalid = false;
                configLength = 1;
                configLine[0] = c;
                configStarted = millis();
            }
            else if (c && strchr("FBLR", c))
            {
                newest = c;
            }
        }
        return stopped ? 'S' : newest;
    }

    void sendTelemetry()
    {
        unsigned long nowUs = micros();
        unsigned long now = millis();
        if (Motor::braking() || configLength || BT.available() || nowUs - lastRx < RX_QUIET_US ||
            nowUs - lastTx < TELEMETRY_BYTE_US ||
            (Motor::active() && Motor::commandAge() > TELEMETRY_WINDOW_MS))
        {
            return;
        }

        if (position == length)
        {
            int distance = Sensor::distanceTenths();
            bool obstacle = Sensor::blocked();
            const char *state = "READY";
            if (distance < 0)
            {
                state = "SENSOR_FAULT";
            }
            else if (obstacle)
            {
                state = "BLOCKED";
            }
            if (!dirty && lastState && strcmp(state, lastState) == 0 &&
                now - lastReport < TELEMETRY_INTERVAL_MS)
            {
                return;
            }

            char cm[8];
            if (distance < 0)
            {
                strcpy(cm, "-1");
            }
            else
            {
                snprintf(cm, sizeof(cm), "%d.%d", distance / 10, distance % 10);
            }
            int size = snprintf(telemetry, sizeof(telemetry),
                "DIST:%s\nOBSTACLE:%d\nSTATE:%s\nCFG:%d,%u\nSAFE:%u,%u,%u,%u\n",
                cm, obstacle, state, settings.avoidanceEnabled, unsigned(settings.speedPercent),
                unsigned(settings.stopDistanceCm), unsigned(settings.sensorIntervalMs),
                unsigned(settings.slowdownZoneCm), unsigned(settings.brakeMs));
            length = size > 0 && size < static_cast<int>(sizeof(telemetry)) ? size : 0;
            position = 0;
            dirty = false;
            lastReport = now;
            lastState = state;
        }

        if (position < length)
        {
            lastTx = nowUs;
            BT.write(static_cast<uint8_t>(telemetry[position++]));
        }
    }
}
