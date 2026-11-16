# Gemini cho TeaSmart — chuẩn bị an toàn

Kiểm tra tài liệu Google ngày 16/11/2026. Không có API key trong source. Chưa gọi Google, chưa xác minh inference thật.

## Model và chi phí

- Adapter dùng endpoint tương thích OpenAI chính thức: `https://generativelanguage.googleapis.com/v1beta/openai/chat/completions`.
- Model mặc định đã chốt: `gemini-3.5-flash-lite`. Allowlist gồm model này và `gemini-2.5-flash` để tương thích khi được chọn tường minh.
- Không tự thay model; không chặn theo ngày. Bản tài liệu Google truy cập qua công cụ ngày 16/11/2026 vẫn có cảnh báo về 2.5 Flash/mốc 01/12/2026, khác nội dung người dùng đang xem. Quyết định vận hành dựa trên model được project thực tế cho phép; không mã hóa suy đoán lịch ngừng model.
- Với 3.5 Flash-Lite, bỏ sampling parameter temperature theo hướng dẫn migration; giữ JSON output. Chưa kiểm tra key/quota/inference thật.
- Free Tier là thuộc tính project/quota phía Google. Cờ local KHÔNG xác minh hoặc bảo đảm project miễn phí. Chỉ dùng project hiển thị Free Tier; không liên kết billing/nạp tiền. Nếu Google yêu cầu trả phí để dùng model, dừng và chọn phương án được duyệt.
- Không bật Google Search grounding, caching hoặc tính năng trả phí; chỉ gửi văn bản nguồn đã xác minh trong kho tri thức local. Không retry/fallback tự động khi 429 hoặc model không khả dụng.

## Nhập key, chưa bật gọi model

1. Tự mở https://aistudio.google.com/api-keys, chọn/tạo key của project Free Tier. Không gửi key vào chat, Git, `.env` hoặc command line. Kiểm tra model `gemini-3.5-flash-lite` khả dụng trong project. Tại https://aistudio.google.com/projects xác nhận Billing Tier là Free; không bấm Set up billing hoặc liên kết tài khoản trả phí. Tại https://aistudio.google.com/rate-limit chọn đúng project/model, xem RPM, TPM và RPD còn lại. Hạn mức áp dụng theo project, không mặc định một số quota cố định.
2. Mở PowerShell tại thư mục gốc TeaSmart (`teasmart`). Chạy:

```powershell
& .\backend\ai-service\run-rag-gemini.ps1 -Model 'gemini-3.5-flash-lite'
```

3. Nhập key tại prompt ẩn. Không cần gõ `$env:RAG_LLM_API_KEY=...`.
4. Service chạy foreground `127.0.0.1:8002`. Kiểm tra ở Terminal khác:

```powershell
Invoke-RestMethod http://127.0.0.1:8002/health
```

Kỳ vọng `retrieval=READY`, `llmConfigured=true`, `provider=gemini`, `providerCallsEnabled=false`. Điều này chỉ chứng minh cấu hình local có đủ trường, KHÔNG xác thực key/quota/model với Google. Khi tắt service bằng Ctrl+C, biến Process được khôi phục; key không được lưu ra file.

## Chỉ sau phê duyệt inference riêng

Launcher có hai cờ tường minh `-AllowProviderCalls -ConfirmFreeTier`. Chưa chạy hai cờ này trong phiên chuẩn bị. Chỉ bật sau khi người dùng xác nhận project/quota/model và duyệt lời gọi thật. Các cờ không tạo bảo đảm tài chính phía Google.

Sau khi được duyệt: chạy Python bằng launcher với model đã thống nhất; IntelliJ thêm `RAG_ENABLED=true`, `RAG_SERVICE_URL=http://127.0.0.1:8002`, rồi người dùng tự restart TeaSmartApplication. Key chỉ ở tiến trình Python, không cần đưa vào IntelliJ hoặc Frontend. Giá/tồn kho/FAQ/bestseller tiếp tục đi nhánh Business của Chatbot A.

## Kiểm tra và hạn chế

- Tests Gemini hiện dùng HTTP mock transport và key giả; không tính là inference.
- 401/403: kiểm tra key/quyền; 404: model không khả dụng; 429: hết quota, không tự retry/nâng cấp; 503/timeout: báo lỗi thân thiện, không tạo câu trả lời giả.
- Kho tri thức không phải dữ liệu fine-tune. Không có vector database mới.
- Free Tier có điều khoản sử dụng nội dung để cải thiện sản phẩm. Chỉ gửi câu hỏi kiến thức đã lọc dữ liệu nhạy cảm và trích đoạn nguồn công khai; không gửi account object, JWT, đơn hàng hoặc dữ liệu MySQL.

Nguồn chính thức:
- https://aistudio.google.com/docs/openai
- https://aistudio.google.com/docs/models/gemini-2.5-flash
- https://aistudio.google.com/docs/pricing
- https://aistudio.google.com/docs/billing
