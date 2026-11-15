-- Manual migration. Apply only after backup; no user data is changed.
CREATE TABLE password_reset_tokens (
 user_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
 token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 password_snapshot VARCHAR(255) NOT NULL,
 issued_at DATETIME(6) NOT NULL,
 expires_at DATETIME(6) NOT NULL,
 used_at DATETIME(6) NULL,
 CONSTRAINT fk_password_reset_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
) ENGINE=InnoDB;
