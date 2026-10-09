# TeaSmart COD + VNPay Sandbox

This implementation only generates VNPay Sandbox URLs and calls the Sandbox query endpoint.
Production payment endpoints are not configurable. No real merchant credentials are included.

## Database deployment

`database/migrations/0512_extend_order_payment_metadata.sql` adds exactly 11 columns
(8 payments, 3 orders), two unique constraints, an expiry index and the nullable Admin FK.
Applied to the main `teasmart` database on 2026-10-09 during the authorized follow-up, after a verified backup and isolated restoration/migration test.
`database/schema.sql` is the complete schema for an empty database, not an upgrade script.

Before manually applying the migration to a chosen database:

1. Stop writers, back up all 19 tables outside Git, and test restoration separately.
2. Inspect the live schema and confirm none of the new columns/constraints already exist.
3. Select the intended database explicitly in your SQL client. The migration itself has no USE statement.
4. Apply the migration once, then verify existing column values and AUTO_INCREMENT have not changed.
5. Start the new application and check Hibernate validate.

Do not backfill stock_released_at from CANCELLED or updated_at. Existing cancelled orders
remain cancelled with a null marker; application logic rejects them before any release.
No existing Payment is marked PAID. Existing reconciliation_required becomes false.
MySQL DDL commits implicitly: inspect a partial failure before recovery; do not blindly rerun.
Do not drop new columns after payment activity without a separate recovery plan.

The new source requires this migration before startup against your main database.
Automatic schema generation and SQL initialization remain disabled.

## Environment configuration

Keep existing DB_URL, DB_USERNAME, DB_PASSWORD and JWT environment variables.
Set these variables locally, without placing their real values in source, .env files in Git or logs:

| Variable | Value / purpose |
| --- | --- |
| VNPAY_ENABLED | true only when merchant configuration is available |
| VNPAY_TMN_CODE | Sandbox merchant identifier received from VNPay |
| VNPAY_HASH_SECRET | Sandbox merchant signing secret received from VNPay |
| VNPAY_RETURN_URL | frontend URL ending in /payment/vnpay-return |
| VNPAY_EXPIRY_MINUTES | 15 by default, allowed 5–30 |
| VNPAY_VERIFIED_FINAL_FAILURE_PAIRS | empty by default; see finality below |

Use Windows User environment variables or the IDE run configuration; restart the terminal/IDE
to inherit new values. Do not rotate merchant configuration while transactions are pending
without a plan to reconcile those transactions.

Register this public HTTPS IPN address with the Sandbox merchant:
`https://YOUR_BACKEND_HOST/api/payments/vnpay/ipn`.
Return can use localhost HTTP during development. IPN requires an externally reachable HTTPS
backend; browser access to localhost does not make your local backend reachable by VNPay.
Proxy client IP only through a trusted reverse proxy; do not trust arbitrary X-Forwarded-For.
QueryDr uses the local server IP in the request; confirm the merchant's deployed network requirements
before a real Sandbox trial.

VNPay configuration disabled or incomplete does not prevent COD or ordinary startup
on a migrated database. A new VNPay checkout returns 503 before any order/stock write.

## APIs

| Method/path | Authorization | Contract |
| --- | --- | --- |
| POST /api/orders | CUSTOMER | Existing shipping DTO; optional Idempotency-Key; COD OrderResponse |
| POST /api/orders/vnpay | CUSTOMER | Same shipping DTO; required Idempotency-Key; order/paymentUrl/expiresAt/replayed |
| GET /api/orders/{orderId}/payment | owner CUSTOMER | PaymentResponse |
| GET /api/orders/payment-status?reference=... | owner CUSTOMER | PaymentResponse for Return page |
| GET /api/payments/vnpay/ipn | public, gateway signature verified | Official RspCode/Message JSON |
| GET /api/admin/orders/{orderId}/payment | ADMIN | PaymentResponse |
| PATCH /api/admin/orders/{orderId}/payment/confirm-cod | ADMIN | Empty body; requires DELIVERED; idempotent 200 |
| POST /api/admin/orders/{orderId}/payment/reconcile | ADMIN | Empty body; gateway lookup outside DB transaction |

