-- Manual additive migration. Verify and apply only to an explicitly selected TEST database.
-- Never run this file against the main teasmart database.
-- Existing Order, Payment, OrderItem and stock values are left unchanged.

CREATE TABLE checkout_sessions (
  checkout_session_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  session_code VARCHAR(50) NOT NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  source_type VARCHAR(20) NOT NULL,
  recipient_name VARCHAR(100) NOT NULL,
  recipient_phone VARCHAR(20) NOT NULL,
  shipping_address VARCHAR(500) NOT NULL,
  note VARCHAR(500) NULL,
  subtotal DECIMAL(12,2) NOT NULL,
  shipping_fee DECIMAL(12,2) NOT NULL,
  total_amount DECIMAL(12,2) NOT NULL,
  status VARCHAR(32) NOT NULL,
  reservation_status VARCHAR(32) NOT NULL,
  expires_at DATETIME NOT NULL,
  reservation_expires_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (checkout_session_id),
  UNIQUE KEY uk_checkout_sessions_code (session_code),
  UNIQUE KEY uk_checkout_sessions_user_key (user_id, idempotency_key),
  KEY idx_checkout_sessions_status_expiry (status, reservation_expires_at),
  CONSTRAINT fk_checkout_sessions_user FOREIGN KEY (user_id) REFERENCES users(user_id)
) ENGINE=InnoDB;

CREATE TABLE checkout_session_items (
  checkout_session_item_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  checkout_session_id BIGINT UNSIGNED NOT NULL,
  product_id BIGINT UNSIGNED NOT NULL,
  source_cart_item_id BIGINT UNSIGNED NULL,
  product_name VARCHAR(200) NOT NULL,
  unit_price DECIMAL(12,2) NOT NULL,
  quantity INT UNSIGNED NOT NULL,
  subtotal DECIMAL(12,2) NOT NULL,
  PRIMARY KEY (checkout_session_item_id),
  UNIQUE KEY uk_checkout_session_items_session_product (checkout_session_id, product_id),
  KEY idx_checkout_session_items_product (product_id),
  CONSTRAINT fk_checkout_session_items_session FOREIGN KEY (checkout_session_id)
    REFERENCES checkout_sessions(checkout_session_id),
  CONSTRAINT fk_checkout_session_items_product FOREIGN KEY (product_id)
    REFERENCES products(product_id)
) ENGINE=InnoDB;

CREATE TABLE payment_attempts (
  payment_attempt_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  checkout_session_id BIGINT UNSIGNED NULL,
  legacy_order_id BIGINT UNSIGNED NULL,
  attempt_number INT UNSIGNED NOT NULL,
  gateway VARCHAR(20) NOT NULL,
  merchant_reference VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  amount DECIMAL(12,2) NOT NULL,
  status VARCHAR(24) NOT NULL,
  transaction_no VARCHAR(30) NULL,
  gateway_response_code VARCHAR(2) NULL,
  gateway_transaction_status VARCHAR(2) NULL,
  reconciliation_required BOOLEAN NOT NULL DEFAULT FALSE,
  last_reconciliation_at DATETIME NULL,
  paid_at DATETIME NULL,
  expires_at DATETIME NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (payment_attempt_id),
  UNIQUE KEY uk_payment_attempt_session_number (checkout_session_id, attempt_number),
  UNIQUE KEY uk_payment_attempt_session_key (checkout_session_id, idempotency_key),
  UNIQUE KEY uk_payment_attempt_merchant_reference (merchant_reference),
  UNIQUE KEY uk_payment_attempt_gateway_transaction (gateway, transaction_no),
  UNIQUE KEY uk_payment_attempt_legacy_order (legacy_order_id),
  KEY idx_payment_attempt_status_expiry (status, expires_at),
  CONSTRAINT fk_payment_attempt_session FOREIGN KEY (checkout_session_id)
    REFERENCES checkout_sessions(checkout_session_id),
  CONSTRAINT fk_payment_attempt_legacy_order FOREIGN KEY (legacy_order_id)
    REFERENCES orders(order_id),
  CONSTRAINT chk_payment_attempt_owner CHECK (
    (checkout_session_id IS NOT NULL AND legacy_order_id IS NULL)
    OR (checkout_session_id IS NULL AND legacy_order_id IS NOT NULL)
  )
) ENGINE=InnoDB;

ALTER TABLE orders
  ADD COLUMN checkout_session_id BIGINT UNSIGNED NULL,
  ADD UNIQUE KEY uk_orders_checkout_session (checkout_session_id),
  ADD CONSTRAINT fk_orders_checkout_session FOREIGN KEY (checkout_session_id)
    REFERENCES checkout_sessions(checkout_session_id);

-- Preserve one callback lookup row for each legacy VNPay Order without changing it.
INSERT INTO payment_attempts (
  checkout_session_id, legacy_order_id, attempt_number, gateway, merchant_reference,
  idempotency_key, amount, status, transaction_no, gateway_response_code,
  gateway_transaction_status, reconciliation_required, last_reconciliation_at,
  paid_at, expires_at, created_at, updated_at
)
SELECT NULL, p.order_id, 1, 'VNPAY', p.merchant_reference, NULL, p.amount,
  CASE p.payment_status
    WHEN 'PAID' THEN 'SUCCEEDED'
    WHEN 'FAILED' THEN 'FAILED_FINAL'
    WHEN 'PENDING' THEN 'PENDING'
    ELSE 'UNKNOWN'
  END,
  CASE WHEN p.transaction_code LIKE 'VNPAY:%' THEN SUBSTRING(p.transaction_code, 7) ELSE NULL END,
  p.gateway_response_code, p.gateway_transaction_status, p.reconciliation_required,
  p.last_reconciliation_at, p.paid_at, p.expires_at, p.created_at, p.updated_at
FROM payments p
WHERE p.gateway = 'VNPAY' AND p.merchant_reference IS NOT NULL;

-- Legacy PENDING attempts marked for reconciliation remain explicitly UNKNOWN.
UPDATE payment_attempts a
JOIN payments p ON p.order_id = a.legacy_order_id
SET a.status = 'UNKNOWN'
WHERE a.legacy_order_id IS NOT NULL
  AND p.payment_status = 'PENDING'
  AND p.reconciliation_required = TRUE;
