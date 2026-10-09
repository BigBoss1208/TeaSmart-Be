# Review AI integration — 05.11-E2-B2

Admin-only, latest-result analysis. No automatic APPROVE/HIDE/DELETE, Customer
analysis trigger, image analysis, or changes to Review content/timestamps.
The E2-A migration is already applied; do not rerun it for this integration.

## Run locally

Prepare the model using the existing ai-service README. From ai-service:

```powershell
.\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000 --workers 1 --limit-concurrency 16 --no-access-log
```

From backend, provide existing DB/JWT environment variables, then:

```powershell
.\mvnw.cmd clean compile
.\mvnw.cmd spring-boot:run
```

| Environment | Default |
|---|---|
| REVIEW_AI_BASE_URL | http://127.0.0.1:8000 |
| REVIEW_AI_TIMEOUT_SECONDS | 15 |
| REVIEW_AI_LEASE_SECONDS | 45 |

URL is server configuration, never client input; no URL credentials/query/fragment.
HTTP redirect disabled, connect timeout 2 seconds, full-response deadline finite,
body capped at 16 KiB, no automatic retry. Lease must exceed timeout by >=10 seconds.
FastAPI must stay private/localhost in this local development configuration.

## API

- GET /api/admin/reviews/{reviewId}/ai-analysis
- POST /api/admin/reviews/{reviewId}/ai-analysis — no request body.

Existing /api/admin/** security requires ADMIN. Service also checks current active
Admin. Positive ID required. Missing Review is 404; DELETED cannot be analyzed (409).
GET may read previous analysis for DELETED Review.

Response fields: sentimentLabel, confidence, needsReview, moderationFlags, reasons,
analysisMethod, modelVersion, processingStatus, analyzedAt, isStale, errorCode.
No analysis returns 200 NOT_ANALYZED with nullable result fields and empty lists.
Reasons deliberately return [] for BOTH GET and POST: database has no reasons column,
and this integration neither persists nor fabricates explanations. Flags retain their
fixed codes. FastAPI reasons are validated then discarded. Confidence remains null.

COMPLETED requires MODEL with whitelisted label, flags and valid field types.
SKIPPED retains null label/confidence/needsReview; INPUT_TOO_LONG is not NEUTRAL.
Unexpected fields, duplicate JSON keys, malformed output, invalid enums, excessive
length, unsupported content type or fabricated confidence are rejected.

## Transactions and stale input

Facade uses NOT_SUPPORTED. Separate Spring transaction bean uses REQUIRES_NEW:

1. Current active Admin check; immutable owner ID projection; lock User -> Review ->
   Analysis. Capture comment/rating, hash and UUID runId. Commit PROCESSING.
2. Send only comment/rating to FastAPI with no active database transaction.
3. Lock in the same order; recheck Review, runId, status, lease and current input hash.
   Persist result only when the run is still current.

Canonical SHA-256 includes a format version, rating, explicit null marker or UTF-8
length-prefixed NFC comment. It does not use updatedAt, moderation status or images.
GET computes isStale from current input. Rating/comment changes invalidate analysis;
APPROVE/HIDE or image changes alone do not invalidate this text-only input.

Changed content: 409 REVIEW_AI_INPUT_CHANGED; DELETED: 409 REVIEW_DELETED; a superseded
run: 409 REVIEW_AI_SUPERSEDED. These paths never change Review or restore DELETED.
FAILED changes commit before the facade throws the HTTP error, avoiding rollback of
the failure record. Database errors remain errors, not fabricated AI success.

## Lease and failures

Live PROCESSING blocks a second POST with 409 REVIEW_AI_IN_PROGRESS. Expired lease
can be replaced by POST with a new runId. GET performs short locked recovery to FAILED
REVIEW_AI_LEASE_EXPIRED. Thus GET can update analysis metadata, but never Review.
No queue/scheduler; untouched expired rows are recovered on the next GET/POST.

- 502 REVIEW_AI_INVALID_RESPONSE.
- 503 REVIEW_AI_UNAVAILABLE.
- 504 REVIEW_AI_TIMEOUT.
- 409 REVIEW_AI_LEASE_EXPIRED for a late finishing run.

FAILED clears the PROCESSING state; Admin can retry. PROCESSING/FAILED analysisMethod
MODEL denotes the attempted provider, not completed inference; result fields are null.
Model outage does not block existing Customer Review or Admin moderation endpoints.

## E2-B2 verification

Clean compile: 154 Java sources BUILD SUCCESS; Hibernate validate and Tomcat PASS.
Integration smoke: 60 Spring HTTP requests, 85 assertions PASS. Normal proxy mode
forwarded to unchanged real FastAPI CPU model; fault modes were separate and explicit.
Covered persistence/GET consistency, null/token SKIPPED, 401/403/404/400, stale retry,
lease recovery, invalid output, HTTP target offline, timeout, retry after FAILED,
concurrent Customer PUT, concurrent Admin approve/hide, concurrent Customer DELETE,
and overlapping POST conflict. AI calls did not block Customer PUT; Review snapshots
were unchanged by inference. Catalog/Core Review/Admin/Cart/Address/Auth-current-user
read smoke checks passed. Full concurrency/fault/regression matrix belongs to E3.

Backup of 19 tables lives outside Git. Controlled Product/Order/OrderItem/Review
fixtures were removed; original data/schema/FK/UNIQUE matched the backup. Counters
advanced naturally by one in products/orders/order_items/reviews and were not reset.
No real accounts/passwords were edited. JWT smoke signing keys stayed in memory.
First end-to-end inference was approximately 196 ms; not an accuracy/latency benchmark.
No model accuracy/F1 results or peak RAM claim is made here.
One concurrent runtime sample: free RAM 0.99 GiB, Spring JVM working set 320.5 MiB,
FastAPI main process 34.0 MiB and its single model worker 518.2 MiB. Working set is
a point-in-time resident-memory reading, not peak or total allocated memory.

No FastAPI/source E2-A changes, migration, frontend changes, staging or commit/push.
