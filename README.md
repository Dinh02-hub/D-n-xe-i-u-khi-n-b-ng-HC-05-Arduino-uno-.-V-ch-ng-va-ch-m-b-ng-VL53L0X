# Arduino Drive — điều khiển xe và công suất PWM

Arduino UNO R3 + L298N + HC-05 + VL53L0X, ứng dụng Android Java/XML. Điều khiển F/B/L/R/S, heartbeat 175 ms, watchdog 500 ms. Thanh trượt điều chỉnh **mức PWM 0–100%**, không đo vận tốc bánh xe.

Firmware giảm PWM tuyến tính và phanh điện khi xe tiến gần vật cản. Android đồng bộ bốn thông số Chống va chạm và giữ điều chỉnh công suất trên màn hình Điều khiển.

## Đấu dây PWM trước khi chạy

**Chưa xác nhận phần cứng đã được đấu. Tắt nguồn UNO và nguồn motor trước khi tháo jumper hoặc đổi dây.**

1. Tháo **hai jumper ENA và ENB** trên L298N. Không nhầm với jumper chọn nguồn/regulator 5V của module.
2. Nối chân tín hiệu ENA tới D3, ENB tới D6. Không để jumper buộc ENA/ENB lên 5V khi nối với chân PWM Arduino.
3. Đấu IN, Bluetooth và VL53L0X theo bảng. Nối chung GND UNO, L298N, HC-05 và VL53L0X. Không cấp motor từ chân 5V UNO.

| Arduino UNO | Thiết bị |
|---|---|
| D3 PWM | L298N ENA, đã tháo jumper ENA |
| D6 PWM | L298N ENB, đã tháo jumper ENB |
| D8 / D9 | L298N IN1 / IN2 |
| D10 / D11 | L298N IN3 / IN4 |
| D4 RX | HC-05 TXD |
| D5 TX | HC-05 RXD qua chia áp phù hợp |
| A4 / A5 | VL53L0X SDA / SCL |
| Nguồn phù hợp / GND | VL53L0X VIN / GND. Kiểm tra điện áp cấp và mức I²C theo module trước khi đấu vào UNO. |

Giữ BT.begin(38400), Serial Monitor 9600; UART chế độ dữ liệu của HC-05 phải khớp sketch. Giữ INVERT_LEFT=false, INVERT_RIGHT=true và các hướng motor hiện tại.

## Nạp Arduino và chạy Android

1. Kê bánh khỏi mặt đất, để trống phía sau xe; kiểm tra nguồn và dây ENA/ENB đã tháo jumper.
2. Cài **VL53L0X by Pololu** bằng Library Manager. Mở [xe_dieu_khien.ino](xe_dieu_khien.ino) ở thư mục gốc, chọn **Arduino Uno** và đúng cổng COM, rồi Verify và Upload.
3. Motor dừng khi khởi động. Nếu VL53L0X khởi tạo thất bại, xe vẫn nhận lệnh lái thủ công và tự thử lại cảm biến khi xe đang dừng.
4. Build Android bằng JDK 17/21, SDK 35, Build Tools 35.0.0 có sẵn, trong thư mục android:
   ~~~powershell
   .\gradlew.bat assembleDebug testDebugUnitTest lintDebug
   ~~~
5. Cài [APK debug](android/app/build/outputs/apk/debug/app-debug.apk), tắt **Mô phỏng khi mở Điều khiển**, ghép đôi HC-05 trong Cài đặt hệ thống rồi chọn thiết bị trong app. Đóng terminal Bluetooth khác trước khi kết nối.
6. Mỗi phiên Bluetooth mới bắt đầu bằng STOP và cấu hình mặc định của phiên: hỗ trợ bật, PWM 60%. Chờ “Arduino đã xác nhận” trước khi chỉnh slider. Chưa nhận CFG hợp lệ hoặc dữ liệu cấu hình quá 1 giây: hiện “—%”, khóa slider, không giả vờ giá trị mới đã áp dụng. Lái thủ công vẫn độc lập với việc thiếu xác nhận cấu hình.
7. Giữ một hướng rồi kéo slider: công suất đổi ngay khi Arduino nhận lệnh. Chọn 0% sẽ dừng và xóa nút giữ; tăng lại cần nhấn hướng mới.

Firmware chỉ có CFG vẫn dùng được thanh công suất lái. Firmware trả SAFE với hai trường cuối bằng 0 cho chỉnh khoảng cách phanh và chu kỳ đọc. Cập nhật firmware để chỉnh cả bốn thông số.