Shipping DTO: recipientName, recipientPhone (0 plus nine digits), shippingAddress, optional note.
Unknown JSON fields are rejected. Never send frontend amounts, payment status or user IDs.
Idempotency keys are 16–64 ASCII letters/digits/underscore/hyphen; UUID works.
Reusing a key with a different shipping request/payment method gives 409.
Initial checkout is 201; replay is 200 and never recreates an order or deducts stock.
On network uncertainty, retry the same key and exact request. Don't generate a new key.
Paid/expired/unresolved VNPay replay returns the existing order with no payable URL.

## State and stock

COD checkout creates PENDING Order/Payment. Admin fulfillment does not collect money.
Only Admin confirmation for DELIVERED records PAID, paid_at and confirmed_by_admin_id.
Repeated confirmation preserves timestamps and the first confirming Admin.

VNPay uses ONLINE + gateway VNPAY; one unique merchant reference per order.
Product prices and OrderItem snapshots come from the locked backend catalog.
Stock is deducted once at checkout. Payment amounts never come from the browser.
Verified payment success requires both gateway codes 00 and a valid payment timestamp,
matching merchant/reference/amount, and a nonzero gateway transaction ID.
transaction_code is namespaced VNPAY:<gateway transaction ID>. paid_at is the verified gateway time.
New payment timestamps use Asia/Ho_Chi_Minh; historic timestamps are not converted.

IPN duplicate processing is serialized under Order -> Payment locks.
Stock release adds Product ASC locks and is atomic with cancellation and Payment FAILED.
Customer cancellation still supports COD/PENDING; unresolved or PAID VNPay cancellation is refused.
Admin fulfillment supports COD/PENDING or VNPay/PAID without reconciliation exceptions.

### Finality and expiry

Expiry or a missing IPN never proves failure. Every minute a bounded job examines expired
VNPay PENDING transactions, with a six-minute retry interval per attempted transaction.
QueryDr HTTP runs outside database transactions, with connect/overall timeouts.
Signed query success must also have successful transaction status, reference, amount and type.
Timeout, query not-found, suspicious or unknown result keeps Payment PENDING, retains stock,
and records reconciliation_required/last_reconciliation_at. Admin can request reconciliation.

By default NO failure pair releases stock. Configure VNPAY_VERIFIED_FINAL_FAILURE_PAIRS only
after VNPay confirms that the exact response-code:transaction-status pair is final for this merchant.
The implementation accepts only explicit pairs from 09/10/11/12/13/24/51/65/75/79 with status 02;
an allowed pair is not itself proof of finality. Do not enable a policy merely to make a demo pass.
The isolated automated test exercises a controlled verified-failure policy; this is not a Sandbox claim.
Non-success QueryDr results currently remain unresolved rather than being reinterpreted as final failures.

Verified late success after CANCELLED records received money as PAID and requires reconciliation,
but never revives Order or re-deducts stock. Conflicting callbacks never downgrade PAID.
V1 has no refund API; these exceptions need manual resolution with the provider.
Callback metadata stores latest results, not a full event audit trail.

Revenue should sum verified PAID Payments once, grouped by paid_at and method/gateway.
Separate reconciled sale revenue from received-money exceptions (CANCELLED/reconciliation_required).
Never join OrderItems and sum the duplicated Payment amount or equate DELIVERED with paid.

## Frontend

React login/register use backend APIs; Bearer token lives in sessionStorage.
Public catalog and Customer Cart use real IDs and current backend prices.
Checkout persists the current idempotency request in sessionStorage across retries/reloads.
It offers COD and VNPay Sandbox only. The Return page ignores browser success claims,
reads owner-authorized Payment status, polls for up to one minute and supports manual refresh.
Admin Orders shows real payment state, next fulfillment transition, COD confirmation and reconciliation.

Vite development proxies /api to http://127.0.0.1:8080; override TEASMART_BACKEND_URL when needed.
Production hosting must route /api to Spring Boot and serve index.html for /payment/vnpay-return.
No Vite variable contains the signing secret. Keep frontend and backend repositories separate.
Other existing prototype Admin screens and AI/editorial pages are outside this payment integration.

## Automated verification

Backend offline: `.\mvnw.cmd test` runs signing, URL, malformed callback and QueryDr validation tests.
Real MySQL integration is opt-in and creates a unique EMPTY database with a strict test prefix:

```powershell
$env:PAYMENT_TEST_SERVER_URL = 'jdbc:mysql://127.0.0.1:3306/'
$env:PAYMENT_TEST_USERNAME = [Environment]::GetEnvironmentVariable('DB_USERNAME', 'User')
$env:PAYMENT_TEST_PASSWORD = [Environment]::GetEnvironmentVariable('DB_PASSWORD', 'User')
.\mvnw.cmd test
```

