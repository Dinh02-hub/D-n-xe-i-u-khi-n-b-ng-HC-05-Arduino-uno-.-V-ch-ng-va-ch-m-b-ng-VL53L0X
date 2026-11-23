# Chẩn đoán HC-05 kết nối không ổn định — 08/11/2026

## Kết luận hiện tại

Người dùng xác nhận cùng điện thoại đã kết nối HC-05 bằng Serial Terminal, và Arduino Drive hiện cũng có lần kết nối thành công, có lần thất bại. Terminal đã được Disconnect và đóng.

**Chưa xác định được nguyên nhân thực tế của các lần thất bại.** Không có điện thoại thật kết nối ADB trong phiên chẩn đoán này. Các sửa đổi dưới đây bổ sung chẩn đoán và chặn thao tác trùng, không phải bằng chứng đã sửa xong kết nối phần cứng.

Đã đọc ConnectionActivity, BaseActivity, ControlActivity, BluetoothTransport, CommandTransport, SimulationTransport, AppSettings và SettingsActivity:

| Kiểm tra | Kết quả từ mã nguồn |
|---|---|
| Android 12+ | Manifest có CONNECT/SCAN; xin runtime cả hai trước khi liệt kê/kết nối. SCAN phục vụ cancelDiscovery. |
| Android 6–11 | Có BLUETOOTH/BLUETOOTH_ADMIN với maxSdkVersion=30; không tự quét thiết bị mới nên không xin vị trí. |
| Thiết bị đã ghép đôi | Dùng BluetoothAdapter.getBondedDevices(); chuyển đúng địa chỉ đã chọn sang ControlActivity. |
| UUID | Đúng SPP 00001101-0000-1000-8000-00805F9B34FB. |
| Socket | createRfcommSocketToServiceRecord (secure); cancelDiscovery trước connect; connect/read/write/close chạy nền. |
| IOException/SecurityException | Có bắt và đóng socket; nay ghi rõ bước lỗi, exception và thời gian. |
| Chuyển màn hình | ConnectionActivity không tạo/đóng socket. ControlActivity tạo phiên, onPause gửi dừng, onDestroy đóng phiên. BaseActivity không thao tác Bluetooth. |
| Kết nối lại | Quay lại màn hình chính và chọn lại HC-05 tạo phiên mới; không có vòng tự retry. |
| Mô phỏng | Cài mới mặc định false; giá trị true đã lưu vẫn bỏ qua Bluetooth. Nay có log rõ khi nhánh này được chọn. |

Phát hiện có căn cứ: trước đây thao tác mở màn hình/chọn thiết bị không có chặn trùng; cùng BluetoothTransport cũng cho phép gọi connect nhiều lần, ghi đè socket và tạo thêm worker.
Đã chặn cả hai trường hợp. **Chưa có log điện thoại chứng minh người dùng từng gặp đúng trường hợp này.**
Không thêm insecure fallback, reflection RFCOMM channel 1, đổi UUID hoặc kéo dài timeout theo phỏng đoán.

## File thay đổi trong đợt chẩn đoán này

- android/app/src/main/java/com/arduinodrive/ui/ConnectionActivity.java: log quyền theo phiên bản, chế độ mô phỏng, adapter, thiết bị bonded, lựa chọn; chặn luồng mở/chọn trùng.
- android/app/src/main/java/com/arduinodrive/ui/ControlActivity.java: log mã Activity, khởi tạo, kết quả kết nối, onPause/onDestroy và thay đổi cấu hình.
- android/app/src/main/java/com/arduinodrive/transport/BluetoothTransport.java: chặn connect trùng/phiên đã đóng, log mã phiên, socket, timeout, lỗi đầy đủ, đọc EOF và đóng kết nối; hiển thị bước lỗi trong thông báo hiện có.
- android/app/src/androidTest/java/com/arduinodrive/ui/NavigationTest.java: kiểm thử nhấn mở liên tiếp chỉ tạo một ControlActivity.
- android/app/src/androidTest/java/com/arduinodrive/transport/BluetoothTransportTest.java: mới; phiên đã đóng không được bắt đầu connect muộn hoặc báo gửi thành công.
- docs/BLUETOOTH_DIAGNOSTICS.md: báo cáo và hướng dẫn lấy log.
- android/local.properties (Git ignore): dùng đúng SDK có sẵn trong ArduinoDriveBuild/sdk để build.

Giữ nguyên BaseActivity, manifest, giao diện XML, strings, dependency, firmware và kiến trúc Activity/transport hiện có.

## Chạy thử và lấy log trên điện thoại thật

1. Kê bánh để xe không tự chạy ngoài ý muốn. Bấm Disconnect trong Serial Terminal và đóng app đó.
2. Cài đè APK mới: android/app/build/outputs/apk/debug/app-debug.apk. Nếu dùng ADB: adb install -r android/app/build/outputs/apk/debug/app-debug.apk.
3. Tắt Mô phỏng mặc định trong Cài đặt Arduino Drive. Kiểm tra HC-05 vẫn ghép đôi; cấp quyền Thiết bị ở gần trên Android 12+.
4. Bật Developer options / USB debugging, cắm USB, chấp nhận dấu vân tay RSA trên điện thoại. Chạy adb devices; phải thấy đúng điện thoại ở trạng thái device.
5. Bắt log **trước khi** bấm Điều khiển. Lệnh bên dưới không xóa log hệ thống.
6. Chọn HC-05, chờ kết quả. Thử lại vài lần bằng cách quay lại rồi chọn lại thiết bị; ghi lại lần nào thành công, lần nào thất bại. Sau đó Ctrl+C để lưu log.
7. Gửi hc05-logcat.txt cùng Android version/model và thời điểm lần lỗi. Log có nhãn thiết bị/địa chỉ MAC để đối chiếu đúng module.

