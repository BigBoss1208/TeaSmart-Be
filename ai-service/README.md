# TeaSmart Review AI — 05.11-E2-B1

Python service độc lập cho sentiment và cờ hỗ trợ kiểm duyệt văn bản.
Không kết nối database, không thay đổi Review, không gọi LLM API.
Admin luôn quyết định APPROVE/HIDE; sentiment NEGATIVE không mặc định vi phạm.

## Môi trường đã kiểm tra

- Windows, Python 3.12.10, JDK 17, CPU; không yêu cầu GPU.
- PyTorch 2.6.0+cpu, Transformers 4.57.6, FastAPI 0.115.14,
  Pydantic 2.11.7, Uvicorn 0.35.0, py-vncorenlp 0.1.4, pyjnius 1.7.0.
- `requirements.txt`: dependency trực tiếp đã pin; `requirements.lock`: toàn bộ
  dependency thực tế trong venv đã kiểm thử. Không cài vào Python global.

Chạy các command dưới đây trong thư mục `ai-service`:

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install --extra-index-url https://download.pytorch.org/whl/cpu -r requirements.lock
.\.venv\Scripts\python.exe -m pip check
.\.venv\Scripts\python.exe prepare_model.py
```

`prepare_model.py` tải công khai một lần, không dùng Hugging Face token.
Không tải model trong `/analyze`. Sau khi chuẩn bị, runtime chỉ load local cache.

## Model, revision và license

- Model: `wonrax/phobert-base-vietnamese-sentiment`.
- Revision: `9076a5896971b5d551588fe8a51c722c89731d36`.
- Model card khai báo MIT, fine-tune trên bình luận thương mại điện tử.
- Mapping config đã kiểm tra: `0=NEG`, `1=POS`, `2=NEU`.
- Tokenizer: PhobertTokenizer, slow tokenizer, giới hạn 256 token,
  tính cả special tokens. Không truncate input.
- VnCoreNLP revision: `62bbc58fe5d113c898eae112656be97dcf50b3a0`,
  VnCoreNLP 1.2, chỉ dùng `wseg`; license GPL-3.0-or-later.
- Preprocessing: `vncorenlp-wseg-nfc-preserve-symbols-v1`; rules: `rules-v1`.

Nguồn:
[Model card](https://huggingface.co/wonrax/phobert-base-vietnamese-sentiment),
[PhoBERT](https://github.com/VinAIResearch/PhoBERT),
[VnCoreNLP](https://github.com/vncorenlp/VnCoreNLP).
Các giấy phép của model và segmenter là riêng biệt.

## Cache và cấu hình

Cache mặc định: `$HOME/.teasmart/review-ai`, ngoài source/repository.
Có thể đặt `AI_MODEL_CACHE` trước cả prepare và startup để dùng vị trí khác.
Cache lưu revision cố định và manifest; không lưu comment hoặc dữ liệu khách hàng.

| Environment | Mặc định |
|---|---|
| `AI_MODEL_CACHE` | `$HOME/.teasmart/review-ai` |
| `AI_STARTUP_TIMEOUT_SECONDS` | 90 |
| `AI_INFERENCE_TIMEOUT_SECONDS` | 10 |
| `AI_QUEUE_TIMEOUT_SECONDS` | 2 |

JDK phải tồn tại. Trên Windows worker tìm JDK từ `java.exe` trên PATH nếu chưa
có `JAVA_HOME`; có thể cấu hình `JAVA_HOME` rõ ràng nếu dùng Java shim hoặc OS khác.
Không sửa Windows User variables tự động.

Model load một lần trong một worker CPU được cách ly. Worker cũng serialize
segmentation/inference; request hết thời gian chờ trả MODEL_BUSY. Khi timeout hoặc
worker chết, readiness chuyển false; khởi động lại service để load lại.

## Chạy localhost

```powershell
$env:HF_HUB_OFFLINE = "1"
$env:TRANSFORMERS_OFFLINE = "1"
.\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8001 --workers 1 --limit-concurrency 16 --no-access-log
```

Không bật `--reload` hoặc nhiều workers trên máy ít RAM. Dừng bằng Ctrl+C.
Không cấu hình CORS rộng. V1 không có API authentication riêng: chỉ bind localhost,
không expose Internet; integration/service authentication thuộc checkpoint sau.
FastAPI debug tắt, không log comment hoặc trả stack trace/local model path qua API.

```powershell
Invoke-RestMethod http://127.0.0.1:8001/health
$body = @{comment="Chè thơm, vị đậm và giao hàng cẩn thận."; rating=5} | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8001/analyze -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
```

## Contract

- `GET /health`: 200 READY chỉ khi worker/model thực sự ready; 503 NOT_READY khi lỗi.
- `POST /analyze`: JSON chỉ có `comment` nullable, tối đa 2.000 ký tự và `rating`
  integer bắt buộc 1–5. Reject field lạ, User/Order/JWT/image và sai kiểu.
- Response thành công: `sentimentLabel`, `confidence`, `needsReview`,
  `moderationFlags`, `reasons`, `analysisMethod`, `modelVersion`,
  `processingStatus`, `errorCode`. Không có runId/inputHash.
- Confidence luôn null ở V1; không xuất raw softmax như xác suất đúng.
- Null/rỗng/whitespace: 200 SKIPPED, label/confidence/needsReview null, không gọi model.
- Vượt 256 token: 200 SKIPPED, INPUT_TOO_LONG; không âm thầm cắt input.
- 422 INVALID_REQUEST; 415 UNSUPPORTED_MEDIA_TYPE; body >16 KiB: 413 REQUEST_TOO_LARGE.
  Mỗi lần đọc body có timeout 5 giây, trả 408 REQUEST_TIMEOUT.
- 503 MODEL_NOT_READY/MODEL_BUSY; 504 MODEL_TIMEOUT; 502 MODEL_OUTPUT_INVALID.
  Response lỗi chỉ có errorCode, không giả thành kết quả COMPLETED.

`analysisMethod=MODEL` mô tả sentiment thật; moderation flags luôn từ rules-v1,
không phải model toxicity/spam. Không có rule-based sentiment fallback giả AI.

## Preprocessing và moderation

NFC, giữ dấu, phủ định và emoji. VnCoreNLP tách từ phần văn bản; symbol/emoji
graphemes được giữ ở đúng thứ tự ngoài Java bridge, vì pyjnius 1.7 đã được xác minh
làm biến đổi non-BMP khi chuyển trực tiếp sang Java String. Emoji có thể là unknown
token với PhoBERT; việc giữ ký tự không chứng minh model hiểu tốt mọi emoji.

Flags advisory: ADVERTISING_LINK, ADVERTISING_LANGUAGE, REPETITIVE_CONTENT,
POSSIBLE_INSULT, RATING_SENTIMENT_MISMATCH. Mismatch chỉ dùng hai trường hợp cực:
rating 5 + NEGATIVE hoặc rating 1 + POSITIVE; không dùng như bằng chứng vi phạm.
Rules chỉ xem một comment, không kết luận spam liên tài khoản. Regex giới hạn,
đếm n-gram 2/3 từ; không regex lồng nhau gây catastrophic backtracking.

## Tests và bằng chứng

```powershell
.\.venv\Scripts\python.exe -m pytest -q
$env:RUN_MODEL_SMOKE = "1"
.\.venv\Scripts\python.exe -m pytest -q -s
Remove-Item Env:RUN_MODEL_SMOKE
.\.venv\Scripts\python.exe -m compileall -q app tests prepare_model.py
```

Contract/rules/preprocessing tests dùng test doubles được ghi rõ. Real model smoke
và Uvicorn HTTP smoke chỉ chạy khi RUN_MODEL_SMOKE=1; cần cache/model/JDK thật.
HTTP smoke bind cổng localhost tạm và shutdown server/worker sau test.
Không truy cập MySQL hoặc tạo fixture. Các câu smoke là mẫu tổng hợp,
không phải accuracy/F1 evaluation. Chưa có dataset TeaSmart gán nhãn.

Đã đo tại E2-B1: cache khoảng 543,8 MiB; venv khoảng 1.449,7 MiB.
Ba HTTP CPU smoke requests khoảng 379/42/52 ms, request đầu gồm warm-up;
không coi là p95 hoặc benchmark. Chưa đo peak RAM. Máy có 7,74 GiB RAM và chỉ
khoảng 0,54 GiB trống khi preflight; tránh chạy nhiều model workers/Backend cùng lúc.

## File scope

`app/`: config, schema, sentiment worker, moderation rules, FastAPI.
`tests/`: contract/rules, preprocessing/timeout, model smoke, Uvicorn HTTP smoke.
`prepare_model.py` tách download khỏi serving; `requirements.lock` giúp tái lập venv.
`.gitignore` loại venv, weights/cache, pycache, .env, logs và private-data.
Không có thay đổi Java/DB hoặc Spring integration trong E2-B1.
