# Kiểm chứng firmware, phanh/tự lùi và giao diện — 09/11/2026

> Báo cáo của lần sửa firmware trước. Màu Navy/Slate bên dưới đã được thay bằng giao diện đơn sắc;
> kết quả và APK của lần chỉnh màu xem [MONOCHROME_VERIFICATION.md](MONOCHROME_VERIFICATION.md).

## 1. Nguyên nhân xác định được

Đã đọc sketch, host test, giao thức, transport/controller, Activity, theme và UI test hiện tại trước khi sửa.
Giữ pin, F/B/L/R/S, A/V/D/I/T/P, CFG/SAFE và các khoảng cấu hình; không thêm dependency hay phần cứng.

| Bằng chứng code/test trước sửa | Kết luận |
|---|---|
| Phát hiện vật cản, nhận S sau 1 ms: ENA=0, ENB=0 | STOP đã cắt xung phanh 30 ms. Nhả Tiến, mất focus hoặc rời màn hình Android đều có thể gửi S. |
| T=500, không heartbeat: tự lùi dừng sau 310 ms trong probe | Watchdog 500 ms tính từ lệnh lái cuối, không tính từ lúc bắt đầu lùi. |
| T=500, có heartbeat: hoàn thành đủ 500 ms lùi | Bản cũ không ngẫu nhiên mất trạng thái khi còn heartbeat hợp lệ. |
| Gặp vật cản chỉ gọi phanh nếu motor đang được ra lệnh tiến | STOP đến trước mẫu hợp lệ thì xe đã về coast; cảnh báo khoảng cách không đồng nghĩa đã có phanh điện. |

[Bằng chứng trước sửa](../output/reliability/before-probe.log), [probe tái hiện](../output/reliability/before-probe.cpp),
[bản sao source trước sửa](../output/reliability-before/xe_dieu_khien.ino).

Code không có nhánh đặc biệt tại 70%. Chưa xác minh được nguyên nhân vật lý của mọi lần xe lao tiếp trên 70%:
quãng dừng/quán tính, dòng và điện áp motor, độ trễ/lỗi Echo hoặc mất byte Bluetooth vẫn là các giả thuyết cần đo.
100 ms coast chắc chắn không tạo phanh điện, nhưng chưa có căn cứ để bỏ khoảng chuyển chiều hoặc kéo dài phanh.

