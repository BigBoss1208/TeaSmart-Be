# Chatbot A — catalog-driven tea lines

Named tea inquiries are resolved from current public category names, region names, and product names. Vietnamese accents are normalized and chè/trà aliases share matching. There is no fixed list of supported varieties. A teaLine preference (optional, max100 chars) preserves the selected line across budget/taste follow-ups. Existing nine-argument Java DTO constructors and older clients remain compatible. No database column or migration is added.

Region IDs remain hard filters. A variety matches its category or a whole phrase in product names, so a named variety inside gift packaging is eligible even under the gift category. Price bounds and known low-astringency constraints remain hard filters; out-of-stock products are excluded, never replenished by chat. Explicit new line/region requests replace stale topic context. Unknown named lines return no suitable stock instead of the entire catalog.

LINE_PRODUCTS uses a short catalog-attributed introduction, the actual filtered count and a natural follow-up. Intro descriptions are taken from category/region metadata, limited to two short sentences and omit obvious medical/certification claims; when metadata is absent, the bot states that rather than inventing taste. These are catalog statements, not independently verified certifications or generated RAG citations. No provider is used. Spring preserves this reply instead of replacing it with the generic catalog reply. Product cards and pagination use the existing Widget, unchanged from c01b749.

Comparison of two named categories uses their catalog descriptions without listing the entire catalog. Brewing/storage/origin tasks retain the knowledge route. This change does not enable RAG or Gemini, alter its model or validate real inference. For manual knowledge-route testing without quota, keep provider calls blocked or explicitly send allowLlm=false.

## Validation, 17/11/2026

- Backend unit/HTTP-fixture regression: 55 PASS, including TeaCatalogTopicTest (9 scenarios) and the existing 46 chatbot/catalog tests.
- Frontend regression: 233 PASS; TypeScript and production build PASS.
- Backend isolated package build PASS. No running user service was stopped by Codex.
- Chrome headless with API fixtures at1440/390px PASS: LINE_PRODUCTS context reaches subsequent requests; per-answer cards, pagination, details and scroll behavior remain correct. This is mock UI evidence, not database integration acceptance.
- Public GET audit on Backend8080 observed25 products,7 used categories and5 regions;13 products were assigned to Tân Cương. API detail provides stock and taste fields. No public catalog data was hardcoded into source.
- After the user restarted IntelliJ, a real authenticated read-only chat run received13 matching Tân Cương products and showed initial4/pagination. Subsequent complete live acceptance was not established: the harness had a Unicode comparison bug and then a login-readiness race. The user elected to test personally. The unfinished live harness was preserved locally and excluded from Git. Do not label the full real integration PASS from fixtures.
- No Gemini inference, order/cart/payment mutation or stock modification was performed. The user performed login themselves; chatbot requests used allowLlm=false. No main schema changes.

## Manual acceptance

Refresh localhost:3000 after restarting TeaSmartApplication on8080 (source has already been synchronized). Use an existing CUSTOMER session. Ask Chè Tân Cương, Chè đinh, Chè nõn tôm, Chè móc câu, Chè La Bằng, Chè Tân Cương dưới300k, Có chè nõn tôm không?, and Chè đinh và chè nõn tôm khác nhau gì?. Verify card metadata against the current product detail API. After Tân Cương ask Loại nào dưới300k? and Loại nào ít chát?, then explicitly switch to La Bằng to verify old constraints are replaced. Verify four initial cards and Xem thêm independently per response on desktop/mobile.

Only test Cách pha chè Tân Cương? with provider calls blocked: it must stay in the knowledge route, not list catalog products. No new Gemini call allowance is granted by this checklist.
