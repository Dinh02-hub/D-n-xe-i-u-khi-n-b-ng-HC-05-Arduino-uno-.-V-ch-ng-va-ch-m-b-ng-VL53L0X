#pragma once

namespace Motor
{
    void begin();
    void command(char direction);
    void service();
    void checkFront();
    void cancelMotion();
    bool active();
    unsigned long commandAge();
    bool braking();
}