SoftwareSerial đang cài dùng cli() khi phát, RX xử lý bit trong ISR. Một byte 8N1 ở 38400 baud mất khoảng 260 µs.
[Arduino xác nhận không thu/phát đồng thời](https://docs.arduino.cc/learn/built-in-libraries/software-serial).
Do đó burst RX có thể làm trễ việc đọc Echo và TX có thể gây mất lệnh nhận; host test không chứng minh mất byte thật.

## 2. Logic và file đã sửa

**Firmware và host test — xe_dieu_khien.ino; tests/arduino/watchdog_test.cpp**

- STOP/V=0/tắt hỗ trợ/watchdog xóa ý định tự lùi; nếu xung phanh đã bắt đầu thì hoàn thành phần còn lại
  của 30 ms. STOP lặp lại không đặt lại timer; sau đó chỉ dừng, không lùi.
- Giữ BRAKE_MS=30 và DIRECTION_SETTLE_MS=100, không tự tăng P hoặc đảo chiều mạnh hơn.
  Ngoài pha phanh đang có, STOP vẫn ngắt EN để coast như phiên bản trước.
- Không miễn watchdog cho tự động. Tối đa 30+100+500=630 ms nên T=500 cần tiếp tục heartbeat.
  F bị khóa sau vật cản vẫn được nhận để xác nhận liveness, nhưng không làm xe tự tiến lại.
- Mỗi loop kiểm tra Echo/timer trước hai lượt đọc Bluetooth, mỗi lượt tối đa 16 byte.
  Khi có Echo hợp lệ trong ngưỡng, đường gọi sampleFront → applyFrontSample → updateObstacle → brakeForObstacle
  đặt IN cùng LOW và EN HIGH ngay, trước telemetry, không chờ rỗng RX.
- Không phát telemetry trong pha phanh, lúc đo Echo hoặc đang đọc cấu hình.
  Chờ RX yên 1500 µs, cách ít nhất 1000 µs giữa các byte phát; lúc có chuyển động/hướng chờ chỉ phát
  trong 100 ms sau lệnh lái, nhường cửa sổ trước heartbeat Android 175 ms.
  Đây là giảm xung đột, không phải bảo đảm truyền không mất byte.
- Bỏ sensorSampled, commandBudget toàn cục và readingConfig; dùng timestamp khởi tạo, budget cục bộ,
  configLength>0. Thay SensorState bằng bit frontBlocked và sentinel distanceTenths=-1.
  Giữ EchoStage/MotionStage vì các pha đo, phanh, coast và đổi chiều có hành vi GPIO khác nhau.

**Android**

- SpeedArcView.java; ControlActivity.java; activity_control.xml:
  cung tròn 180° ôm số phần trăm, cùng một ValueAnimator cập nhật cung/số; slider giữ ở dưới.
  Khi kéo hiện xem trước và nhãn “Xem trước · Arduino n%”; chưa ACK không báo thành công.
  Quá hạn yêu cầu thì trả về giá trị đã ACK; dữ liệu cũ hiện —%.
- BaseActivity.java; AppSettings.java; SettingsActivity.java:
  chọn palette theo Light/Dark/System; đổi Light ↔ System vẫn cập nhật khi uiMode không đổi.
  Activity đang nằm dưới màn hình Cài đặt cập nhật lại palette khi quay về.
- attrs.xml, themes.xml, system_colors.xml ở values/values-night, colors.xml ở values-night,
  các layout/drawable/selector và view vẽ tay:
  dùng màu semantic theo theme. Tint icon đặt trực tiếp bằng app:tint để không giữ màu từ drawable cache.
  Không thay bố cục ngoài đồng hồ hoặc vẽ lại hình xe.
- ControlUiTest.java; SettingsUiTest.java:
  kiểm tra preview/ACK/timeout, số nằm trong bán nguyệt, bốn palette,
  tương phản chữ/nền, tint icon và đổi palette khi thiết bị vẫn sáng.
- README.md; docs/PROTOCOL.md; báo cáo này. [Danh sách file đầy đủ](../output/reliability/changed-files.txt).

Theme:

| Chọn | Nền | Accent |
|---|---|---|
| Light — mặc định | #F5F5F7 | #183153 |
| Dark cố định | #1B1D22 | #A9C9F0 |
| System khi thiết bị sáng | #EAF0F6 | #244A70 |
| System khi thiết bị tối | #11253B | #8FC5F4 |

## 3. Số dòng và biến trước/sau

| Chỉ số | Trước | Sau |
|---|---:|---:|
| Tổng dòng sketch | 436 | 442 |
| Dòng không trống | 388 | 394 |
| Biến/đối tượng toàn cục khả biến | 33 | 32 |
| Hằng toàn cục khai báo const | 19 | 23 |
| Tổng hai nhóm trên | 52 | 55 |
| Kiểu enum | 3 | 2 |
| RAM tĩnh từ compiler Uno | 561 byte | 565 byte |
| Flash | 8408 byte | 8674 byte |

Đếm mỗi tên khai báo một lần, kể cả BT/mảng; các phần tử enum không tính là biến.
[Chi tiết phép đếm](../output/reliability/metrics.json).
Dòng tăng 6 để bổ sung bảo vệ xung phanh và lịch RX/TX. Không giảm độ tin cậy để giảm số dòng;
đây không phải tối ưu dung lượng bộ nhớ.

## 4. Kiểm thử và artifact

| Kiểm tra | Kết quả |
|---|---|
| Host GCC trước sửa | PASS |
| Host GCC sau sửa, -std=c++11 -Wall -Wextra -pedantic | PASS |
| Arduino CLI 1.5.1, arduino:avr:uno / core 1.8.8 | PASS; 8674/32256 byte flash, 565/2048 byte RAM tĩnh |
| Android assembleDebug / JDK 21 / SDK 35 / Gradle 8.11.1 | PASS |
| testDebugUnitTest | PASS: 19 test, 0 lỗi |
| connectedDebugAndroidTest / emulator Android 15 API 35 | PASS: 36 test, 0 lỗi, 0 bỏ qua |
| lintDebug | PASS: 0 lỗi, 12 cảnh báo hiện có |
| Chụp ảnh và kiểm tra trực quan | PASS riêng 2 test; đã xem Light, Dark, System sáng/tối và icon sau sửa tint |

Host test có các hồi quy STOP ở 1 ms và STOP lặp trong pha phanh, F không hủy/khởi động lại phanh,
link gần hết hạn không tự được gia hạn bởi cảm biến, T=500 có/không heartbeat,
RX/TX quiet/pacing/window, giữ STOP ở Echo, các biên D/I/T/P/V/A, mất Echo, khóa F và tràn clock.
UI test có preview 100%, timeout quay về ACK cũ, hết hạn dữ liệu theo tick 175 ms,
bố cục và tương phản cả bốn palette, chọn System cùng uiMode, tint icon, giữ/nhả và Bluetooth giả.

Lệnh chính từ thư mục gốc:

~~~powershell
g++ -std=c++11 -Wall -Wextra -pedantic -I tests/arduino tests/arduino/watchdog_test.cpp -o output/reliability/firmware-test.exe
.\output\reliability\firmware-test.exe
New-Item -ItemType Directory -Force output/reliability/xe_dieu_khien
Copy-Item xe_dieu_khien.ino output/reliability/xe_dieu_khien/
& "$env:LOCALAPPDATA/Programs/Arduino IDE/resources/app/lib/backend/resources/arduino-cli.exe" compile --fqbn arduino:avr:uno --build-path output/reliability/uno-build output/reliability/xe_dieu_khien
~~~

Trong android:

~~~powershell
$env:JAVA_HOME = 'C:/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot'
& "$env:LOCALAPPDATA/ArduinoDriveBuild/gradle-8.11.1/bin/gradle.bat" --no-daemon assembleDebug testDebugUnitTest lintDebug connectedDebugAndroidTest
~~~

- [APK debug](../android/app/build/outputs/apk/debug/app-debug.apk), [firmware hex](../output/reliability/uno-build/xe_dieu_khien.ino.hex).
- [Log Android cuối](../output/reliability/android-tests-final.log), [Arduino](../output/reliability/arduino-build.log), [host](../output/reliability/host-test.log), [hash artifact](../output/reliability/hashes.txt).
- [Unit report](../android/app/build/reports/tests/testDebugUnitTest/index.html), [UI report](../android/app/build/reports/androidTests/connected/debug/index.html), [lint](../android/app/build/reports/lint-results-debug.html).
- [Light](../output/reliability/screenshots/files/sensor-light-READY.png), [Dark](../output/reliability/screenshots/files/sensor-dark-READY.png),
  [System sáng](../output/reliability/screenshots/files/sensor-system-light-READY.png), [System tối](../output/reliability/screenshots/files/sensor-system-dark-READY.png).
- Ảnh/test dùng dữ liệu giả, không phải dữ liệu từ xe. Không commit/push; không sửa Gradle/dependency/pin.

## 5. Cần kiểm chứng trên xe thật

Chưa upload hoặc chạy xe thật; không kết luận đã sửa hoàn toàn khả năng dừng ở 100%.

1. Kiểm tra ENA/ENB thực sự nối D3/D6, đã tháo jumper; IN/motor đúng mapping hiện tại.
2. Với D/I/T/P đã được ACK, ghi lại lần nhả/STOP và khoảng cách quan sát trước khi kết luận tự lùi thất thường.
   Nhả Tiến gửi S nên tự lùi bị hủy có chủ đích; T=0 hoặc P=0 cũng không lùi.
3. Đo khoảng từ phát hiện đến EN phanh, dòng/nhiệt và quãng dừng ở từng mức V; đo tiếp trong 100 ms coast.
   [Datasheet L298](https://www.st.com/resource/en/datasheet/l298.pdf) yêu cầu dòng phanh không vượt 2 A.
   Xung ngắn không chứng minh đỉnh dòng an toàn hoặc motor đã ngừng quay.
4. Chọn D sớm hơn theo quãng đi trong thời gian chờ đo + quãng dừng thực tế + khoảng dự phòng.
   I=60 có thể chờ gần 60 ms trước lần kích kế tiếp; Echo 40 cm khoảng 2,32 ms, chưa kể ISR/loop.
   I=200 tăng phần chờ này. Không tự tăng P hoặc bỏ khoảng chuyển chiều để bù phát hiện muộn.
5. Thử STOP/mất sóng khi đang phanh, coast, lùi; đo Echo khi Bluetooth bận.
   SoftwareSerial vẫn có thể mất byte hoặc làm trễ polling. Chưa xác minh sụt nguồn, torque ở P=30,
   độ bám bánh hoặc nguyên nhân cơ khí của hiện tượng trên 70%.

Đổi theme có thể tái tạo Activity theo vòng đời hiện tại; nếu phiên Bluetooth bị đóng thì cần chờ kết nối/ACK lại.
