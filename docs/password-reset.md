# TeaSmart: khôi phục mật khẩu bằng OTP

## Nâng cấp OTP (auth06)

Source đã chuyển sang email → OTP 6 số → mật khẩu mới. Không tự đăng nhập.
Không chạy lại auth05 trên database hiện hữu. auth06_password_reset_otp.sql chỉ thêm
otp_hash VARCHAR(60), otp_attempts INT DEFAULT 0, otp_verified_at DATETIME(6).

Thứ tự triển khai: kiểm thử MySQL33377 → phê duyệt riêng cho main → tạm ngừng yêu cầu
reset mới → backup đầy đủ và kiểm tra file dump → xác minh cột chưa có → áp dụng
đúng auth06 một lần → kiểm tra SHOW CREATE TABLE và số tài khoản → restart backend
với cấu hình SMTP hiện có → frontend → nghiệm thu tài khoản test được phép.
Không tự chạy DDL khi khởi động. Nếu migration chưa có, chưa triển khai backend mới.
Rollback an toàn: PASSWORD_RESET_ENABLED=false và restart; giữ bảng/cột và dữ liệu.
Không rollback sang mã reset-link cũ khi còn grant OTP vì khác giao thức.

## API

- POST /api/auth/forgot-password {email}: 202 generic + challenge ngẫu nhiên 256 bit
  kể cả email không tồn tại/Google-only/inactive/bị giới hạn email. Không trả OTP.
- POST /api/auth/reset-password/verify {challenge,otp}: 200 token ngẫu nhiên 256 bit
  nếu OTP hợp lệ. Sai/hết hạn/5 lần sai/không tồn tại cùng 400 INVALID_OTP và thông báo
  chung để không tiết lộ tài khoản. Không còn nhận token của link cũ.
- POST /api/auth/reset-password {token,password,confirmPassword}: dùng grant đã xác minh,
  còn hạn 5 phút, đúng password snapshot. Thành công tiêu thụ grant, không đăng nhập.

OTP SecureRandom sáu chữ số (bao gồm số 0 đầu), BCrypt cost12, sống 5 phút. Không
lưu rõ/ghi log OTP. Challenge và grant được hash SHA256 vì có entropy 256 bit, không
áp dụng SHA256 đơn thuần cho OTP. OTP đúng xóa otp_hash và thay challenge bằng grant.
Gửi lại sau cooldown 60 giây thay toàn bộ yêu cầu trước, kể cả grant chưa sử dụng.
Mỗi user một dòng; khóa user rồi SELECT FOR UPDATE token chống replay và race trên
MySQL REPEATABLE READ. Nhập sai commit bộ đếm, tối đa5. Dummy BCrypt check giảm
khác biệt thời gian giữa challenge không tồn tại và OTP sai. Không coi đây là cam kết
constant-time toàn bộ mạng/database.

Giới hạn 10 gửi/IP/15phút, 3 gửi/email/15phút, 60 xác minh/IP/15phút, 60 reset/IP/15phút;
limiter IP/email trong bộ nhớ, cooldown và bộ đếm OTP trong DB. Triển khai nhiều
instance cần shared rate limiter. Không tin X-Forwarded-For của client. Queue100,
worker1–2; không phải durable outbox. SMTP lỗi không đổi mật khẩu, khách gửi lại sau
cooldown. Không bật body/SQL-bind/mail-debug logging. Token/OTP/grant chỉ trong bộ
nhớ frontend, không localStorage/sessionStorage/URL. Refresh bắt đầu lại, không gửi mail.

Liên kết cũ bị từ chối: otp_verified_at NULL. /dat-lai-mat-khau bỏ fragment và hướng
khách yêu cầu OTP mới. Không xóa lịch sử hay mật khẩu. Giữ nguyên Google OAuth.
JWT đã phát hành chưa bị thu hồi bởi reset, vẫn theo expiry hiện tại; thay đổi cơ chế
thu hồi phiên nằm ngoài phạm vi này.

## Kiểm thử

PasswordResetMysqlTest chỉ chạy với PAYMENT_TEST_SERVER_URL=jdbc:mysql://127.0.0.1:33377/,
kiểm tra @@port và datadir test. Schema synthetic riêng, không dùng3306, không DROP.
Áp dụng auth05 cho schema test mới rồi auth06; không chạy auth05 trên main.
SMTP socket fixture thật, không phải Gmail. RESET_BROWSER_E2E=1 chạy Chrome có giao diện,
React3001 + Spring random port + MySQL33377. Không mock API.

Chưa nghiệm thu Gmail thật cho OTP; cần tài khoản test được chủ sở hữu cho phép và
migration chính được duyệt. Không xem kết quả fixture là email thật.

---
## Lịch sử triển khai reset-link (trước OTP, không còn là hướng dẫn API hiện tại)

