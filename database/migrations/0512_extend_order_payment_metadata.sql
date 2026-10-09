-- Manual migration. Stop writers; back up and verify schema on an isolated test DB first.
-- Apply once to the selected database. Never automatically run on application startup.
-- Existing columns, timestamps, stock and payment states are not modified.
ALTER TABLE orders
  ADD COLUMN checkout_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD COLUMN checkout_request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD COLUMN stock_released_at DATETIME NULL,
  ADD CONSTRAINT uk_orders_user_id_checkout_key UNIQUE (user_id, checkout_key);

ALTER TABLE payments
  ADD COLUMN gateway VARCHAR(20) NULL,
  ADD COLUMN merchant_reference VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD COLUMN expires_at DATETIME NULL,
  ADD COLUMN gateway_response_code VARCHAR(2) NULL,
  ADD COLUMN gateway_transaction_status VARCHAR(2) NULL,
  ADD COLUMN reconciliation_required BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN confirmed_by_admin_id BIGINT UNSIGNED NULL,
  ADD COLUMN last_reconciliation_at DATETIME NULL,
  ADD CONSTRAINT uk_payments_merchant_reference UNIQUE (merchant_reference),
  ADD INDEX idx_payments_status_expires_at (payment_status, expires_at),
  ADD INDEX idx_payments_confirmed_by_admin_id (confirmed_by_admin_id),
  ADD CONSTRAINT fk_payments_confirmed_by_admin_id
    FOREIGN KEY (confirmed_by_admin_id) REFERENCES users(user_id);
-- Intentionally no backfill of stock_released_at, paid_at, or confirmed_by_admin_id.
