-- Apply manually once, after backup and writer checks. Already applied locally in D2.
CREATE TABLE teasmart.review_images (
  review_image_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  review_id BIGINT UNSIGNED NOT NULL,
  storage_key VARCHAR(255) NOT NULL,
  content_type VARCHAR(30) NOT NULL,
  file_size BIGINT UNSIGNED NOT NULL,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (review_image_id),
  CONSTRAINT uk_review_images_storage_key UNIQUE (storage_key),
  INDEX idx_review_images_review_id_review_image_id (review_id, review_image_id),
  CONSTRAINT fk_review_images_review_id FOREIGN KEY (review_id) REFERENCES reviews(review_id)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
