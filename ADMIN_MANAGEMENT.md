# Admin Dashboard and Customer Management

## API
All endpoints require ADMIN. CUSTOMER receives 403; missing/invalid JWT receives 401.
- GET /api/admin/dashboard?from=YYYY-MM-DD&to=YYYY-MM-DD
- GET /api/admin/customers?keyword=&status=&page=0&size=12
- GET /api/admin/customers/{userId}
- PATCH /api/admin/customers/{userId}/status with status ACTIVE or INACTIVE.

Revenue includes only PAID payments with paid_at in the inclusive date range, interpreted as Asia/Ho_Chi_Minh local DATETIME. Default range: 30 calendar days; maximum 366. Orders use created_at in that range. Product/customer counts are all-time. Customer spending is all-time PAID amounts with paid_at present. No order-item joins multiply revenue. Missing days are zero; no simulated statistics.

Customer search covers name/email/phone, treats wildcard punctuation literally and sorts userId descending. Only CUSTOMER accounts can be managed. Status changes lock User first, preserve existing account/transaction data, and are idempotent. Existing authentication checks account status on every JWT request. An unexpired token can become usable again after unlocking; permanent token revocation is outside this schema.

## Verification
Maven clean test: 30 tests PASS, zero failures/errors/skips; 176 production sources compiled. Admin real-MySQL suite: 5 tests, 45 HTTP requests; payment regression: 25 tests including 60 HTTP requests. Fixtures use newly named test databases, are removed after testing, and never modify teasmart or quiz_app. No database drop or AUTO_INCREMENT reset.
Frontend: 5 admin tests, 7 payment tests, TypeScript and production build. Tests cover loading rendering and reporting helpers; browser end-to-end testing is not included.

No migration is required. No payment, schema, Entity or existing checkout implementation changed. MySQL tests are opt-in with PAYMENT_TEST_SERVER_URL (localhost server URL only), PAYMENT_TEST_USERNAME and PAYMENT_TEST_PASSWORD supplied as environment variables; no credentials are stored in source.