The test requires permission to CREATE DATABASE. It never selects teasmart or quiz_app.
It applies the migration to a baseline schema fixture, seeds synthetic users/products/orders,
runs HTTP/concurrency/rollback tests on a random port, and deletes only its own fixture rows.
It does not drop databases or reset AUTO_INCREMENT. Empty test schemas remain for inspection.
Passwords, signing keys and tokens for tests are generated in memory and never printed.
Without PAYMENT_TEST_SERVER_URL, MySQL tests are explicitly skipped, not counted as passed.

Frontend: `npm run test:payment`, `npm run lint`, `npm run build`.
The frontend tests use Node 24 type transformation; no real credentials or network payment.

## Real Sandbox acceptance still required

After configuring merchant credentials and HTTPS IPN, perform successful and failed payments,
Return/IPN ordering, retry notification and QueryDr tests using Sandbox test banking credentials.
Confirm finality policy and network requirements with VNPay. No real Sandbox transaction was
performed during this implementation, and no frontend browser E2E claim is made from adapter tests.

Official references:
- https://sandbox.vnpayment.vn/apis/docs/thanh-toan-pay/pay.html
- https://sandbox.vnpayment.vn/apis/docs/truy-van-hoan-tien/querydr%26refund.html
- https://sandbox.vnpayment.vn/apis/docs/bang-ma-loi/

## Verification completed (2026-10-09)

- Maven clean test: 167 main source files; 25 tests passed, 0 failed, 0 skipped.
- Real MySQL 8.0.45 integration: 9 tests and 60 HTTP requests on a new isolated test database; Hibernate validate and random-port Tomcat startup passed.
- Tests cover COD confirmation/replay, VNPay signing/lookup/IPN, callback concurrency, idempotent checkout, amount mismatch, ownership, controlled final-failure release, rollback, overflow, late success and unresolved reconciliation.
- All 19 test tables contained zero fixture rows after cleanup; no AUTO_INCREMENT reset or database drop. Earlier successful empty test schemas and one empty partial schema from an initial harness parsing failure remain.
- Frontend: 7 adapter/client tests passed, TypeScript check passed, Vite production build passed. Browser checkout E2E and real Sandbox transactions have not been performed.
- At the initial implementation checkpoint, main teasmart was not migrated. The authorized follow-up below applied the migration without changing existing values or user passwords.
- Ports 8080, 8081 and the final test server port were unoccupied at verification.
- Git diff check and changed/untracked source whitespace scan passed; no JWT/BCrypt/private-key pattern detected. Staging remains empty; no commit or push.

## Exact implementation file inventory

### Backend (31 files)

- `PAYMENT_SANDBOX.md`
- `database/migrations/0512_extend_order_payment_metadata.sql`
- `database/schema.sql`
- `pom.xml`
- `src/main/java/vn/teasmart/backend/config/PaymentSchedulingConfig.java`
- `src/main/java/vn/teasmart/backend/config/SecurityConfig.java`
- `src/main/java/vn/teasmart/backend/config/VnpaySettings.java`
- `src/main/java/vn/teasmart/backend/controller/OrderController.java`
- `src/main/java/vn/teasmart/backend/controller/PaymentController.java`
- `src/main/java/vn/teasmart/backend/dto/response/PaymentCheckoutResponse.java`
- `src/main/java/vn/teasmart/backend/dto/response/PaymentResponse.java`
- `src/main/java/vn/teasmart/backend/entity/Order.java`
- `src/main/java/vn/teasmart/backend/entity/Payment.java`
- `src/main/java/vn/teasmart/backend/exception/GlobalExceptionHandler.java`
- `src/main/java/vn/teasmart/backend/exception/PaymentException.java`
- `src/main/java/vn/teasmart/backend/repository/OrderRepository.java`
- `src/main/java/vn/teasmart/backend/repository/PaymentRepository.java`
- `src/main/java/vn/teasmart/backend/service/AdminOrderService.java`
- `src/main/java/vn/teasmart/backend/service/CheckoutIdentity.java`
- `src/main/java/vn/teasmart/backend/service/OrderService.java`
- `src/main/java/vn/teasmart/backend/service/OrderStockService.java`
- `src/main/java/vn/teasmart/backend/service/PaymentReconciliationService.java`
- `src/main/java/vn/teasmart/backend/service/PaymentService.java`
- `src/main/java/vn/teasmart/backend/service/PaymentTime.java`
- `src/main/java/vn/teasmart/backend/service/VnpayGateway.java`
- `src/main/java/vn/teasmart/backend/service/VnpayQueryClient.java`
- `src/main/resources/application.properties`
- `src/test/java/vn/teasmart/backend/payment/PaymentMysqlTest.java`
- `src/test/java/vn/teasmart/backend/payment/VnpayGatewayTest.java`
- `src/test/java/vn/teasmart/backend/payment/VnpayQueryClientTest.java`
- `src/test/resources/payment/baseline-schema.sql`