Trong **Cài đặt**, các thanh trượt gửi khi thả tay hoặc sau 250 ms khi dùng phím/trợ năng. Mở Cài đặt từ phiên điều khiển để dùng chung kết nối HC-05. Khi chưa nhận SAFE hoặc dữ liệu quá 1 giây, các thanh trượt bị khóa. Sau 2 giây thiếu xác nhận khớp, ứng dụng báo chưa xác nhận. Bốn thông số lưu trong RAM Arduino đến khi reset.

| Thông số | Khoảng | Mặc định | Lệnh |
|---|---|---|---|
| Khoảng cách phanh | 10–150 cm | 40 cm | D:n + LF |
| Chu kỳ đọc VL53L0X | 40–200 ms | 60 ms | I:n + LF |
| Độ rộng vùng giảm tốc | 0–190 cm | 60 cm | Z:n + LF |
| Thời gian phanh điện | 1–60 ms | 30 ms | E:n + LF |

Khoảng cách phanh và vùng giảm tốc cộng lại tối đa 200 cm. Đặt vùng giảm tốc về 0 cm để chỉ phanh ở ngưỡng. Công tắc **Chống va chạm** trên màn hình Điều khiển bật hoặc tắt cả hai hành vi.

Công suất lái V:0..100 vẫn ở màn hình điều khiển. Đồng hồ bán nguyệt 180° ôm quanh số phần trăm; khi kéo, số và cung cùng xem trước giá trị bằng animation nhẹ. Nhãn “Xem trước · Arduino n%” giữ rõ giá trị đã được xe xác nhận; quá hạn ACK sẽ trả đồng hồ về mức đã xác nhận. Đồng hồ không đo RPM.

Lần mở đầu dùng Sáng: nền #F5F5F5, chữ #1D1D1D, điều khiển đen/trắng/xám. Tối dùng nền xám than #1C1C1C, chữ #F5F5F5 và điều khiển xám/trắng tương ứng. Hệ thống theo chế độ sáng/tối của thiết bị và dùng cùng bảng màu với Sáng/Tối, không có palette riêng. Chỉ giữ màu đỏ cho STOP và cảnh báo. Màu lấy qua color resources và theme attributes; thiết kế và animation đồng hồ không đổi. Xem [kiểm chứng màu đơn sắc](docs/MONOCHROME_VERIFICATION.md).

## Hành vi PWM và các bảo vệ

- analogWrite() cho ENA/ENB với PWM làm tròn từ phần trăm: 0→0, 30→77, 60→153, 100→255. Hai motor dùng mức công suất lái V. Không delay để giả lập công suất.
- Đổi công suất dương chỉ cập nhật đầu ra đang chạy; không tạo hướng mới, không phát lại hướng sau STOP hoặc sau 0%.
- Lệnh tốc độ không gia hạn watchdog. STOP vẫn được ưu tiên ngay cả trong một khung cấu hình dở; không có lệnh lái hợp lệ trong 500 ms thì dừng.
- Android gửi tối đa một giá trị slider mỗi 120 ms, gộp các giá trị cũ. Khi xem trước, trạng thái vẫn hiển thị mức Arduino đã xác nhận. Chỉnh về 0% gửi STOP để xóa các nút giữ ngay.
- Mặc định chặn tiến ≤40 cm và hết trạng thái vật cản ≥45 cm. Sau khi phanh do vật cản, cần nhả hết nút lái hoặc nhấn STOP trước khi tiến lại.
- Khi đang tiến và bật Chống va chạm, PWM giảm từ 100% mức yêu cầu ở D+Z xuống gần 30% ngay trên D. Từ D trở xuống, xe phanh điện trong thời gian E rồi dừng. Hướng nhận trong xung phanh không được lưu.
- Đảo hướng thủ công ngắt EN và chờ 100 ms. Heartbeat giữ nguyên thời điểm kết thúc khoảng chờ. Firmware tạm hoãn đọc VL53L0X trong xung phanh.
- Khi lỗi cảm biến, xe vẫn nhận lệnh lái thủ công. Chỉ mẫu hợp lệ mới có thể kích hoạt bảo vệ theo khoảng cách. Android hiện thanh cảnh báo và khoảng cách `--` cho đến khi có số đo hợp lệ. Xe không có cảm biến sau.

