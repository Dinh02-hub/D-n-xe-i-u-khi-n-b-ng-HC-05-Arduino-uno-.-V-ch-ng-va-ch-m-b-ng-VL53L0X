#include "include/Bluetooth.h"
#include "include/Motor.h"
#include "include/Sensor.h"

void setup()
{
    Motor::begin();
    Bluetooth::begin();
    Sensor::begin();
}

void loop()
{
    char direction = Bluetooth::receive();
    // Nhường thời gian cho xung phanh ngắn, kể cả khi I²C đang gặp lỗi.
    if (!Motor::braking() && Sensor::service(!direction && !Motor::active()))
    {
        Motor::checkFront();
    }

    char latest = Bluetooth::receive();
    if (direction != 'S' && latest != 'S')
    {
        char command = latest ? latest : direction;
        if (command)
        {
            Motor::command(command);
        }
    }

    Motor::service();
    Bluetooth::sendTelemetry();
}
