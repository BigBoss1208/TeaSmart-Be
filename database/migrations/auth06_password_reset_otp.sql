-- Additive migration. Apply only after backup and explicit approval on main.
-- Old link rows remain intact but cannot authorize reset: otp_verified_at is NULL.
ALTER TABLE password_reset_tokens
    ADD COLUMN otp_hash VARCHAR(60) NULL,
    ADD COLUMN otp_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN otp_verified_at DATETIME(6) NULL;
