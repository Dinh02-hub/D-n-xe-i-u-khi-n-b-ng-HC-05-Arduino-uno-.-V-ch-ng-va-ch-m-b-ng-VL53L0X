#pragma once

namespace Sensor
{
    void begin();
    bool service(bool mayRestart);
    void updateObstacle();
    int distanceTenths();
    bool blocked();
}
