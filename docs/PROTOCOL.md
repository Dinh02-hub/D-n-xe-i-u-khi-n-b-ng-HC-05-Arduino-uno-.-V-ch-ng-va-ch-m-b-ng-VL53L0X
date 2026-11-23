# Giao thức Bluetooth và hành vi dừng

SPP UUID 00001101-0000-1000-8000-00805F9B34FB. HC-05 UART 38400 baud, RX D4 / TX D5.
Pin L298N: ENA D3, ENB D6, IN1..IN4 D8..D11. VL53L0X dùng I²C với SDA A4 và SCL A5.
Giữ INVERT_LEFT=false, INVERT_RIGHT=true; tháo jumper ENA/ENB trước khi nối PWM.

## Android → Arduino

| Lệnh | Ý nghĩa | Phạm vi | Mặc định khi reset UNO |
|---|---|---|---|
| F / B / L / R | Tiến/lùi/trái/phải, một byte | Không thay đổi giao thức cũ | Dừng |
| S | STOP một byte | Ưu tiên cả trong khung cấu hình dở | — |
| A:n + LF | Bật/tắt Chống va chạm | 0 hoặc 1 | 1 |
| V:n + LF | Công suất lái thủ công | 0..100% | 60 |
| D:n + LF | Khoảng cách phanh | 10..150 cm, với D + Z ≤ 200 cm | 40 |
| I:n + LF | Khoảng chờ giữa các lượt đọc kết quả VL53L0X của UNO | 40..200 ms | 60 |
| Z:n + LF | Độ rộng vùng giảm tốc; 0 để tắt giảm tốc | 0..190 cm, với D + Z ≤ 200 cm | 60 |
| E:n + LF | Thời gian phanh điện | 1..60 ms | 30 |

R là rẽ phải. T/P/U là các khung cũ bị bỏ qua.
Đầu ra motor trước INVERT giữ nguyên: F=(+1,+1), B=(-1,-1), L=(+1,-1), R=(-1,+1).
Bảng motor dự kiến trong Android đã được đồng bộ với các giá trị này; byte gửi không thay đổi.

LF kết thúc lệnh; chấp nhận CRLF. Chỉ khung đủ, đúng ký tự số và đúng phạm vi mới áp dụng.
Bộ đệm 5 ký tự, không cần NUL, không dùng String. A chỉ nhận đúng A:0 hoặc A:1.
Khung quá dài, âm, chứa chữ hoặc ngoài phạm vi bị bỏ. F/B/L/R bên trong khung lỗi không biến thành lệnh lái.
Khung dở quá 100 ms bị bỏ. S dừng ngay và đánh dấu khung hiện tại không hợp lệ tới LF hoặc timeout.
Mỗi vòng lặp đọc Bluetooth trước và sau lượt I²C, tối đa 32 byte mỗi lượt. STOP được xử lý ngay khi đọc được, trước khi áp dụng hướng hoặc chuyển pha.

A:0 tắt giảm tốc và bảo vệ theo ngưỡng, đồng thời tiếp tục báo số đo. D/Z được kiểm tra cùng giá trị hiện tại của trường còn lại. Khi cần tăng một giá trị vượt tổng 200 cm, hãy giảm và xác nhận giá trị còn lại trước.
V:0 luôn dừng và hủy mọi hướng/hành động đang chờ; tăng V lại không tự chạy.
D/I/Z/E chỉ lưu trong RAM UNO.

Chỉ F/B/L/R/S gia hạn watchdog 500 ms. Mất Bluetooth không có chân báo riêng: UNO dừng khi hết heartbeat.
STOP và V:0 thắng mọi lệnh hướng trong cùng loop; cấu hình không gia hạn watchdog.
Thứ tự ưu tiên là STOP, xung phanh đang chạy, bảo vệ vật cản, rồi tới lái thủ công.
Trong xung phanh, F/B/L/R chỉ gia hạn watchdog. Hướng nhận trong thời gian này bị bỏ qua.

## Phanh điện, thả trôi và đổi chiều

stopMotion()/drive(0,0) giữ hành vi coast: ENA/ENB=LOW. Đây không phải phanh điện.
Khi đang tiến và có mẫu vật cản hợp lệ ≤D (A=1), firmware lập tức đặt bốn IN LOW,
ENA/ENB HIGH (analogWrite 255) để fast motor stop. Việc này xảy ra trong xử lý mẫu cảm biến,
trước telemetry và không chờ BT hết dữ liệu.

