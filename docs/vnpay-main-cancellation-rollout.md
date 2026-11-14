# VNPay cancellation policy for local main

Approved scope: localhost:3000 -> IntelliJ TeaSmartApplication:8080 -> existing teasmart on 3306.
The working copy had disabled the default final-failure policy. This rollout restores only 24:02. The repository HEAD already contained that default; the commit also records the deployment scope and rollback procedure. 51:02 is not enabled on main.

The existing callback implementation validates the signature, merchant, reference and amount before accepting cancellation. A released cancelled purchase cannot be fulfilled by a late charge; late conflicting evidence remains subject to reconciliation. Browser Back alone does not cancel payment. No bulk processing of historical sessions is performed by this configuration change.

## Preflight and backup

No schema change or migration is required for this policy change. Do not run migrations as part of this rollout. No main SQL UPDATE/DELETE was executed and no new database backup was created by this change.
Before a separately approved financial acceptance run, take a full consistent backup using existing private client credentials (never command-line passwords), record table counts and verify restore on an isolated instance. Never restore over the live database to undo a policy change.
Preserve the current private IntelliJ configuration and credentials. The existing Return URL is http://localhost:3000/payment/vnpay-return. Keep reconciliation scheduling unchanged.

## Activation and rollback

Stop TeaSmartApplication with IntelliJ, then Run the same saved configuration once. Existing settings remain port 8080 and DB_URL pointing to 3306/teasmart. Do not select checkout-sandbox on main.
After restart verify PID, startup logs, effective policy, JDBC destination and read-only GET APIs before any separately approved payment test.
To disable the cancellation pairs again, add the program argument --teasmart.vnpay.verified-final-failure-pairs= to that run configuration and restart. Remove it to restore the approved default. Rollback affects future processing only; never reverse released reservations or financial states by SQL.
Historical UNKNOWN/HELD sessions need individual verified evidence and separate approval; changing this default does not by itself resolve them. Existing reconciliation scheduling can still process authenticated gateway evidence under its current workflow.

## Validation, 2026-11-15

MySQL test identity checked: port 33377, checkout-demo/data, MySQL 8.0.45.
PaymentMysqlTest 22, VnpayGatewayTest 23, VnpayQueryClientTest 5: 50 passed, zero failures/errors/skips.
Frontend payment/checkout/recovery/reconciliation/selected-cart: 60 passed.
TypeScript, frontend production build and Maven package passed.
No real Sandbox acceptance or main payment was performed for this rollout. Runtime acceptance remains pending the user's IntelliJ restart.