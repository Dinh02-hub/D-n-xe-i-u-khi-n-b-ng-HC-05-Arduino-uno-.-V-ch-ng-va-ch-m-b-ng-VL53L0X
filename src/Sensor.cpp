#include "../include/Config.h"
#include "../include/Sensor.h"
#include <Wire.h>
#include <VL53L0X.h>

namespace Sensor
{
    namespace
    {
        VL53L0X sensor;
        bool started = false, frontBlocked = false;
        int distance = -1;  // 1 mm = 0,1 cm.
        constexpr unsigned long READY_POLL_MS = 2, SAMPLE_GRACE_MS = 50, RESTART_INTERVAL_MS = 1000;
        unsigned long lastPoll = 0, lastResult = 0, lastStart = 0;
        bool waitingForResult = true;

        bool readRegisters(byte reg, byte *data, byte count)
        {
            Wire.clearWireTimeoutFlag();
            Wire.beginTransmission(sensor.getAddress());
            Wire.write(reg);
            if (Wire.endTransmission(false) != 0 || Wire.requestFrom(sensor.getAddress(), count) != count ||
                Wire.getWireTimeoutFlag())
            {
                return false;
            }

            for (byte i = 0; i < count; ++i)
            {
                data[i] = Wire.read();
            }
            return true;
        }

        int readRange(unsigned long now)
        {
            byte result[12];
            if (!readRegisters(VL53L0X::RESULT_RANGE_STATUS, result, sizeof(result)))
            {
                return -1;
            }
            sensor.writeReg(VL53L0X::SYSTEM_INTERRUPT_CLEAR, 1);
            if (sensor.last_status || Wire.getWireTimeoutFlag())
            {
                return -1;
            }
            lastResult = now;

            // Mã 11 trong thanh ghi báo phép đo đã hoàn tất.
            if (((result[0] & 0x78) >> 3) != 11)
            {
                return -1;
            }
            unsigned int mm = (static_cast<unsigned int>(result[10]) << 8) | result[11];
            if (mm < MIN_SENSOR_DISTANCE_MM || mm > MAX_SENSOR_DISTANCE_CM * 10)
            {
                return -1;
            }
            return static_cast<int>(mm);
        }

        void startRanging()
        {
            Wire.clearWireTimeoutFlag();
            started = sensor.init() && !Wire.getWireTimeoutFlag();
            if (started)
            {
                sensor.startContinuous();
                started = sensor.last_status == 0 && !Wire.getWireTimeoutFlag();
            }

            lastStart = millis();
            lastResult = lastStart;
            lastPoll = lastStart - READY_POLL_MS;
            waitingForResult = true;
        }
    }

    void begin()
    {
        Wire.begin();  // Trên Uno, SDA ở A4 và SCL ở A5.
        Wire.setWireTimeout(2500, true);
        sensor.setTimeout(100);
        startRanging();
        if (!started)
        {
            Serial.println(F("VL53L0X init failed"));
        }
    }

    int distanceTenths()
    {
        return distance;
    }

    bool blocked()
    {
        return distance >= 0 && frontBlocked;
    }

    void updateObstacle()
    {
        if (distance < 0)
        {
            return;  // Giữ vùng trễ cho mẫu hợp lệ tiếp theo.
        }

        int threshold = settings.stopDistanceCm * 10;
        if (distance <= threshold)
        {
            frontBlocked = true;
        }
        else if (distance >= threshold + CLEAR_MARGIN_CM * 10)
        {
            frontBlocked = false;
        }
    }

    bool service(bool mayRestart)
    {
        unsigned long now = millis();
        unsigned long sampleTimeout = settings.sensorIntervalMs + SAMPLE_GRACE_MS;
        int previousDistance = distance;
        if (started && now - lastPoll >= (waitingForResult ? READY_POLL_MS : settings.sensorIntervalMs))
        {
            lastPoll = now;
            byte status;
            waitingForResult = readRegisters(VL53L0X::RESULT_INTERRUPT_STATUS, &status, 1);
            if (!waitingForResult)
            {
                distance = -1;
            }
            else if (status & 0x07)
            {
                distance = readRange(now);
                waitingForResult = false;
            }

            if (now - lastResult >= sampleTimeout)
            {
                distance = -1;
            }
            updateObstacle();
        }

        // Khởi tạo có thể chờ hiệu chuẩn. Chỉ thử lại khi xe đã dừng.
        if (mayRestart && (!started || now - lastResult >= sampleTimeout) && now - lastStart >= RESTART_INTERVAL_MS)
        {
            startRanging();
        }
        return distance != previousDistance;
    }
}