Khi đang tiến với A=1 và Z>0, giảm PWM từ mức yêu cầu tại D+Z xuống gần 30% của mức đó ngay trên D. Công thức là `PWM yêu cầu × (0,3 + 0,7 × (khoảng cách − D)/Z)` trong vùng giảm tốc. Từ D+Z trở lên dùng toàn bộ PWM yêu cầu. Mẫu khoảng cách mới cập nhật PWM ngay cả khi không có lệnh Bluetooth mới. Lùi, rẽ và giá trị công suất đã xác nhận trên Android giữ hành vi cũ.

Xung phanh giữ thời lượng E có hiệu lực lúc bắt đầu, mặc định 30 ms, rồi ngắt EN để xe dừng. Đọc I²C và gửi telemetry tạm nhường xung phanh.
STOP đang trong xung phanh giữ phần xung còn lại và không đặt lại timer.
Không có heartbeat thì dừng theo mốc 500 ms từ lệnh lái cuối.
Đảo hướng thủ công cũng ngắt EN và chờ 100 ms; heartbeat không khởi động lại khoảng chờ.
Đổi IN luôn thực hiện khi EN đang LOW.

Sau phanh vật cản, F đang giữ bị khóa tới khi nhận S. Ứng dụng gửi S khi nhả hết các nút lái hoặc nhấn STOP.
Khi vật cản còn trong ngưỡng, F tiếp tục bị chặn dù đã nhấn lại. Heartbeat gửi mỗi 175 ms.

Hysteresis: chặn ở ≤D, hết trạng thái vật cản ở ≥D+5 cm; vùng giữa giữ trạng thái trước.
Lỗi I²C, trạng thái đo lỗi, số đo ngoài 20..2000 mm (gồm 8190 mm), hoặc thiếu mẫu mới sau I + 50 ms được báo
DIST:-1, OBSTACLE:0, STATE:SENSOR_FAULT. Vùng trễ dùng lại trạng thái trước đó khi có mẫu hợp lệ.
Lỗi đo hoặc lỗi khởi tạo vẫn cho phép điều khiển thủ công và giữ nguyên ưu tiên STOP, watchdog và thời gian chờ đảo chiều.
Sau khi có mẫu hợp lệ, bảo vệ hoạt động lại theo cấu hình A.
Android hiện thanh cảnh báo và khoảng cách `--` trong thời gian lỗi.

VL53L0X đo liên tục theo timing budget mặc định của Pololu. UNO đọc ở nhịp I và thử lại mỗi 2 ms nếu chưa có mẫu,
giới hạn thời gian chờ Wire ở 2,5 ms và kiểm tra cờ sẵn sàng trước khi đọc dữ liệu.
Nếu module còn cấu hình, số đo hồi phục ngay khi đọc được mẫu hợp lệ. Khi cần khởi tạo lại, UNO thử tối đa mỗi giây
trong lúc motor dừng và không có hướng đang chờ, để tránh chờ hiệu chuẩn khi xe chạy.

