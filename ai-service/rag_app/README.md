# TeaSmart Chatbot RAG — vận hành local

Trạng thái 17/10/2026: **PARTIAL**. Đã kiểm thử pipeline offline, MySQL/API và Chromium; chưa có API key nên **chưa nghiệm thu LLM thật**. Không thay thế Recommendation hoặc AI bệnh lá chè.

## Kiến trúc

React `TeaAdvisor` → Spring `POST /api/chatbot/rag` (CUSTOMER) → một trong hai luồng:

- **PRODUCTS/BESTSELLERS/REVIEWS**: `RagBusinessService` đọc catalog hoặc SQL cố định; trả dữ liệu thật, `llmUsed=false`. Không gửi dữ liệu kinh doanh/PII/JWT tới LLM.
- **KNOWLEDGE**: Spring kết thúc transaction đọc trước khi gọi Python loopback. Python chunk/retrieve tài liệu → OpenAI-compatible chat completion → kiểm tra JSON, chunk ID và quote → reply + nguồn. Thiếu key/nguồn hợp lệ thì báo rõ, không tạo dữ liệu thay thế.

`POST /api/chatbot/advice` và `/api/recommendations` giữ nguyên. UI có chế độ danh mục nhanh tường minh để dùng khi RAG chưa cấu hình.

## Cấu hình

Backend (Process/IntelliJ):

- `RAG_ENABLED=true` (mặc định false) chỉ cho phép nhánh kiến thức gọi Python/LLM. Tra cứu sản phẩm qua `/api/chatbot/rag` vẫn hoạt động khi false, không gọi Python.
- `RAG_SERVICE_URL=http://127.0.0.1:8002` (chỉ HTTP loopback).
- Các biến datasource/JWT cũ giữ nguyên; chọn đúng DB test khi nghiệm thu ghi. Không tự chạy migration.

Python:

- `RAG_LLM_URL`: URL HTTPS đầy đủ của endpoint chat/completions do provider cung cấp.
- `RAG_LLM_MODEL`: model ID thực tế của provider.
- `RAG_LLM_API_KEY`: chỉ Process; không ghi `.env`, Git, lịch sử terminal hoặc báo cáo.
- `RAG_INDEX_DIR`: tùy chọn, mặc định thư mục người dùng `~/.teasmart/chatbot-rag`. SQLite nằm ngoài repo; chỉ chứa tài liệu công khai và vector, không chat/PII.

Trong PowerShell ở project root:

```powershell
& .\backend\ai-service\run-rag-local.ps1
```

Script hỏi endpoint/model và nhập key ẩn, chạy foreground port 8002; Ctrl+C để dừng và phục hồi biến Process trước đó. Dùng venv AI đang có; không tự cài package. Không dùng Client Secret Google hoặc JWT secret làm LLM key.

Trong IntelliJ cấu hình Backend đã có, thêm RAG_ENABLED/RAG_SERVICE_URL rồi tự Run. Không tự chuyển database. Frontend dùng proxy `/api` như cũ. Truy cập `/ai`, đăng nhập CUSTOMER. ADMIN không dùng API tư vấn Customer.

## API

`POST /api/chatbot/rag`, Authorization JWT TeaSmart theo API client hiện có:

```json
{"message":"Top 5 chè bán chạy","preferences":{},"history":["Tôi thích chè nhẹ"]}
```

`context` cùng type TeaPreferenceRequest, tùy chọn. message tối đa 1.000 ký tự; history tối đa 6 câu hỏi người dùng, mỗi câu tối đa 1.000 ký tự. Không gửi JWT/cookie/email/account object trong body.

Response giữ preferences/notices/recommendations của advisor và thêm:

- method: READ_ONLY_BUSINESS_V1 / RAG_LLM_V1 / RAG_NO_EVIDENCE.
- reply; sources (id/title/url/publisher/accessedAt).
- embeddingMethod; llmUsed.
- businessKind; businessProducts (ID, name, slug, giá, tồn, unitsSold hoặc averageRating/reviewCount).

Lỗi: 400 validation/private input; 401/403 theo Security cũ; 503 RAG_DISABLED/RAG_NOT_CONFIGURED/provider unavailable/busy; 504 timeout. Không trả prompt, provider response gốc hoặc exception message.

Python nội bộ `/health`, `/answer`; không publish Internet, không có endpoint nhận SQL hoặc ghi MySQL. HTTP loopback chỉ thích hợp máy local tin cậy; deploy cần kiểm soát mạng/service authentication riêng.

## Retrieval và dữ liệu

