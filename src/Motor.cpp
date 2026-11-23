#include "../include/Config.h"
#include "../include/Motor.h"
#include "../include/Sensor.h"

namespace Motor
{
    namespace
    {
        enum Stage
        {
            READY,
            BRAKING,
            TURNAROUND
        };

        Stage stage = READY;
        unsigned long lastCommand = 0, stageStarted = 0;
        unsigned long brakeStartedUs = 0;
        byte brakeDuration = 0;
        int motorLeft = 0, motorRight = 0;
        char pending = 'S';
        bool needReleaseForward = false;

        void updatePwm()
        {
            int pwm = (settings.speedPercent * 255U + 50U) / 100U;
            if (settings.avoidanceEnabled && motorLeft > 0 && motorRight > 0)
            {
                int zone = settings.slowdownZoneCm * 10;
                int remaining = Sensor::distanceTenths() - settings.stopDistanceCm * 10;
                if (zone > 0 && remaining > 0 && remaining < zone)
                {
                    // Nội suy 30–100% theo mm, dùng 32 bit để tránh tràn trên Uno.
                    long scale = 10L * zone;
                    pwm = (pwm * (3L * zone + 7L * remaining) + scale / 2) / scale;
                }
            }

            analogWrite(ENA, motorLeft == 0 ? 0 : pwm);
            analogWrite(ENB, motorRight == 0 ? 0 : pwm);
        }

        void setMotor(byte pin1, byte pin2, int direction, bool inverted)
        {
            if (inverted)
            {
                direction = -direction;
            }
            digitalWrite(pin1, direction > 0 ? HIGH : LOW);
            digitalWrite(pin2, direction < 0 ? HIGH : LOW);
        }

        void drive(int left, int right)
        {
            if (motorLeft != left || motorRight != right)
            {
                analogWrite(ENA, 0);
                analogWrite(ENB, 0);
                setMotor(IN1, IN2, left, INVERT_LEFT);
                setMotor(IN3, IN4, right, INVERT_RIGHT);
                motorLeft = left;
                motorRight = right;
            }
            updatePwm();
        }

        void stopMotion()
        {
            if (motorLeft || motorRight || braking())
            {
                stageStarted = millis();
            }
            stage = READY;
            pending = 'S';
            drive(0, 0);
        }

        void brake()
        {
            // Đưa IN về LOW rồi bật EN để phanh điện.
            stopMotion();
            brakeDuration = settings.brakeMs;
            stage = BRAKING;
            analogWrite(ENA, 255);
            analogWrite(ENB, 255);
            brakeStartedUs = micros();
        }

        void applyCommand(char direction)
        {
            if (!settings.speedPercent)
            {
                return;
            }
            if (direction == 'F' && (needReleaseForward || (settings.avoidanceEnabled && Sensor::blocked())))
            {
                needReleaseForward = true;
                stopMotion();
                return;
            }
            if (stage == TURNAROUND)
            {
                pending = direction;
                return;
            }

            int left = direction == 'B' || direction == 'R' ? -1 : 1;
            int right = direction == 'B' || direction == 'L' ? -1 : 1;
            if (motorLeft * left < 0 || motorRight * right < 0)
            {
                stopMotion();
            }
            if (!active() && millis() - stageStarted < DIRECTION_SETTLE_MS)
            {
                stage = TURNAROUND;
                pending = direction;
                return;
            }
            drive(left, right);
        }
    }

    void begin()
    {
        const byte pins[] = {ENA, ENB, IN1, IN2, IN3, IN4};
        for (byte pin : pins)
        {
            pinMode(pin, OUTPUT);
        }

        analogWrite(ENA, 0);
        analogWrite(ENB, 0);
        setMotor(IN1, IN2, 0, INVERT_LEFT);
        setMotor(IN3, IN4, 0, INVERT_RIGHT);
        stageStarted = millis() - DIRECTION_SETTLE_MS;
    }

    bool active()
    {
        return motorLeft || motorRight || stage != READY;
    }

    unsigned long commandAge()
    {
        return millis() - lastCommand;
    }

    bool braking()
    {
        return stage == BRAKING;
    }

    void cancelMotion()
    {
        if (!braking())
        {
            stopMotion();
        }
    }

    void checkFront()
    {
        if (braking() || (!motorLeft && !motorRight))
        {
            return;
        }
        if (settings.avoidanceEnabled && motorLeft > 0 && motorRight > 0 && Sensor::blocked())
        {
            brake();
            needReleaseForward = true;
            return;
        }
        updatePwm();
    }

    void command(char direction)
    {
        bool wasBraking = braking();
        if (active() && commandAge() >= COMMAND_TIMEOUT_MS)
        {
            cancelMotion();
        }

        lastCommand = millis();
        if (direction == 'S')
        {
            needReleaseForward = false;
            cancelMotion();
        }
        else if (!wasBraking)
        {
            applyCommand(direction);
        }
        else if (direction == 'F')
        {
            // Heartbeat không mở khóa tiến sau khi gặp vật cản.
            needReleaseForward = true;
        }
    }

    void service()
    {
        unsigned long now = millis();
        if (active() && now - lastCommand >= COMMAND_TIMEOUT_MS)
        {
            cancelMotion();
        }

        unsigned long elapsed = now - stageStarted;
        if (braking() && micros() - brakeStartedUs >= brakeDuration * 1000UL)
        {
            stopMotion();
        }
        else if (stage == TURNAROUND && elapsed >= DIRECTION_SETTLE_MS)
        {
            char next = pending;
            stopMotion();
            applyCommand(next);
        }
    }
}
