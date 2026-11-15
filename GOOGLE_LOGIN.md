# Google Login Backend V1 (AUTH-04B)

Google authentication is disabled by default. No frontend integration or account
linking is included in this checkpoint. Never start this source against the main
database until AUTH-04 migration has been separately approved/applied there.

## Configuration

- `GOOGLE_AUTH_ENABLED=true` to opt in.
- `GOOGLE_CLIENT_ID`: Web application client ID (also used by future GIS frontend).
- `GOOGLE_ALLOWED_ORIGINS`: exact comma-separated origins, e.g.
  `http://localhost:3000,https://shop.example.com`. No wildcard/trailing slash.
- Existing DB/JWT environment variables remain required. No client secret needed.

## API contract

1. `POST /api/auth/google/challenge` with an allowed `Origin` header. Returns
   `{nonce, expiresIn: 300}` and a HttpOnly, SameSite=Strict browser-binding cookie.
2. Pass the nonce to GIS `google.accounts.id.initialize`. GIS returns `credential`.
3. `POST /api/auth/google` as JSON `{idToken: credential}`, with the same browser
   cookie and allowed Origin. Returns the existing TeaSmart `AuthResponse`.

Use same-origin browser fetch. Nonce/binding are distinct 256-bit random values.
Cookies are Secure for HTTPS. Local HTTP is allowed only for localhost/loopback.
One outstanding challenge per binding; renewing invalidates the prior challenge.
Expiration/atomic consume are enforced on the server. Retry failed/unavailable
login by obtaining a new challenge; do not reuse tokens after successful login.
Responses use Cache-Control: no-store. Tokens/claims are never logged by this module.

Google Java API Client verifies signature, audience, issuer and expiration.
The adapter additionally verifies email_verified, sub, email and nonce. It bounds
each verification wait to six seconds, with two workers and four queued jobs;
underlying key-fetch workers may finish later, but queued work/memory remain bounded.

Returning Google identities are matched only by case-sensitive google_sub.
Only ACTIVE CUSTOMER accounts may use this API. New accounts have nullable password
and phone. Existing email conflicts return 409 GOOGLE_LINK_REQUIRED; V1 has no
linking endpoint. Email changes from Google never overwrite the existing local email.
ADMIN and INACTIVE accounts are rejected. Password-less accounts cannot password-login.

## Operational limits

Challenge storage is bounded to 1000 active challenges in one process. Expired
entries are removed on each operation. Restart invalidates challenges. Multiple
instances require shared storage; Redis is not part of V1. A distributed rate
limiter is not included. HTTPS and an appropriate CSP remain deployment requirements.

Tests use isolated databases and generated signing keys or a fake verifier. No
Google credentials, real Google tokens or main-database account fixtures are used.
