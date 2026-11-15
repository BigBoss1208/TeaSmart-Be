-- Administrative isolation, not payment finality. No existing payment data is modified.
CREATE TABLE checkout_session_quarantine (
 checkout_session_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
 user_id BIGINT UNSIGNED NOT NULL,
 admin_user_id BIGINT UNSIGNED NOT NULL,
 reason VARCHAR(500) NOT NULL,
 cutoff_at DATETIME NOT NULL,
 previous_status VARCHAR(32) NOT NULL,
 previous_reservation_status VARCHAR(32) NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CONSTRAINT fk_quarantine_session FOREIGN KEY(checkout_session_id) REFERENCES checkout_sessions(checkout_session_id)
);
CREATE TABLE checkout_quarantine_callback (
 callback_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 checkout_session_id BIGINT UNSIGNED NOT NULL,
 payment_attempt_id BIGINT UNSIGNED NOT NULL,
 response_code VARCHAR(2), transaction_status VARCHAR(2), transaction_no VARCHAR(30), paid_at DATETIME,
 received_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 KEY idx_quarantine_callback_session(checkout_session_id, received_at),
 CONSTRAINT fk_quarantine_callback_session FOREIGN KEY(checkout_session_id) REFERENCES checkout_session_quarantine(checkout_session_id),
 CONSTRAINT fk_quarantine_callback_attempt FOREIGN KEY(payment_attempt_id) REFERENCES payment_attempts(payment_attempt_id)
);
