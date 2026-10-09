-- Apply manually once, after backup, snapshot verification and writer checks.
-- Latest analysis only; no review text or automatic analysis backfill.
CREATE TABLE teasmart.review_ai_analysis (
  review_id BIGINT UNSIGNED NOT NULL,
  sentiment_label VARCHAR(20) NULL,
  confidence DECIMAL(6,5) NULL,
  needs_review BOOLEAN NULL,
  moderation_flags JSON NULL,
  analysis_method VARCHAR(20) NOT NULL,
  model_version VARCHAR(200) NULL,
  input_hash CHAR(64) NOT NULL,
  run_id CHAR(36) NOT NULL,
  processing_status VARCHAR(20) NOT NULL,
  error_code VARCHAR(50) NULL,
  analyzed_at DATETIME NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (review_id),
  CONSTRAINT fk_review_ai_analysis_review_id FOREIGN KEY (review_id) REFERENCES reviews(review_id)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