## Kiểm tra trước khi đặt xuống sàn

Đo và chọn khoảng cách phanh lớn hơn quãng xe đi trong lúc chờ mẫu kế tiếp, cộng quãng dừng đo được và khoảng dự phòng. Chu kỳ đọc mặc định là 60 ms, xung phanh mặc định là 30 ms.

Vòng lặp chỉ đọc số đo khi VL53L0X báo có kết quả. Tham số I là khoảng chờ giữa các lượt đọc của UNO, độc lập với thời gian đo bên trong VL53L0X. Telemetry nhường RX trong pha phanh và khi đang nhận cấu hình. Cần đo độ trễ STOP và tỷ lệ mất byte trên HC-05 thật. [Tài liệu SoftwareSerial](https://docs.arduino.cc/learn/built-in-libraries/software-serial)

- Kê bánh; lần lượt thử tiến/lùi/trái/phải ở 30%, 60%, 100%, rồi 0%.
- Khi đang giữ hướng, đổi mức dương: hướng vẫn giữ, chỉ PWM thay đổi. 0% rồi tăng lên: phải còn đứng yên cho tới khi nhấn hướng mới.
- Thử STOP và mất lệnh khi đang chạy; tiếp tục gửi V nhưng ngừng F/B/L/R/S không được kéo dài chuyển động quá watchdog.
- Thử xe phanh rồi dừng khi gặp vật cản và kiểm tra lùi bằng nút Lùi sau pha phanh. Thử khi hỗ trợ tắt để xác nhận đổi tốc độ không tự bật lại hỗ trợ.
- Mất Bluetooth: không giữ số phần trăm cũ như giá trị đang được xác nhận. Kết nối lại: dừng và đồng bộ cấu hình phiên mới.
- Chỉ đặt xuống sàn trống sau khi các bước trên đạt; đo quãng dừng thực, đặc biệt ở PWM cao.

**Chưa kiểm chứng dòng phanh.** Dùng nguồn giới hạn dòng và đo dòng, nhiệt độ L298N, thời lượng xung và quãng dừng trước khi chạy trên sàn. Mục 5.3 và bảng 5 nêu giới hạn dòng phanh 2 A. [Datasheet L298](https://www.st.com/resource/en/datasheet/l298.pdf)

## Build và kiểm thử

[Giao thức hiện tại](docs/PROTOCOL.md) và [báo cáo kiểm chứng trước khi thay cảm biến](docs/VERIFICATION.md).

VS Code: mở thư mục gốc `xe_dieu_khien` để nhận cấu hình trong `.vscode`.
File `.ino` được nhận dạng là C++, dùng AVR GCC và thư viện Arduino Uno thật;
không thêm `tests/arduino` vào đường dẫn thư viện của firmware vì đó là API giả cho host test.
Cấu hình hiện dùng AVR core 1.8.8 và AVR GCC 7.3.0-atmel3.6.1-arduino7 trong
`%LOCALAPPDATA%/Arduino15`; nếu đổi phiên bản cài đặt, cập nhật đường dẫn trong
`.vscode/c_cpp_properties.json`. Nếu còn báo `undefined` sau khi mở lại file,
chạy `C/C++: Reset IntelliSense Database` rồi `Developer: Reload Window` từ Command Palette.

Host test chạy chính sketch với GPIO/clock giả, không thay thế kiểm thử xe:
~~~powershell
New-Item -ItemType Directory -Force output
g++ -std=c++11 -Wall -Wextra -pedantic -I tests/arduino tests/arduino/watchdog_test.cpp -o output/arduino-pwm-toggle-test.exe
.\output\arduino-pwm-toggle-test.exe
~~~

Chạy Arduino CLI từ thư mục gốc của sketch.
~~~powershell
arduino-cli lib install VL53L0X
arduino-cli compile --fqbn arduino:avr:uno .
~~~

Nếu AVR GCC báo thiếu `VL53L0X.h` dù CLI đã tìm thấy thư viện trong đường dẫn có dấu,
hãy dùng thư mục thư viện chỉ có ký tự ASCII. Có thể chọn lại Sketchbook location trong Arduino IDE
hoặc chỉ định thư viện cho lần build bằng `arduino-cli compile --fqbn arduino:avr:uno --library <đường-dẫn-thư-viện> .`.

Chưa tự nạp UNO hoặc cài lên điện thoại thật. Không commit/push.
