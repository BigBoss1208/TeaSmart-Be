# TeaSmart demo seed v1

Chỉ hỗ trợ database teasmart_product_test; không có tham số đổi sang database chính. Không migration hoặc UPDATE/DELETE. Scope store chỉ tạo đúng Store đã được duyệt, lookup theo tên và không ghi đè.

Nguồn: yêu cầu người dùng 24/10/2026, giá/khối lượng demo đã xác nhận. Xem MAPPING_CAN_DUYET.md. Store và toàn bộ mapping đã được người dùng phê duyệt ngày 24/10/2026; script vẫn giữ kiểm tra approval cho các lần thay đổi dữ liệu sau.

DB_USERNAME/DB_PASSWORD phải có trong Process. Script truyền password qua MYSQL_PWD cho child process, không command line/log. Chạy từ root project:

```
python backend/database/seeds/teasmart-demo-v1/seed.py --scope catalog --report plan.json
python backend/database/seeds/teasmart-demo-v1/seed.py --scope catalog --apply --report apply.json
python backend/database/seeds/teasmart-demo-v1/seed.py --scope products --report product-plan.json
```

Không đặt approval=true trước khi có phê duyệt. Sau phê duyệt, Store phải tồn tại ACTIVE; cấu hình approvedExistingName theo tên duy nhất, không ID cố định. Script lookup IDs tại database đích. Product apply cũng cần --apply. Luôn dry-run trước. Hàng hiện có được giữ nguyên; không đồng bộ giá hay sửa tên bản ghi cũ. UNIQUE slug/name và transaction bảo vệ chạy lại; xung đột làm lỗi/rollback, không --force. Snapshot catalog trước/sau xác minh các bản ghi cũ giữ nguyên. SQL literal và metadata chuẩn hóa collation tường minh để tương thích MySQL8.

Product: 25 tên chính xác, giá/gram đã được duyệt, tồn kho30 là DEMO. SKU logic nằm trong manifest/description, không có cột SKU ở schema. 25 PNG tạo bằng built-in image_gen là ảnh minh họa AI riêng từng sản phẩm, có provenance/prompt/SHA-256 trong image-provenance.json; không phải ảnh chụp hàng hóa thực tế. Cần thay bằng ảnh hàng thật và thông tin xác minh trước bán hàng. SVG cũ chỉ là bản placeholder dự phòng, không được Product tham chiếu. Không có review/doanh số/lượt mua giả.

## Separate frontend checkout

For asset validation when the repositories are checked out separately, set `TEASMART_FRONTEND_ROOT` to the frontend repository root before running `python -m unittest discover -s database/seeds/teasmart-demo-v1 -p test_seed.py`. This check only reads assets; it does not seed the database.
