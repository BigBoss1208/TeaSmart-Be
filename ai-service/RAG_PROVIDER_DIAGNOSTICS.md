# RAG provider diagnostics

Provider diagnostics do not enable inference. The existing explicit call gate, model selection, endpoint restriction, no redirects and no retries remain unchanged. Public `/answer` responses still contain only the existing safe `code` on errors. Internal `RagFailure.diagnostic_code` and `upstream_status` are not serialized.

| Evidence | Internal diagnostic | Public code / HTTP |
|---|---|---|
| Received upstream HTTP 401 or 403 | RAG_UPSTREAM_HTTP_401 / 403 | RAG_PROVIDER_AUTH_REJECTED / 503 |
| Received upstream HTTP 404 | RAG_UPSTREAM_HTTP_404 | RAG_MODEL_UNAVAILABLE / 503 |
| Received upstream HTTP 429 | RAG_UPSTREAM_HTTP_429 | RAG_RATE_LIMITED / 503 |
| Received upstream HTTP 503 | RAG_UPSTREAM_HTTP_503 | RAG_PROVIDER_UNAVAILABLE / 503 |
| Other non-200 HTTP | RAG_UPSTREAM_HTTP_<status> | RAG_PROVIDER_UNAVAILABLE / 503 |
| socket.gaierror retained in exception chain | RAG_DNS_ERROR | RAG_PROVIDER_UNAVAILABLE / 503 |
| ssl.SSLError retained in exception chain | RAG_TLS_ERROR | RAG_PROVIDER_UNAVAILABLE / 503 |
| ConnectError without stronger typed evidence | RAG_CONNECT_ERROR | RAG_PROVIDER_UNAVAILABLE / 503 |
| Other NetworkError | RAG_NETWORK_ERROR | RAG_PROVIDER_UNAVAILABLE / 503 |
| ProtocolError | RAG_HTTP_PROTOCOL_ERROR | RAG_PROVIDER_UNAVAILABLE / 503 |
| Other HTTPError (e.g. response decoding) | RAG_HTTP_TRANSPORT_ERROR | RAG_PROVIDER_UNAVAILABLE / 503 |
| Connect/read/write/pool timeout | RAG_TIMEOUT_CONNECT / READ / WRITE / POOL | RAG_TIMEOUT / 504 |
| Other TimeoutException | RAG_TIMEOUT | RAG_TIMEOUT / 504 |
| Invalid JSON or OpenAI-compatible envelope/content | RAG_PROVIDER_RESPONSE_INVALID | RAG_INVALID_OUTPUT / 502 |
| Response exceeds 64 KiB | RAG_PROVIDER_RESPONSE_TOO_LARGE | RAG_INVALID_OUTPUT / 502 |

DNS and TLS are only labeled when their exception types remain available. An opaque ConnectError must not be claimed to prove either. `upstream_status=None` means no HTTP status was observed; a read/transport failure after headers can retain status 200. Status alone does not prove the response was a valid generated answer.

Failure logs use WARNING and include only generated request ID, validated model identifier, duration, fixed diagnostic code and observed HTTP status. They exclude exception strings/tracebacks, bodies, user questions/history/passages, headers, full URLs, keys and tokens. Parsing logs report `status=decoded`, not a grounded-answer success: final paragraph/source/quote validation is still performed by main.py without weaker rules.

## Prior failure and next controlled test

The second previously approved inference preserved local HTTP 503 with RAG_PROVIDER_UNAVAILABLE, but no upstream status or exception category. The old mapping grouped upstream non-200 errors (excluding specifically mapped auth/model/rate errors) and HTTP transport errors. It cannot retrospectively prove Google returned 503, DNS failed, or TLS failed. Recognized timeouts and parser failures already had separate public codes; neither was recorded for that call.

No provider requests are needed to run tests:

```powershell
python -m pytest ai-service/tests/test_rag.py ai-service/tests/test_gemini_provider.py -q
```

Before a new real inference: load this code in the user-owned RAG service, retain the explicit call gate and selected model, verify Free Tier/quota in AI Studio, ensure no other caller is consuming the approved budget, and obtain a new explicit one-call approval. Do not add retries or probe model endpoints. A timeout may still have reached the provider and must count as an attempt. Stop after the single attempt and collect only the sanitized diagnostic log.

If upstream 503 is actually observed, wait/check the Google service status and request a separately approved later attempt; do not rotate model, credentials or billing. If DNS/connect/TLS is observed, diagnose local resolution, connectivity or certificate trust in the same Windows environment without authenticated generation. Do not disable TLS verification. For timeouts, identify connect/read/write/pool first; do not blindly extend timeouts or retry.

References: https://ai.google.dev/gemini-api/docs/troubleshooting and https://www.python-httpx.org/exceptions/ . Google documents transient-error retry strategies, but this application intentionally does not enable automatic retries under a fixed user-approved call budget.
