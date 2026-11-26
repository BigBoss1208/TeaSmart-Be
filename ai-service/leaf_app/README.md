# Dịch vụ AI lá chè TeaSmart

FastAPI chạy riêng trên `127.0.0.1:8003`. Dịch vụ dùng checkpoint MobileNetV2 epoch 20 để phân loại ảnh và tổng hợp kết quả trên tối đa 8 khung hình video. Trọng số được bàn giao ngoài Git theo [manifest tài nguyên](MODEL_ARTIFACTS.json).

## Chuẩn bị và khởi động

Cài venv Python 3.12 theo [README Backend](../../README.md). Với venv Leaf riêng đã có Torch và Torchvision tương thích, có thể cài phần phụ trợ từ [requirements Leaf](requirements.txt). Đặt ba tệp mô hình cùng thư mục và kiểm tra SHA-256 trước khi khởi động.

Chạy từ thư mục `ai-service`. Ví dụ dưới đây dùng CPU và thư mục `leaf-model` trong `ai-service`.

```powershell
$py = (Resolve-Path -LiteralPath '.venv/Scripts/python.exe').Path
$env:LEAF_MODEL_DIR = (Resolve-Path -LiteralPath 'leaf-model').Path
$env:LEAF_DEVICE = 'cpu'
& $py -B -m uvicorn leaf_app.main:app --host 127.0.0.1 --port 8003 --workers 1
```

Dùng một worker để nạp model một lần và xử lý lần lượt các yêu cầu tải lên. `LEAF_DEVICE=auto` chọn CUDA nếu có và dùng CPU khi không có. Đặt `cuda` nếu venv và driver hỗ trợ. Service đối chiếu checkpoint, epoch 20, mapping 7 lớp và tiền xử lý trước khi báo sẵn sàng. Khi health trả HTTP 503 `MODEL_NOT_READY`, xem log cục bộ và kiểm tra tài nguyên.

## API và giới hạn

```powershell
Invoke-RestMethod 'http://127.0.0.1:8003/health'
$uri = 'http://127.0.0.1:8003/predict'
Invoke-RestMethod -Uri $uri -Method Post -ContentType 'image/jpeg' -InFile 'mau-la-che.jpg'
Invoke-RestMethod -Uri $uri -Method Post -ContentType 'video/mp4' -InFile 'mau-la-che.mp4'
```

`POST /predict` nhận thân tệp thô với `image/jpeg`, `image/png` hoặc `video/mp4`. Kết quả gồm `media_type`, `model_id`, lớp dự đoán, điểm mô hình và xác suất đủ 7 lớp. Với video, kết quả thêm thời lượng, dự đoán từng khung, số khung đồng thuận và cảnh báo nếu cần. Model dùng trung bình xác suất softmax của các khung lấy trên timeline. Ảnh và video dùng RGB, resize toàn khung 224×224 và chuẩn hóa ImageNet.

- Ảnh tối đa 10 MiB và 16 triệu pixel. Video tối đa 30.000.000 byte, 15 giây, 8.847.360 pixel/khung và 960 khung giải mã. Ngân sách giải mã được kiểm tra khi có khung.
- Service đếm byte thực và dọn tệp tạm sau yêu cầu. Đầu vào không hợp lệ trả lỗi có mã, còn yêu cầu khi đang bận trả HTTP 429.
- Điểm dự đoán của từng tệp dùng để tham khảo. Accuracy 85,66% và Macro-F1 82,55% được đo trên 530 ảnh Test. Đánh giá trên video chưa có.

## Kiểm thử

```powershell
& $py -B -m unittest leaf_app.test_leaf_app -v
```

Smoke checkpoint thật dùng hai ảnh ngoài tập Test, thuộc thư mục `train` của `tea-leaf-clean-v1`, rồi tạo video thử trong bộ nhớ. Test tự mở cổng loopback tạm. Đặt `LEAF_REAL_SMOKE=1`, `LEAF_SMOKE_TRAIN_DIR` và `LEAF_MODEL_DIR` trước khi chạy. Mục đích là xác minh API và checkpoint đã bàn giao.
