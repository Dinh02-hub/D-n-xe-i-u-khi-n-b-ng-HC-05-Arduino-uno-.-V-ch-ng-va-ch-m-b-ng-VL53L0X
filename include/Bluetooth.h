#pragma once

namespace Bluetooth
{
    void begin();
    // Xử lý STOP ngay. Trả 'S' nếu phải bỏ lệnh hướng trong vòng lặp này.
    char receive();
    void sendTelemetry();
}
