# TeaSmart Backend — Bước 05.1

Maven project độc lập: Java 17, Spring Boot 4.1.1, Maven Wrapper 3.9.16.
Frontend vẫn nằm ở thư mục cha, giữ nguyên toàn bộ.

## IntelliJ IDEA

Open `backend/pom.xml` as Project, chọn Project SDK JDK 17 và Maven Wrapper.
Run class `vn.teasmart.backend.TeaSmartApplication`.
Trong Run Configuration, khai báo các biến môi trường sau bằng thông tin thật của bạn:

| Biến | Nội dung |
| --- | --- |
| DB_URL | JDBC URL của database MySQL đã có, ví dụ `jdbc:mysql://localhost:3306/<database_name>` |
| DB_USERNAME | Tài khoản MySQL được cấp quyền cho database đó |
| DB_PASSWORD | Mật khẩu tài khoản MySQL |
| SERVER_PORT | Tùy chọn, mặc định 8080 |

Không đặt credentials vào source, Git hoặc Run Configuration được chia sẻ.
Không thêm `createDatabaseIfNotExist=true`. Backend không tạo database/schema/data.
Spring Boot không tự đọc file `.env`; đặt biến trong môi trường chạy hoặc IntelliJ.

## Build và chạy (PowerShell, tại backend/)

```powershell
.\mvnw.cmd --version
.\mvnw.cmd clean package
# Sau khi đã khai báo DB_URL, DB_USERNAME, DB_PASSWORD trong môi trường:
java -jar .\target\teasmart-backend-0.0.1-SNAPSHOT.jar
```

Build không cần MySQL. Bước này chưa có test nghiệp vụ hoặc context test cần database.
Runtime cần MySQL thật và cả ba biến DB_*; thiếu biến hoặc database không sẵn sàng sẽ lỗi.

## Giới hạn Bước 05.1

Chỉ có application entrypoint và cấu hình. Chưa có Entity, Repository, Service,
Controller, API, JWT, Auth hay AI. Không dùng Lombok vì chưa có class cần boilerplate.
Spring Security được giữ với cấu hình tự động mặc định của framework; không có
luồng đăng nhập TeaSmart hoặc tài khoản nghiệp vụ được triển khai ở bước này.

`ddl-auto=validate`, `generate-ddl=false`, `sql.init.mode=never` ngăn tự tạo/sửa schema
và chạy script SQL. Chưa có Entity nên Hibernate chưa thể đối chiếu 17 bảng/21
relationships. Tài liệu ERD/Data Dictionary Final Backend v1.0 và Blueprint FROZEN
chưa nằm trong repository khi khởi tạo; không suy diễn cấu trúc từ dữ liệu frontend.
Thiết kế giữ nguyên wishlists, payments.payment_status PENDING/PAID/FAILED,
không REFUNDED; hủy đơn thuộc orders.order_status. Chưa tạo enum hoặc bảng nào.

Dừng ở 05.1, chỉ tạo mapping nghiệp vụ sau khi được xác nhận bước tiếp theo.