PowerShell, nếu adb có trong PATH:

~~~powershell
adb logcat -v threadtime "DriveConnection:V" "DriveControl:V" "DriveBluetooth:V" "AndroidRuntime:E" "*:S" > hc05-logcat.txt
~~~

Nếu máy hiện tại không có adb trong PATH, dùng bản SDK đã cài:

~~~powershell
$taskAdb = "$env:LOCALAPPDATA\ArduinoDriveBuild\sdk\platform-tools\adb.exe"
& $taskAdb devices
& $taskAdb logcat -v threadtime "DriveConnection:V" "DriveControl:V" "DriveBluetooth:V" "AndroidRuntime:E" "*:S" > hc05-logcat.txt
~~~

Nếu có nhiều thiết bị/emulator, thêm -s SERIAL sau adb.
Có thể dùng Android Studio Logcat và lọc ba tag DriveConnection, DriveControl, DriveBluetooth.

## Cách đọc log

| Dấu mốc | Ý nghĩa / bước tiếp |
|---|---|
| simulation_selected | Bluetooth chưa được thử; tắt mô phỏng. |
| permission_result, connect/scan=-1 | Trên Android 12+, kiểm tra quyền Thiết bị ở gần; 0 là đã cấp. Android cũ dùng legacyBluetooth/legacyAdmin. |
| bonded_device_count=0 hoặc không có HC-05 | Kiểm tra danh sách ghép đôi hệ thống và đúng module. |
| device_selected, connect_dispatch | Đã chuyển đúng thiết bị tới ControlActivity. |
| socket_create / socket_created | Tạo socket secure SPP; lỗi tại đây có exception đi kèm. |
| cancel_discovery result=false | Hệ thống không xác nhận hủy discovery; vẫn thử connect như trước, chưa đủ kết luận nguyên nhân. |
| socket_connect begin rồi disconnected stage=socket_connect | Xem IOException và elapsedMs; cần đối chiếu lần thành công/thất bại, chưa tự kết luận lỗi UUID hoặc loại socket. |
| connect_timeout | Hết 12 giây; socket được đóng. Không tự tăng timeout khi chưa đo. |
| socket_connected, streams_ready | Socket đã kết nối và ghi S ban đầu; chưa chứng minh Arduino nhận được. |
| disconnected stage=socket_read/read_eof/socket_write | Đã sang bước truyền/nhận rồi mất link hoặc lỗi I/O. |
| onDestroy changingConfigurations=true + close_requested | Activity bị tạo lại; đối chiếu phiên mới và thời điểm đóng phiên cũ. Không gán nguyên nhân cho ConnectionActivity.onPause. |
| connect_ignored/open_ignored | Chặn thao tác trùng hoặc connect sau khi phiên đã đóng. |
| failure_after_close | Worker kết thúc sau một yêu cầu đóng đã có; xem sự kiện đóng trước đó để tìm nguyên nhân đầu tiên. |

Mỗi session=… là một BluetoothTransport; activity=… là một ControlActivity.
Không ghi heartbeat liên tục vào logcat để tránh nhiễu; nhật ký điều khiển cũ vẫn giữ nguyên.

## Lưu ý firmware đã kiểm tra

Sketch xe_dieu_khien.ino hiện tại là test hai motor chạy/dừng mỗi 2 giây, có delay(2000), không có SoftwareSerial và không đọc lệnh Bluetooth. File này đã khác bản V1 được kiểm tra ở báo cáo trước và được giữ nguyên theo phạm vi chẩn đoán Android.

Điều này không ngăn điện thoại thiết lập SPP với HC-05, nhưng sketch này không thực thi F/B/L/R/S.
UART 38400 hay 9600 phải khớp giữa HC-05 và sketch thực tế để dữ liệu đúng; Android SPP không đặt baud UART.
Chưa kiểm tra hoặc thay đổi cấu hình UART của module trong phiên này.

## Kết quả build và kiểm thử bản chẩn đoán

- assembleDebug: thành công; APK android/app/build/outputs/apk/debug/app-debug.apk, 5.820.438 byte.
- SHA-256: 9a95486dc1d7c5af5823751c9604c46ef515aed3088eddf1770eaf8eaba96a04.
- testDebugUnitTest: 11 test, không lỗi.
- connectedDebugAndroidTest: 12 test trên emulator API 35, không lỗi/không bỏ qua; gồm hai ca mới chặn mở trùng và connect sau close.
- lintDebug: 0 lỗi, 7 cảnh báo hiện có; không thêm baseline hoặc tắt lint.
- git diff --check: đạt. Không commit/push. Emulator tự mở đã được đóng.
- Không kiểm thử kết nối HC-05 thực tế; không xác nhận đã hết tình trạng kết nối chập chờn.

Tài liệu đối chiếu: [quyền Android](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions), [kết nối RFCOMM](https://developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices).
