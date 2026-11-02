# ChatRoom TCP Socket

Ứng dụng chat desktop bằng Java Swing, sử dụng TCP và SQLite. Có chat theo
phòng, tin nhắn riêng, lịch sử, bạn bè, sửa/thu hồi tin nhắn, reaction,
điều phối cuộc gọi và truyền file theo chunk.

## Yêu cầu

- JDK 23 với `java` và `javac` trong PATH (phiên bản đã kiểm tra: 23.0.1).
- Windows và PowerShell để dùng script đi kèm.
- SQLite JDBC đã có trong `lib/`; không cần tải thư viện khi build.

## Chạy nhanh

Mở PowerShell tại thư mục chứa README này:

```powershell
.\dev.ps1 build
.\dev.ps1 server
```

Trong cửa sổ server, nhấn nút khởi động. Mở PowerShell thứ hai:

```powershell
.\dev.ps1 client
```

Đăng ký tài khoản từ màn hình client, sau đó đăng nhập. Mặc định server dùng
cổng `5000`; client trên cùng máy dùng địa chỉ `127.0.0.1`.

**Đăng nhập luôn yêu cầu mật khẩu.** Server không còn tự tạo tài khoản khi
LOGIN và không cho phép bỏ qua mật khẩu bằng lời gọi `connect(host, port, username)`.
Đăng ký qua `REGISTER` hoặc giao diện đăng ký trước khi đăng nhập.
Mật khẩu được giữ nguyên, bao gồm khoảng trắng đầu/cuối.

Nếu PowerShell chặn script do chính sách trên máy, có thể chạy từng lệnh qua:

```powershell
powershell -ExecutionPolicy Bypass -File .\dev.ps1 test
```

## Kiểm thử

```powershell
.\dev.ps1 test
```

Script biên dịch toàn bộ mã và chạy tám bộ kiểm thử tuần tự. Mỗi bộ dùng một
cổng tạm và một cơ sở dữ liệu riêng trong `build/`, không ghi vào lịch sử chat
thật trong `data/messenger.db`. Nếu có bộ thất bại, script dừng và báo lỗi.

- Xác thực: thiếu/sai mật khẩu, tài khoản chưa tồn tại, chữ hoa/thường,
  mật khẩu có khoảng trắng, tám kết nối tranh cùng tài khoản.
- Vòng đời: ngắt client đang chờ đọc, đóng kết nối chưa xác thực khi dừng server,
  từ chối phản hồi đăng nhập không hợp lệ và giải phóng socket.
- Giao diện Windows: chữ trên nút thường, bị vô hiệu hóa, đang nhấn và nút gốc
  của Windows không bị lớp giao diện che; kiểm tra độ tương phản chữ/nền.
- Kết bạn: trạng thái lời mời gửi/nhận, chống trùng và gửi chéo, chấp nhận đúng
  chiều, từ chối, hủy bạn, nhận lời mời sau khi online lại và tên có dấu phân cách.
- Các bộ Phase 1–10: giao thức, heartbeat, bạn bè, tin nhắn nâng cao,
  điều phối media, truyền file, cách ly phòng và tải đồng thời.

Các bài tự động không thay thế việc thử microphone, camera và chất lượng
cuộc gọi giữa hai máy thực tế.

Để kiểm tra giao diện thực tế bằng hai tài khoản mẫu trên dữ liệu riêng:

```powershell
java -Dchat.port=51424 -Dchat.db.path=build/manual-qa.db -cp "build/classes;lib/*" test.DesktopQaLauncher
```

Chỉ dùng cửa sổ có tiền tố `QA` để thử thao tác. Luồng kết bạn cập nhật sau
phản hồi server, không báo thành công trước khi được xác nhận. Khi cập nhật
phiên bản này, khởi động lại cả server và client để nạp giao thức kết bạn mới.

## Cấu trúc

| Thư mục | Vai trò |
| --- | --- |
| `client/ui` | Giao diện Swing |
| `client/ChatClient.java` | Đăng ký, kết nối, gửi lệnh và heartbeat |
| `client/ServerListener.java` | Nhận dữ liệu và phát sự kiện cho giao diện |
| `client/media`, `client/file` | Cuộc gọi và truyền file |
| `server/ChatServer.java` | Listener, virtual threads, watchdog và dừng server |
| `server/ClientHandler.java` | Xác thực và xử lý lệnh của từng kết nối |
| `server/ClientManager.java`, `server/RoomManager.java` | Phiên trực tuyến và phòng |
| `server/db` | SQLite, PBKDF2 và bộ nhớ dự phòng |
| `shared/protocol` | Packet, escape ký tự và tương thích giao thức cũ |
| `test` | Kiểm thử tích hợp chạy bằng Java |

## Cấu hình và dữ liệu

Sau khi build, có thể truyền cấu hình khi chạy Java trực tiếp:

```powershell
java -Dchat.port=6000 -Dchat.db.path=data/custom.db -cp "build/classes;lib/*" server.ChatServer
```

Nhập cùng cổng đó ở client. `chat.port` mặc định là `5000`; `chat.db.path`
mặc định là `data/messenger.db`. Các thuộc tính được đọc khi nạp lớp.
File nhận về nằm trong `downloads/`. Không đưa dữ liệu tài khoản hay file
người dùng vào Git. Khi sao lưu SQLite đang chạy với WAL, cần xử lý cả file
WAL hoặc dừng server trước khi sao chép cơ sở dữ liệu.

Server chờ tối đa 15 giây cho mỗi lần đọc trong giai đoạn xác thực và 60 giây
cho mỗi lần đọc sau đăng nhập; client có heartbeat mỗi 15 giây.
Client giới hạn thời gian kết nối và chờ phản hồi đăng nhập ở 10 giây.
Khi dừng, server đóng cả kết nối chưa xác thực và chờ tối đa 5 giây cho
các handler dọn trạng thái trước khi trả về.

## Hướng phát triển tiếp

Đợt cải thiện này tập trung vào xác thực, vòng đời kết nối và khả năng kiểm thử.
Trước khi triển khai trên Internet cần hoàn thiện thêm:

- TLS: TCP hiện truyền nội dung và mật khẩu chưa mã hóa trên đường truyền.
- Rà soát quyền truy cập lịch sử, reaction, file và thao tác nhóm theo từng lệnh.
- Giới hạn kích thước frame, tốc độ gửi và số kết nối để bảo vệ tài nguyên.
- Thay metadata phân cách bằng chuỗi (`::`, `;;`, dấu phẩy) bằng định dạng có
  cấu trúc và phiên bản giao thức để tránh xung đột với nội dung người dùng.
- Chế độ triển khai bắt buộc SQLite: hiện khi khởi tạo database lỗi, ứng dụng
  chuyển sang bộ nhớ dự phòng; dữ liệu đó không tồn tại sau khi thoát.
