CREATE TABLE IF NOT EXISTS vnpay_checkout_audit (
    audit_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    recorded_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    source VARCHAR(12) NOT NULL,
    merchant_reference VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL,
    payment_attempt_id BIGINT NULL,
    checkout_session_id BIGINT NULL,
    verification_outcome VARCHAR(64) NOT NULL,
    processing_outcome VARCHAR(64) NOT NULL,
    gateway_response_code CHAR(2) NULL,
    gateway_transaction_status CHAR(2) NULL,
    http_status SMALLINT NULL,
    request_sent BOOLEAN NULL,
    elapsed_ms BIGINT NULL,
    KEY idx_vnpay_audit_reference (merchant_reference, audit_id),
    KEY idx_vnpay_audit_session (checkout_session_id, audit_id)
);