[Datasheet ST L298](https://www.st.com/resource/en/datasheet/l298.pdf), bảng 5 và mục 5.3,
là căn cứ phân biệt phanh/thả trôi; dòng phanh phải không vượt 2 A.
30 ms + 100 ms là giá trị khởi đầu chưa đo thực tế, không chứng minh dòng an toàn hay bánh đã ngừng quay.
Cần đo dòng/nhiệt/quãng dừng với nguồn giới hạn dòng trước khi hiệu chỉnh hoặc cho xe chạy trên sàn.
Xe không có cảm biến phía sau và firmware không đo/giới hạn dòng.

## Arduino → Android

Các dòng cảm biến, CFG và SAFE giữ định dạng cũ. Hai trường cuối của SAFE báo vùng giảm tốc và thời gian phanh điện.

~~~text
DIST:50.0
OBSTACLE:0
STATE:READY
CFG:1,60
SAFE:40,60,60,30
~~~

| Trường | Nội dung |
|---|---|
| DIST:n.n | cm với một chữ số thập phân, ví dụ 167 mm → 16.7; -1 khi không có mẫu hợp lệ |
| OBSTACLE:0/1 | Trạng thái ngưỡng và vùng trễ, bằng 0 khi mẫu lỗi |
| STATE:READY/BLOCKED/SENSOR_FAULT | Sẵn sàng, có vật cản hoặc lỗi số đo |
| CFG:a,v | Bật/tắt Chống va chạm và công suất lái đặt trên màn hình Điều khiển |
| SAFE:d,i,z,e | Khoảng cách phanh (cm), chu kỳ đọc (ms), vùng giảm tốc (cm), thời gian phanh điện (ms) đã xác nhận. Cặp z,e bằng 0,0 đánh dấu firmware trước khi hỗ trợ Z/E. |

Frame được tạo mỗi 250 ms hoặc sau thay đổi cấu hình/trạng thái.
Phát tối đa một byte mỗi 1000 µs; chờ RX yên ít nhất 1500 µs, không phát khi đang đọc khung cấu hình
hoặc đang phanh. Khi có chuyển động/hướng chờ, chỉ phát trong 100 ms sau lệnh lái cuối,
để nhường RX trước heartbeat Android 175 ms. Lúc đứng yên không áp dụng cửa sổ heartbeat.
Đây là giảm xung đột SoftwareSerial, không phải bảo đảm thu/phát đồng thời hay không mất byte.
Bộ đệm 96 byte có kiểm tra kết quả snprintf; không gửi vượt vùng nhớ.
Cấu hình được xác nhận kể cả khi gửi lại cùng giá trị.

Android chỉ nhận dòng hoàn chỉnh kết thúc LF, kiểm tra đầy đủ kiểu/range của cả bốn trường SAFE.
CFG và SAFE độc lập với DIST/OBSTACLE/STATE; không suy đoán giá trị từ giao diện.
Android mới vẫn cho chỉnh D/I với firmware trả SAFE có cặp 0,0. Hai thanh Z/E cần firmware mới. Với firmware chỉ có CFG, các điều khiển lái, công suất và bật/tắt tiếp tục hoạt động.

## Đồng bộ và giao diện Android

- Phiên mới gửi S, T:0, A:1 và V:60. T:0 dùng để tắt tự lùi nếu kết nối firmware cũ. D/I/Z/E lấy từ SAFE của UNO.
- Một writer chung cho tất cả hướng/cấu hình; không trộn byte giữa các frame. STOP chờ không bị hướng mới ghi đè, được ưu tiên trước cấu hình; STOP đến giữa frame theo ngay sau frame ngắn đó.
- D/I/Z/E được gộp theo từng trường; V giữ giá trị mới nhất. Không có hàng đợi kéo slider kéo dài.
- Màn hình Cài đặt dùng kết nối do ControlActivity đang sở hữu, qua tham chiếu yếu tới phiên đang hoạt động; không mở socket thứ hai hoặc giữ Activity bằng singleton.
- D/I/Z/E chỉ gửi khi thả. Bàn phím và trợ năng gửi sau 250 ms. Sau 2 giây thiếu phản hồi khớp, ứng dụng báo chưa xác nhận. Hai thanh D/Z giới hạn theo giá trị đã xác nhận của nhau.
- Khi thiếu SAFE hoặc quá 1 giây: hiển thị —, khóa bốn slider. Mất kết nối: xóa dữ liệu đang dùng, không báo thành công.
- V vẫn ở màn hình điều khiển, gửi tối đa mỗi 120 ms. Đồng hồ bán nguyệt 180° ôm số phần trăm;
  khi kéo, cung và số cùng dùng một giá trị animation 180 ms. Nhãn “Xem trước · Arduino n%” phân biệt
  yêu cầu với giá trị đã ACK. Không có ACK khớp sau 2 giây: báo chưa xác nhận và trở về giá trị Arduino.
  Thiếu CFG thì hiện —% và không vẽ chỉ báo. Đồng hồ không phải tốc độ bánh đo được.
- Giữ công tắc A, hành vi nhấn/nhả nhiều nút, STOP và heartbeat. Rời màn hình điều khiển luôn gửi STOP.
- Mặc định Light nếu chưa từng chọn theme; Light/Dark/System được lưu. Giao diện đơn sắc trắng/đen/xám;
  màu đỏ dành cho STOP/cảnh báo. System dùng đúng palette Light hoặc Dark theo thiết bị, không có màu riêng.

SoftwareSerial không thu/phát đồng thời. Kiểm thử mô phỏng không chứng minh không mất byte,
độ trễ đọc VL53L0X dưới tải Bluetooth hay hiệu quả và dòng phanh trên xe thật.
