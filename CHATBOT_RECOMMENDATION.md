# TeaSmart V1 — Tea Advisor and Content Recommendations

## Scope and architecture
Spring Boot owns the MySQL catalog, authorization, Vietnamese intent grammar and deterministic ranking. Reuses Product, public Catalog visibility, JWT account-status checking, existing API errors and React commerce adapters. No dependency, schema change, migration or external inference call. Existing Python/FastAPI Review Sentiment remains unchanged; it is not a tea recommendation model.

This is an explainable rule-based advisor plus content-based filtering, not a trained conversational LLM. Do not claim accuracy, confidence or a percentage of suitability. All returned product IDs, names, pack weights, prices, stock, categories and regions originate in MySQL. ACTIVE Product/Category/TeaRegion/Store and positive stock are required for suggestions. There is no example-product fallback. Cart and checkout still validate current price/stock.

## API
- GET /api/recommendations?productId=&profile=&limit=4 — public; seed ID optional, positive and public-visible; seed excluded from results. Limit 1–12. Profiles: STRONG, LOW_ASTRINGENCY, SWEET, AROMATIC, DAILY, GIFT. Unknown profile is 400; hidden/missing seed is 404.
- POST /api/recommendations/preferences?limit=4 — CUSTOMER only; JSON optional categoryId, regionId, minPrice, maxPrice, strength, astringency, aroma, aftertaste, purpose (DAILY/GIFT). Unknown fields rejected.
- POST /api/chatbot/advice — CUSTOMER only; JSON message (1–1000 characters), preferences optional, context optional. Both preference objects have the same validated contract. No userId, JWT, private history or arbitrary metadata accepted.

Advice response: method RULE_BASED_CATALOG_V1, reply, resolved preferences, notices, recommendations. Recommendation response: method CONTENT_BASED_V1, fallback, items with public ProductSummaryResponse, stockQuantity, score and reasons. Scores are ranking values, not probabilities. 400 validation/invalid price range; 404 no public products for an explicit category/region; 401 absent/invalid/locked-account JWT; 403 non-CUSTOMER on protected endpoints.

## Algorithm
Hard filters: category, region, inclusive price range and positive stock. Budgets are VND per product pack, never per kilogram. No result means empty response, never an automatic increase in budget.
Similarity weights: category .25, region .15, price .20, four existing taste levels .075 each, text overlap .10; purpose adds .10 when requested and only matches actual catalog text. Only requested/seed dimensions participate. Price similarity is min(price,target)/max(price,target). Taste similarity is 1 - abs(actual-desired)/4 on known levels 1–5. Text uses normalized Vietnamese tokens; no embeddings or training data. Missing taste values receive no match credit, never invented values. Score normalized by applicable weights; ties use productId descending.
Cold start/no usable match signal: explicitly flagged fallback, deterministic available products. No popularity claims. Inactive or out-of-stock products never enter fallback. Algorithm loads the public catalog in one fetch-join query; suitable for this small V1 catalog, O(n log n) ranking. Large-catalog retrieval/caching is future work.

Vietnamese parsing supports common strength/astringency/aroma/aftertaste phrases, simple negation, daily/gift intent, actual visible category/region names and explicit currency forms such as 300k, 250.000 đồng, 1,5 triệu, từ 100k đến 300k. Explicit form fields override text, new inferred preferences override previous context. A new budget replaces the previous budget range. Ambiguous language is not a general natural-language understanding model; use structured controls or start a new conversation. Gift/daily text is advisory, not a claim about packaging, health or product quality.

## Privacy and frontend
No chat, context, product-view event or identity is persisted. No API key, external prompt or request-content log. Bearer remains only in the Authorization header; recognizable Bearer/JWT strings pasted into messages are rejected. React renders text, not raw HTML. Memory holds at most eight exchanges; unmount/logout/new conversation clears it. Categories/regions are fetched through existing public APIs. AbortControllers prevent stale responses after navigation/reset.
React replaces the mock advisor and mock related products, connects Home taste selection and Product detail, and handles loading/errors/empty results. ProductCard is reused; fixes remove nested buttons, empty image URLs and the invented default review count. No payment/Admin business logic changed.

Existing JWT converter reloads the active user from MySQL on every request. Tests verify a previously issued token is rejected after an Admin locks the Customer across chatbot, preference recommendations, profile, Cart, Order, Address and Review. No auth patch was needed. As documented for customer management, an unexpired token may become valid again after unlocking; permanent token revocation is outside the current schema.

## Verified results
- Maven clean test: 49 tests PASS, zero failures/errors/skips; 187 production files compiled. Includes 12 algorithm/grammar unit tests, seven real-MySQL advisor tests (78 HTTP requests), and existing Admin/payment regression suites (45/60 HTTP requests).
- Fixture databases are newly named teasmart_advisor_test_*; all 19 tables cleaned and checked. Read-only concurrent advisor requests leave every fixture table unchanged. No database dropped or AUTO_INCREMENT reset; empty test schemas remain.
- Frontend: 10 advisor tests, five Admin tests, seven payment tests PASS; TypeScript and Vite production build PASS. Advisor tests use explicitly labeled transport doubles plus SSR/contract tests, not live-browser authenticated E2E.
- Main teasmart: 19 tables, four public in-stock products; three have taste levels. Three read-only HTTP requests through Vite proxy match every returned ID/name/price/stock/slug against SQL. Hibernate validate/startup PASS with read-only pool. Browser smoke verified Home taste selection, related-product cards and guest sign-in gate; no main account password or data changed.

Important: current main catalog includes pre-existing DEV products. They remain visibly named DEV and were not fabricated/renamed as business data. Populate approved real catalog descriptions/attributes through existing Admin APIs before production evaluation. No chatbot quality benchmark exists yet. Python sentiment models were not retrained, downloaded or changed in this work. No deployment performed.

## Run tests
Use the existing Maven wrapper and supply PAYMENT_TEST_SERVER_URL (localhost MySQL server URL without database), PAYMENT_TEST_USERNAME and PAYMENT_TEST_PASSWORD in the process environment, then run .\mvnw.cmd clean test. Credentials are never stored in this document/source. Frontend: npm run test:advisor, test:admin, test:payment, lint, build.
