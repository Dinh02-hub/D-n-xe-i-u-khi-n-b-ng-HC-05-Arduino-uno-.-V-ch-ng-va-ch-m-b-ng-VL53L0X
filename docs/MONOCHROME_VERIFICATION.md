# Kiểm chứng màu đơn sắc — 09/11/2026

Đổi màu giao diện sang trắng/đen/xám; giữ đỏ cho STOP và cảnh báo.
Giữ theme attributes hiện có. System kế thừa palette chung, tự chọn Light/Dark theo thiết bị;
không còn bảng màu Navy/Slate hoặc color resource riêng cho System. Lần mở đầu vẫn Light.

| Thành phần | Light | Dark |
|---|---|---|
| Nền | #F5F5F5 | #1C1C1C |
| Card/nút | #FFFFFF | #282828 |
| Chữ/icon | #1D1D1D | #F5F5F5 |
| Slider/cung/đang chọn | #242424 | #ECECEC |
| Chữ phụ | #6B6B6B | #B8B8B8 |
| Viền/cung còn lại | #D4D4D4 | #454545 |

System sáng dùng cột Light, System tối dùng cột Dark, bao gồm cả màu cảnh báo.

## Thay đổi

- values/colors.xml; values-night/colors.xml: thay các tông Navy/xanh bằng màu trung tính.
- values/themes.xml: Theme.ArduinoDrive.System chỉ kế thừa theme chung, không override màu.
- Xóa values/system_colors.xml và values-night/system_colors.xml.
- drawable/ic_app.xml: logo cũng dùng màu đơn sắc từ resources, giữ nguyên hình dạng.
- ControlUiTest.java; SettingsUiTest.java: cập nhật kỳ vọng màu, kiểm tra 16 màu System trùng palette chung.
- README.md và docs/PROTOCOL.md: cập nhật mô tả theme; docs/VERIFICATION.md dẫn sang báo cáo này.

Đã so sánh SHA-256 trước/sau: firmware, toàn bộ Java của ứng dụng (kể cả SpeedArcView), layout,
manifest và Gradle đều không đổi. Bố cục, animation, điều khiển và Bluetooth giữ nguyên.
Không thêm thư viện, không commit/push. Bản sao trước sửa nằm tại output/monochrome-before/.

## Kiểm tra thực tế

| Kiểm tra | Kết quả |
|---|---|
| assembleDebug | PASS |
| testDebugUnitTest | 19 test, 0 lỗi |
| connectedDebugAndroidTest, emulator API 35 / Android 15 | 36 test, 0 lỗi |
| lintDebug | 0 lỗi, 12 cảnh báo hiện có |
| Kiểm tra các nguồn được bảo toàn bằng hash | PASS |
| Chụp ảnh riêng và kiểm tra trực quan | 2 test PASS; đã xem Light/Dark/System sáng/tối |

UI test bao gồm Light, Dark, System sáng/tối, lựa chọn mặc định Light khi thiết bị tối,
độ tương phản chữ/nền, tint icon và số nằm trong đồng hồ bán nguyệt.
Đã giữ các test nhấn/nhả, preview/ACK, STOP và writer Bluetooth trong cùng bộ kiểm tra.

Lệnh dùng trong android với JDK 21 đã cài:

~~~powershell
$env:JAVA_HOME = 'C:/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot'
& "$env:LOCALAPPDATA/ArduinoDriveBuild/gradle-8.11.1/bin/gradle.bat" --no-daemon assembleDebug testDebugUnitTest lintDebug connectedDebugAndroidTest
~~~

- [APK debug](../android/app/build/outputs/apk/debug/app-debug.apk), [hash](../output/monochrome/hashes.txt).
- [Build/test log](../output/monochrome/android-tests.log), [log lấy ảnh](../output/monochrome/visual-tests.log).
- [Light](../output/monochrome/screenshots/files/sensor-light-READY.png), [Dark](../output/monochrome/screenshots/files/sensor-dark-READY.png).
- [System sáng](../output/monochrome/screenshots/files/sensor-system-light-READY.png), [System tối](../output/monochrome/screenshots/files/sensor-system-dark-READY.png).
- [Cài đặt sáng](../output/monochrome/screenshots/files/settings-light-0.png), [Cài đặt tối](../output/monochrome/screenshots/files/settings-dark-0.png).

Ảnh dùng dữ liệu kiểm thử giả lập; lần sửa này chỉ đổi màu Android, không kiểm thử lại phần cứng Arduino.
