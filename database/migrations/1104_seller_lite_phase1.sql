-- Seller Lite phase 1: additive only. Run against an explicitly selected, verified database.
-- No USE, no existing data changes, no changes to migration 1027.
-- One-time migration: an existing table causes an error, never silently accepts a different schema.
CREATE TABLE seller_applications (
 application_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 applicant_user_id BIGINT UNSIGNED NOT NULL,
 shop_name VARCHAR(150) NOT NULL,
 representative_name VARCHAR(100) NOT NULL,
 phone VARCHAR(20) NOT NULL,
 business_address VARCHAR(500) NOT NULL,
 description TEXT NOT NULL,
 status VARCHAR(20) NOT NULL,
 pending_applicant_id BIGINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN status='PENDING' THEN applicant_user_id ELSE NULL END) STORED,
 reviewer_user_id BIGINT UNSIGNED NULL,
 rejection_reason VARCHAR(1000) NULL,
 created_at DATETIME NOT NULL,
 reviewed_at DATETIME NULL,
 PRIMARY KEY(application_id),
 UNIQUE KEY uk_seller_pending_applicant(pending_applicant_id),
 KEY ix_seller_application_status(status,application_id),
 KEY ix_seller_application_user(applicant_user_id,application_id),
 CONSTRAINT fk_seller_application_user FOREIGN KEY(applicant_user_id) REFERENCES users(user_id),
 CONSTRAINT fk_seller_application_reviewer FOREIGN KEY(reviewer_user_id) REFERENCES users(user_id),
 CONSTRAINT ck_seller_application_status CHECK(status IN ('PENDING','APPROVED','REJECTED')),
 CONSTRAINT ck_seller_application_review CHECK(
 (status='PENDING' AND reviewer_user_id IS NULL AND reviewed_at IS NULL AND rejection_reason IS NULL)
 OR (status='APPROVED' AND reviewer_user_id IS NOT NULL AND reviewed_at IS NOT NULL AND rejection_reason IS NULL)
 OR (status='REJECTED' AND reviewer_user_id IS NOT NULL AND reviewed_at IS NOT NULL AND CHAR_LENGTH(TRIM(rejection_reason))>0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE shops (
 shop_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 owner_user_id BIGINT UNSIGNED NOT NULL,
 approved_application_id BIGINT UNSIGNED NOT NULL,
 name VARCHAR(150) NOT NULL,
 representative_name VARCHAR(100) NOT NULL,
 phone VARCHAR(20) NOT NULL,
 business_address VARCHAR(500) NOT NULL,
 description TEXT NOT NULL,
 logo_url VARCHAR(500) NULL,
 status VARCHAR(20) NOT NULL,
 status_reason VARCHAR(1000) NULL,
 status_changed_by BIGINT UNSIGNED NOT NULL,
 created_at DATETIME NOT NULL,
 updated_at DATETIME NOT NULL,
 PRIMARY KEY(shop_id),
 UNIQUE KEY uk_shop_owner(owner_user_id),
 UNIQUE KEY uk_shop_application(approved_application_id),
 KEY ix_shop_status(status,shop_id),
 CONSTRAINT fk_shop_owner FOREIGN KEY(owner_user_id) REFERENCES users(user_id),
 CONSTRAINT fk_shop_application FOREIGN KEY(approved_application_id) REFERENCES seller_applications(application_id),
 CONSTRAINT fk_shop_status_actor FOREIGN KEY(status_changed_by) REFERENCES users(user_id),
 CONSTRAINT ck_shop_status CHECK(status IN ('ACTIVE','SUSPENDED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