- `rag_app/knowledge/sources.json`: năm bản tóm tắt có URL/cơ quan/ngày truy cập. Không scrape tự động khi chạy và không tải model/dataset.
- Chunk tối đa 450 ký tự, overlap 70, ưu tiên ranh giới từ.
- **VI_LEXICAL_HASH_2048_V1 là lexical hashing embedding**: unigram/bigram bỏ dấu, L2-normalized, cosine. SQLite lưu vector. **Không phải semantic embedding pretrained hoặc mô hình đã huấn luyện.** Synonym/negation và câu hỏi xa từ khóa còn hạn chế.
- Retrieve tối đa 4 chunk, ngưỡng 0.08 là cấu hình heuristic chưa hiệu chỉnh trên tập đánh giá.
- Provider trả tối đa 3 đoạn JSON với sourceId và quote chính xác từ chunk. Python từ chối source bịa, quote không khớp, URL/HTML hoặc số liệu kinh doanh trong văn bản. Kiểm tra quote **không chứng minh** mọi diễn giải của LLM đúng; vẫn cần đánh giá thủ công.
- Bestseller toàn thời gian: tổng quantity của Order DELIVERED + Payment PAID + paid_at có giá trị + reconciliation_required=false; chỉ sản phẩm và Category/Region/Store ACTIVE. Không dùng doanh thu giả hoặc coi PENDING là đã bán.
- Review ranking chỉ APPROVED. Không có dữ liệu thì trả rỗng; không tự tạo lịch sử bán/đánh giá.

## Giới hạn và bảo mật

HTTP provider timeout 25s/connect 5s, Spring 35s, Frontend 45s kết hợp cancellation. Python tối đa hai inference đồng thời; không retry tự động gây tính phí lặp. Body Python 16KB, provider response 64KB, Spring response 32KB.

System prompt coi câu hỏi/lịch sử/đoạn trích là dữ liệu; không cấp tool SQL/file cho LLM. Email/phone và mẫu key/password trong câu hỏi được redaction trước provider; JWT/Bearer bị chặn. Không có bộ lọc PII hoàn hảo; UI yêu cầu không nhập dữ liệu cá nhân. Nhà cung cấp có thể lưu câu hỏi theo chính sách của họ; phải kiểm tra trước dùng thật.

Chưa có semantic model, corpus trồng/chăm sóc/sâu bệnh chi tiết, benchmark chất lượng, persistent chat, streaming hoặc giải quyết tham chiếu mơ hồ hoàn chỉnh. Không chẩn đoán bệnh lá hoặc hướng dẫn thuốc. Không giả vờ hiểu mọi câu ngoài phạm vi.

## Test

```powershell
.\backend\mvnw.cmd -f backend/pom.xml test
& .\ai-service\.venv\Scripts\python.exe -m pytest backend/ai-service/tests -q
node --experimental-transform-types --test tests/*.test.ts
npm run lint
npm run build
```

Maven MySQL tests mặc định có thể SKIP khi chưa bật profile/biến test riêng. Không bật chúng với DB chính. Xem kết quả cụ thể và nghiệm thu cô lập trong `BaoCaoTrienKhai/AI_CHATBOT/AI_CHATBOT_RAG_FINAL_REPORT.md`.

Bước nghiệm thu còn thiếu: có provider endpoint/model/key hợp lệ → script nhập ẩn → health llmConfigured=true → các câu hỏi kiến thức trên browser có sources → kiểm tra quote/ý nghĩa và tính trung thực → đánh giá ít nhất một tập câu hỏi gán nhãn thủ công. Chưa có kết quả thì không đánh dấu RAG/LLM PASS.

## Chatbot integration (15/11/2026)

- `/ai` opens TeaAdvisor; `/leaf` remains the separate leaf preview tool.
- Full page and floating widget default to catalog mode (`allowLlm=false`). Product, budget and taste questions use the existing read-only catalog services, even when `RAG_ENABLED=false`.
- Knowledge mode is an explicit user selection. It needs the backend flag, Python service and all three provider variables above. Missing configuration/provider errors remain visible, never replaced with a fabricated LLM answer.
- Catalog data is current at request time, not a stock reservation. No payment, order or inventory writes are made by chat.
- Verification: MySQL integration tests exercise catalog data and authentication; Chrome UI smoke uses intercepted synthetic API responses. Neither establishes a successful real provider call.
- After configuring the provider privately, restart Python and the IntelliJ backend. On `/ai`, select knowledge mode and ask “Cách pha chè để giữ hương thơm?”. Verify the response says RAG + LLM and includes source links. Do not label this PASS until a real provider response is observed.