### Frontend (11 files)

- `package.json`
- `src/App.tsx`
- `src/lib/commerce.ts`
- `src/pages/Admin.tsx`
- `src/pages/AdminPayments.tsx`
- `src/pages/Auth.tsx`
- `src/pages/PaymentCheckout.tsx`
- `src/pages/PaymentReturn.tsx`
- `src/types.ts`
- `tests/payment.test.ts`
- `vite.config.ts`


## Authorized main-database follow-up (2026-10-09)

- Full 19-table backup stored outside both Git repositories under Windows LocalAppData/TeaSmart/backups/payment-aaf798a861bc4b3ebbf87fc295f8a35d. Backup size: 28,211 bytes.
- Backup restored to teasmart_payment_restore_aaf798a861bc4b3ebbf87fc295f8a35d. Original data, schema and AUTO_INCREMENT verified; migration rehearsed there before main apply.
- Per-table locks on teasmart controlled writers during backup and main migration. No global lock or quiz_app access.
- MySQL information_schema cached AUTO_INCREMENT initially differed from live counters; verification uses information_schema_stats_expiry=0 without altering counters.
- Both main ALTER statements completed. All original values across 19 tables and AUTO_INCREMENT unchanged, including after HTTP smoke.
- MySQL removed the redundant non-unique fk_orders_user_id index because UNIQUE(user_id,checkout_key) covers the FK; the foreign-key constraint remains intact.
- Main startup: Hibernate validate and Tomcat 8080 passed. Vite started on 3000. 29 HTTP/proxy smoke checks passed; no order/cart/payment mutations.
- JWT_SECRET_BASE64 and JWT_ISSUER were absent. Smoke used a random temporary signing key and issuer in process memory for role/ownership read tests against the two active existing users. No main account password reset.
- Successful Customer/Admin password login and COD order mutations were tested only on the isolated integration DB. Main invalid login returned 401; invalid empty checkout returned 400 before writes.
- Re-run: 25 Maven tests passed, 60 integration HTTP requests, 7 frontend tests passed, TypeScript and Vite build passed.
- Test servers stopped. Restore/schema-test databases retained; no DROP or AUTO_INCREMENT reset. Backup contains sensitive user data and stays outside Git.
- Browser UI E2E and real VNPay Sandbox transactions remain unverified. Configure persistent JWT environment values before normal startup; no VNPay credentials were requested.
- No business source changes, staging, commit, push or deployment in this follow-up.

## Local JWT and Git checkpoint acceptance (2026-10-09)

- JWT_SECRET_BASE64 is generated from 48 cryptographically random bytes and stored only in Windows User environment variables; JWT_ISSUER is teasmart-local. No signing key is written to source or Git. Restart the terminal/IDE to inherit them.
- Browser testing used a fresh teasmart_payment_browser_* database with synthetic catalog and accounts. Registration, real Customer/Admin password login, COD checkout and Admin transitions through DELIVERED were verified through UI.
- The browser tool timed out on the COD confirmation JavaScript dialog. Confirmation of the same fixture was verified through the authorized API (200/PAID); complete confirmation-button browser E2E remains limited by this tooling issue.
- All browser-test fixtures were deleted in dependency order; 19 tables were empty. No database drop or AUTO_INCREMENT reset.
- The local JWT signing key was rotated after browser testing to invalidate fixture tokens before returning to the main database.
- Main startup and 29 read-only/invalid-request smoke checks passed using persistent local JWT configuration. No main account password or existing data was changed.
- Final Maven clean test: 25 passed, 0 failed/skipped, with 60 HTTP integration requests. Frontend: 7 tests passed; TypeScript and production build passed.
- No real VNPay Sandbox transaction or deployment performed.
