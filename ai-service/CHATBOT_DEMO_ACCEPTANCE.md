# Chatbot A + B — demo acceptance, 17/11/2026

## Scope and current evidence

One floating Widget automatically routes business questions to read-only Spring services and knowledge questions to Python POST /answer. A is preserved. No MySQL integration or real provider call is part of this offline pass. The prior two-call allowance is exhausted. No new inference is authorized by this document.

Offline: Spring unit and loopback HTTP fixtures cover routing, product context, source validation, exact Python request schema, NO_EVIDENCE, 503/504, and no invented success. Python tests cover retrieval, citations, context, and sanitized provider diagnostics. Chrome headless API fixtures cover desktop/mobile, loading, no double send, error/manual retry, evidence labels, 503/504, and business recovery. All /api traffic in that harness is intercepted. Live CUSTOMER acceptance remains SKIP.

## User-controlled restart and health, without inference

1. Leave Frontend 3000 and IntelliJ 8080 running. Do not start another Backend. Stop only the existing Python RAG terminal with Ctrl+C; wait until it exits. If port 8002 remains occupied, identify its process before stopping anything.
2. From the original TeaSmart workspace run this PowerShell command, keeping provider calls blocked while checking readiness:

```powershell
& .\backend\ai-service\run-rag-gemini.ps1 -Model 'gemini-3.5-flash-lite'
Invoke-RestMethod http://127.0.0.1:8002/health
```

The launcher is foreground: run the health command in a second terminal. Enter the key only through SecureString prompt. Never paste it into chat. Expected health: retrieval READY, llmConfigured true, provider gemini, model gemini-3.5-flash-lite, providerCallsEnabled false, knowledgeDocuments 11, providerDiagnosticsVersion provider-errors-v1. A health GET never invokes Gemini. API key existence alone does not authorize calls.

3. In the existing IntelliJ TeaSmartApplication Run Configuration, set RAG_ENABLED=true and RAG_SERVICE_URL=http://127.0.0.1:8002 for the eventual knowledge demo. Keep SERVER_PORT=8080 and existing datasource/auth settings unchanged. Do not put the Gemini key in IntelliJ. These RAG env names were not present in the inspected saved TeaSmartApplication XML; inherited/runtime environment was not established. Defaults in application.properties are false and loopback8002. Stop/Run this exact configuration once yourself to load the tested Spring source. Do not use /chatbot/rag knowledge requests to check readiness before call approval.

## Exactly one new real call — only after separate user approval

- Confirm Free Tier/quota in AI Studio, keep the selected model, and ensure no other browser/client is asking knowledge questions during the one-call window. Manual Retry and quick knowledge prompts also consume attempts; do not click them.
- Restart the user-owned Python terminal with:

```powershell
& .\backend\ai-service\run-rag-gemini.ps1 -Model 'gemini-3.5-flash-lite' -AllowProviderCalls -ConfirmFreeTier
```

- Verify health in a separate terminal: the same version/model/11 documents, now providerCallsEnabled true. This GET does not consume an inference.
- Use ONE path: a single Widget question OR a single direct POST /answer, never both. Suggested question: “Cách pha chè Thái Nguyên như thế nào?”. Do not retry after any error or timeout; an uncertain timeout still counts as an attempt.
- Collect only public HTTP status, elapsed time, safe code, answer/source URLs if successful, and the sanitized rag_provider_failure line (request_id, model, duration_ms, diagnostic_code, upstream_status). Do not collect Authorization, raw provider bodies, keys, cookies or tokens. A status=decoded log is not proof of a grounded answer.
- If successful, compare the answer to the cited source. NO_EVIDENCE is not a successful knowledge answer. If failed, use RAG_PROVIDER_DIAGNOSTICS.md. Do not change model, billing, TLS verification or enable retries. Close the one-call window by returning Python to the default calls-blocked launch.

## Browser acceptance checklist

Use localhost:3000 and the CUSTOMER's own login. Do not create orders, payments or change inventory. Read-only business chat may query existing catalog; it never grants permission to mutate commerce data. Refresh starts a fresh in-memory conversation; navigation within the app should not duplicate sends.

| Prompt/action | Expected | Offline evidence | Live this pass |
|---|---|---|---|
| Xin chào | Friendly greeting, no provider | Classifier/unit | SKIP |
| Tôi muốn mua chè | Catalog-backed advice/cards | Business unit/Chrome fixtures | SKIP |
| Chè dưới 300k | Prices from catalog, strict budget | Consultation/unit/client | SKIP |
| Tôi muốn mua chè làm quà → ít chát | Preserve consultation preferences | Multi-turn/topic tests | SKIP |
| Cách đặt hàng / thanh toán / hủy / theo dõi đơn | Existing FAQ only; no order mutation | Business routing/unit | SKIP |
| Cách pha chè Thái Nguyên như thế nào? | Grounded knowledge or honest unavailability | Python + Spring + Chrome fixtures | SKIP: needs new approval |
| Bảo quản chè như thế nào? | Cited knowledge or honest fallback | Retrieval/fixtures | SKIP: additional calls need approval |
| Chè Tân Cương có gì đặc biệt? | Knowledge routing; do not invent facts | Classifier/retrieval | SKIP: additional calls need approval |
| Knowledge → Chè dưới 300k | Business routing continues despite RAG errors | Topic + Chrome recovery | SKIP |
| Provider 503/504 | Friendly error, no technical code, no auto retry | HTTP fixtures + Chrome | SKIP live fault injection |
| NO_EVIDENCE with llmUsed=true | No source badge claiming success | Spring + Widget + Chrome | SKIP live |
| Close/open, refresh, Back/Forward | No duplicate send; reset semantics clear | Chrome covers close/open/reset; remaining manual | SKIP live |

One approved call cannot establish all three live knowledge topics above. Request further explicit quota before running the remaining knowledge cases. Never mark Chatbot B overall PASS from fixtures alone.

## Offline results for this change

- Spring chatbot/catalog/topic/context tests: 46 passed, 0 failed, 0 skipped; repositories mocked, Python replaced by loopback HTTP fixtures. No Spring database context started.
- Python RAG: 62 passed, 0 failed; mocked transport only.
- Frontend: 232 passed, 0 failed, 0 skipped. TypeScript and production build passed.
- Chrome headless on temporary 4176: six scenario groups passed with all API calls intercepted, including evidence/no-evidence/503/504 and catalog recovery. Temporary browser/server closed normally; port3000 untouched.
- Backend packaged successfully into an isolated target/chatbot-offline output. The initial ordinary target package failed on JAR rename; no user service was stopped or existing artifact deleted. Temporary alternate-output POM was removed.
- Existing warnings: Starlette deprecation, Node type-transform warning, concurrent SSR HMR port24678 warnings and frontend chunk-size warning. Test and isolated build exit codes were successful.
- No real Gemini requests, database writes or user-owned service restarts performed. Live CUSTOMER/grounded Gemini acceptance is SKIP in this pass.