# TeaSmart: khôi phục mật khẩu qua email

## Kích hoạt thủ công

Chức năng mặc định **tắt**, không tự chạy DDL hoặc sửa users. Không restart backend chính trong quá trình triển khai source.
1. Backup database đích và kiểm tra phục hồi trước khi vận hành.
2. Dừng phát hành reset mới nếu đang nâng cấp; áp dụng `database/migrations/auth05_password_reset_tokens.sql` bằng tài khoản migration đúng database. Không chạy lại file nếu bảng đã tồn tại; xác minh bằng SHOW CREATE TABLE. Bảng mới không được map thành JPA Entity nên backend cũ/mới khi feature tắt không đòi hỏi bảng này.
3. Đặt các biến theo `config/password-reset.env.example` trong môi trường tiến trình backend. File mẫu không được tự đọc. Dùng `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM`, `FRONTEND_BASE_URL`. Không lưu secret vào Git, log hay command-line arguments. Không đọc config thanh toán để lấy secret.
4. SMTP hiện có dùng STARTTLS bắt buộc, kiểm tra chứng chỉ, timeout 5 giây. Gmail: smtp.gmail.com:587. Bật xác minh hai bước và tạo App Password cho TeaSmart theo [hướng dẫn Google](https://support.google.com/accounts/answer/185833?hl=en); không dùng mật khẩu đăng nhập Gmail. Tài khoản quản lý bởi tổ chức có thể không hỗ trợ App Password. Có thể dùng SMTP nhà cung cấp khác hỗ trợ STARTTLS.
5. Dùng HTTPS cho FRONTEND_BASE_URL khi public (HTTP chỉ được phép localhost/127.0.0.1). Không lấy URL từ Host/request của khách. Đặt MAIL_ENABLED=true và PASSWORD_RESET_ENABLED=true sau khi kiểm tra đúng schema/SMTP.
6. Kiểm thử với tài khoản được phép trước khi mở cho người dùng thật. Chưa kiểm chứng email tới Gmail thật trong lượt triển khai này.

Rollback: đặt PASSWORD_RESET_ENABLED=false rồi restart backend. Giữ nguyên bảng/history, không DROP và không khôi phục mật khẩu cũ. Frontend báo dịch vụ chưa cấu hình khi API trả 503. Triển khai backend trước frontend khi bật chức năng.

## API

- POST `/api/auth/forgot-password`: `{email}`. 202 và thông báo thống nhất cho tài khoản có/không tồn tại, INACTIVE, Google-only. Chỉ ACTIVE có password_hash mới nhận email. Không tự thêm mật khẩu cho Google-only.
- POST `/api/auth/reset-password/verify`: `{token}`. 200 TOKEN_VALID hoặc 400 INVALID_RESET_TOKEN. Không đặt token trong query string.
- POST `/api/auth/reset-password`: `{token,password,confirmPassword}`. 200 khi cập nhật BCrypt thành công; 400 nếu token hoặc mật khẩu không hợp lệ. Quy tắc khớp register: >=8 ký tự, <=72 byte UTF-8, không trống.
- Response có Cache-Control: no-store. Public recovery không gửi Bearer token cũ.
- Frontend `/quen-mat-khau`, `/dat-lai-mat-khau#token=...`; fragment không được gửi trong HTTP request/referrer. Sau thành công bỏ fragment, không tự đăng nhập.

## Dữ liệu và giới hạn

Bảng password_reset_tokens có một dòng/user: user_id FK, token_hash SHA-256 (unique), password_snapshot (hash BCrypt hiện tại), issued_at/expires_at/used_at UTC. Token 32 byte SecureRandom, Base64URL, sống 15 phút. Token rõ chỉ tồn tại trong bộ nhớ/mail. Phát hành mới thay token cũ; token không còn hợp lệ khi password_hash đã đổi. Redemption và issuance khóa user; redemption đọc token FOR UPDATE để không dùng snapshot cũ dưới MySQL REPEATABLE READ. Password và used_at commit cùng transaction. Không thay trạng thái tài khoản.

Giới hạn trong process: 10 yêu cầu/IP/15 phút; 3/email/15 phút (trả cùng 202), 60 kiểm tra/reset/IP/15 phút. Bảng có cooldown một phút/user, bền qua restart. Map giới hạn tối đa 10.000 keys, queue 100, 1–2 workers. Không tin X-Forwarded-For tự gửi. Multi-instance cần rate limiter chung ở proxy/gateway; bộ đếm IP/email hiện không bền qua restart. Worker bất đồng bộ tránh khác biệt thời gian SMTP tiết lộ email tồn tại; queue không phải durable outbox. Nếu restart/crash, khách có thể cần yêu cầu lại. SMTP lỗi chỉ log mã EMAIL_DELIVERY_FAILED, không exception, token, recipient hoặc password. Không bật request-body/bind-parameter/mail debug logging ở production.

**Giới hạn phiên đăng nhập:** JWT cũ vẫn có thể dùng tới expiry (mặc định 1800 giây); converter hiện chỉ xác minh tài khoản ACTIVE. Không tự sửa cơ chế JWT, Google, cookie hoặc session trong task này. Cần quyết định riêng về auth-version/revocation trước khi yêu cầu reset thu hồi mọi thiết bị ngay lập tức. Không coi reset này là chức năng thu hồi phiên bị đánh cắp.

## Kiểm thử

Backend: `PAYMENT_TEST_SERVER_URL=jdbc:mysql://127.0.0.1:33377/`, PAYMENT_TEST_USERNAME/PASSWORD của instance test; chạy `mvn -Dtest=PasswordResetMysqlTest,PasswordResetSafetyTest,EmailNotificationTest test` tại backend. Test từ chối port khác và datadir không thuộc checkout-demo/data; tạo schema synthetic teasmart_reset_test_<UUID>, giữ lại để kiểm tra, không DROP. Không dùng dữ liệu khách hàng chính. SMTP ServerSocket trên loopback random port, JavaMailSender thật truyền MIME qua SMTP local, không Gmail/network mail.

Chrome: đặt RESET_BROWSER_E2E=1, PLAYWRIGHT_MODULE trỏ package Playwright đã cài, BROWSER_ARTIFACT_DIR là thư mục ảnh; chạy cùng Maven command. Test khởi động React Vite3001 với backend Spring random-port của suite, Chrome có giao diện (`channel:chrome`, `headless:false`), rồi đóng tiến trình test. Cổng3001 phải trống. Không mock API, không trỏ8080 hoặc3306. Cần Chrome và Playwright local.

Frontend: `node --experimental-transform-types --test tests/auth.test.ts tests/google-login.test.ts tests/google-link.test.ts`; `npx tsc --noEmit`; `npm run build` (nên chọn outDir riêng nếu dist đang phục vụ website).

Các ca: email có/không tồn tại, sai định dạng, Google-only/inactive; valid/expired/replaced/used tokens; mật khẩu yếu/không khớp/UTF-8 quá giới hạn; đăng nhập mới PASS/cũ401; concurrent redemption chỉ một200; rate limit; SMTP451 không đổi mật khẩu; UI mobile390/desktop1440, double-click chỉ một POST, không tự đăng nhập, token dùng lại bị chặn.

## File trong thay đổi này

Frontend: src/pages/PasswordRecovery.tsx (mới), src/pages/PasswordRecovery.css (mới), src/pages/Auth.tsx (thay placeholder quên mật khẩu), src/App.tsx (import/route/render), src/types.ts (2 Page values), tests/password-recovery.browser.cjs (mới).

Backend: src/main/java/vn/teasmart/backend/controller/PasswordResetController.java (mới), service/PasswordResetService.java (mới), config/SecurityConfig.java (permit đúng 3 endpoint), src/main/resources/application.properties (2 config reset), database/migrations/auth05_password_reset_tokens.sql (mới), config/password-reset.env.example (mới), docs/password-reset.md (mới), src/test/java/vn/teasmart/backend/auth/PasswordResetMysqlTest.java và PasswordResetSafetyTest.java (mới).

Các thay đổi khác đang có trong Git từ trước không thuộc chức năng này. Không sửa checkout, VNPay, đơn hàng, quản trị, User entity hay JwtService.

## Kết quả nghiệm thu 13/11/2026

- 17 backend tests PASS (5 MySQL/API/Chrome, 2 cấu hình an toàn, 10 mail regression); không skip ở lượt cuối.
- 29 frontend auth/Google regression PASS; TypeScript và Vite production build PASS. Build còn cảnh báo bundle >500kB đã có, không mở rộng refactor.
- Chrome có giao diện: frontend3001 → Spring random-port → MySQL33377; SMTP giả lập qua socket thật. Forgot → email-sent → token reset → đăng nhập mật khẩu mới PASS; mật khẩu cũ401; token dùng lại400; double-click một POST; không page errors. Desktop1440x900, mobile390x844.
- Chrome localhost3000/quen-mat-khau: chỉ xem UI tablet768x1024, không tràn ngang, không page errors, không submit.
- Không restart frontend3000/backend8080, không áp dụng migration hoặc ghi3306, không gửi SMTP thật. Test schema synthetic được giữ để kiểm tra. Chức năng chưa được bật trên backend chính.
- Một lượt Chrome trước bị lỗi ở bước mở UI; ca riêng và lượt đầy đủ cuối đã chạy lại PASS. Test có timeout chờ frontend; không bỏ assertion để lấy PASS.
