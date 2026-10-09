CREATE TABLE `users` (
  `user_id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `full_name` VARCHAR(100) NOT NULL,
  `email` VARCHAR(255) NOT NULL,
  `password_hash` VARCHAR(255) NOT NULL,
  `phone` VARCHAR(20) NULL,
  `role` VARCHAR(20) NOT NULL,
  `status` VARCHAR(20) NOT NULL,
  `avatar_url` VARCHAR(500) NULL,
  `created_at` DATETIME NOT NULL,
  `updated_at` DATETIME NOT NULL,
  PRIMARY KEY (`user_id`),
  CONSTRAINT `uk_users_email` UNIQUE (`email`)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE `addresses` (
  `address_id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `recipient_name` VARCHAR(100) NOT NULL,
  `phone` VARCHAR(20) NOT NULL,
  `province` VARCHAR(100) NOT NULL,
  `district` VARCHAR(100) NOT NULL,
  `ward` VARCHAR(100) NOT NULL,
  `detail_address` VARCHAR(255) NOT NULL,
  `is_default` BOOLEAN NOT NULL,
  `created_at` DATETIME NOT NULL,
  `updated_at` DATETIME NOT NULL,
  PRIMARY KEY (`address_id`),
  CONSTRAINT `fk_addresses_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE `categories` (
  `category_id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(100) NOT NULL,
  `slug` VARCHAR(150) NOT NULL,
  `description` TEXT NULL,
  `status` VARCHAR(20) NOT NULL,
  `created_at` DATETIME NOT NULL,
  `updated_at` DATETIME NOT NULL,
  PRIMARY KEY (`category_id`),
  CONSTRAINT `uk_categories_name` UNIQUE (`name`),
  CONSTRAINT `uk_categories_slug` UNIQUE (`slug`)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE `tea_regions` (
  `region_id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(150) NOT NULL,
  `description` TEXT NULL,
  `location` VARCHAR(255) NULL,
  `image_url` VARCHAR(500) NULL,
  `status` VARCHAR(20) NOT NULL,
  `created_at` DATETIME NOT NULL,
  `updated_at` DATETIME NOT NULL,
  PRIMARY KEY (`region_id`),
  CONSTRAINT `uk_tea_regions_name` UNIQUE (`name`)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE `stores` (
  `store_id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(150) NOT NULL,
  `description` TEXT NULL,
  `address` VARCHAR(255) NULL,
  `phone` VARCHAR(20) NULL,
  `email` VARCHAR(255) NULL,
  `logo_url` VARCHAR(500) NULL,
  `status` VARCHAR(20) NOT NULL,
  `created_at` DATETIME NOT NULL,
  `updated_at` DATETIME NOT NULL,
  PRIMARY KEY (`store_id`)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE `products` (
  `product_id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `category_id` BIGINT UNSIGNED NOT NULL,
  `region_id` BIGINT UNSIGNED NOT NULL,
  `store_id` BIGINT UNSIGNED NOT NULL,
  `name` VARCHAR(200) NOT NULL,
  `slug` VARCHAR(250) NOT NULL,
  `description` TEXT NULL,
  `price` DECIMAL(12,2) NOT NULL,
  `stock_quantity` INT UNSIGNED NOT NULL,
  `weight_grams` INT UNSIGNED NOT NULL,
  `image_url` VARCHAR(500) NULL,
  `taste_note` VARCHAR(500) NULL,
  `strength_level` TINYINT UNSIGNED NULL,
  `astringency_level` TINYINT UNSIGNED NULL,
  `aroma_level` TINYINT UNSIGNED NULL,
  `aftertaste_level` TINYINT UNSIGNED NULL,
  `status` VARCHAR(20) NOT NULL,
  `created_at` DATETIME NOT NULL,
  `updated_at` DATETIME NOT NULL,
  PRIMARY KEY (`product_id`),
  CONSTRAINT `uk_products_slug` UNIQUE (`slug`),
  CONSTRAINT `fk_products_category_id` FOREIGN KEY (`category_id`) REFERENCES `categories` (`category_id`),
  CONSTRAINT `fk_products_region_id` FOREIGN KEY (`region_id`) REFERENCES `tea_regions` (`region_id`),
  CONSTRAINT `fk_products_store_id` FOREIGN KEY (`store_id`) REFERENCES `stores` (`store_id`)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE `carts` (
  `cart_id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `created_at` DATETIME NOT NULL,
  `updated_at` DATETIME NOT NULL,
  PRIMARY KEY (`cart_id`),
  CONSTRAINT `uk_carts_user_id` UNIQUE (`user_id`),
  CONSTRAINT `fk_carts_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE `cart_items` (
  `cart_item_id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `cart_id` BIGINT UNSIGNED NOT NULL,
  `product_id` BIGINT UNSIGNED NOT NULL,
  `quantity` INT UNSIGNED NOT NULL,
  `created_at` DATETIME NOT NULL,
  `updated_at` DATETIME NOT NULL,
  PRIMARY KEY (`cart_item_id`),
  CONSTRAINT `uk_cart_items_cart_id_product_id` UNIQUE (`cart_id`, `product_id`),
  CONSTRAINT `fk_cart_items_cart_id` FOREIGN KEY (`cart_id`) REFERENCES `carts` (`cart_id`),
  CONSTRAINT `fk_cart_items_product_id` FOREIGN KEY (`product_id`) REFERENCES `products` (`product_id`)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE `wishlists` (
  `wishlist_id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `product_id` BIGINT UNSIGNED NOT NULL,
  `created_at` DATETIME NOT NULL,
  PRIMARY KEY (`wishlist_id`),
  CONSTRAINT `uk_wishlists_user_id_product_id` UNIQUE (`user_id`, `product_id`),
  CONSTRAINT `fk_wishlists_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`),
  CONSTRAINT `fk_wishlists_product_id` FOREIGN KEY (`product_id`) REFERENCES `products` (`product_id`)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE `orders` (
  `order_id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `order_code` VARCHAR(50) NOT NULL,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `recipient_name` VARCHAR(100) NOT NULL,
  `recipient_phone` VARCHAR(20) NOT NULL,
  `shipping_address` VARCHAR(500) NOT NULL,
  `subtotal` DECIMAL(12,2) NOT NULL,
  `shipping_fee` DECIMAL(12,2) NOT NULL,
  `total_amount` DECIMAL(12,2) NOT NULL,
  `order_status` VARCHAR(30) NOT NULL,
  `note` VARCHAR(500) NULL,
  `created_at` DATETIME NOT NULL,
  `updated_at` DATETIME NOT NULL,
  PRIMARY KEY (`order_id`),
  CONSTRAINT `uk_orders_order_code` UNIQUE (`order_code`),
  CONSTRAINT `fk_orders_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE `order_items` (
  `order_item_id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `order_id` BIGINT UNSIGNED NOT NULL,
  `product_id` BIGINT UNSIGNED NOT NULL,
  `product_name` VARCHAR(200) NOT NULL,
  `unit_price` DECIMAL(12,2) NOT NULL,
  `quantity` INT UNSIGNED NOT NULL,
  `subtotal` DECIMAL(12,2) NOT NULL,
  PRIMARY KEY (`order_item_id`),
  CONSTRAINT `fk_order_items_order_id` FOREIGN KEY (`order_id`) REFERENCES `orders` (`order_id`),
  CONSTRAINT `fk_order_items_product_id` FOREIGN KEY (`product_id`) REFERENCES `products` (`product_id`)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE `payments` (
  `payment_id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `order_id` BIGINT UNSIGNED NOT NULL,
  `payment_method` VARCHAR(30) NOT NULL,
  `amount` DECIMAL(12,2) NOT NULL,
  `payment_status` VARCHAR(30) NOT NULL,
  `transaction_code` VARCHAR(100) NULL,
  `paid_at` DATETIME NULL,
  `created_at` DATETIME NOT NULL,
  `updated_at` DATETIME NOT NULL,
  PRIMARY KEY (`payment_id`),
  CONSTRAINT `uk_payments_order_id` UNIQUE (`order_id`),
  CONSTRAINT `uk_payments_transaction_code` UNIQUE (`transaction_code`),
  CONSTRAINT `fk_payments_order_id` FOREIGN KEY (`order_id`) REFERENCES `orders` (`order_id`)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE `reviews` (
  `review_id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `product_id` BIGINT UNSIGNED NOT NULL,
  `order_item_id` BIGINT UNSIGNED NOT NULL,
  `rating` TINYINT UNSIGNED NOT NULL,
  `comment` TEXT NULL,
  `status` VARCHAR(20) NOT NULL,
  `created_at` DATETIME NOT NULL,
  `updated_at` DATETIME NOT NULL,
  PRIMARY KEY (`review_id`),
  CONSTRAINT `uk_reviews_order_item_id` UNIQUE (`order_item_id`),
  CONSTRAINT `uk_reviews_user_id_product_id` UNIQUE (`user_id`, `product_id`),
  CONSTRAINT `fk_reviews_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`),
  CONSTRAINT `fk_reviews_product_id` FOREIGN KEY (`product_id`) REFERENCES `products` (`product_id`),
  CONSTRAINT `fk_reviews_order_item_id` FOREIGN KEY (`order_item_id`) REFERENCES `order_items` (`order_item_id`)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE `review_images` (
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

CREATE TABLE review_ai_analysis (
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

CREATE TABLE `product_views` (
  `view_id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `product_id` BIGINT UNSIGNED NOT NULL,
  `viewed_at` DATETIME NOT NULL,
  PRIMARY KEY (`view_id`),
  CONSTRAINT `fk_product_views_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`),
  CONSTRAINT `fk_product_views_product_id` FOREIGN KEY (`product_id`) REFERENCES `products` (`product_id`)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE `chat_sessions` (
  `session_id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `created_at` DATETIME NOT NULL,
  `updated_at` DATETIME NOT NULL,
  PRIMARY KEY (`session_id`),
  CONSTRAINT `fk_chat_sessions_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE `chat_messages` (
  `message_id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `session_id` BIGINT UNSIGNED NOT NULL,
  `sender` VARCHAR(20) NOT NULL,
  `content` TEXT NOT NULL,
  `created_at` DATETIME NOT NULL,
  PRIMARY KEY (`message_id`),
  CONSTRAINT `fk_chat_messages_session_id` FOREIGN KEY (`session_id`) REFERENCES `chat_sessions` (`session_id`)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE `leaf_classifications` (
  `classification_id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT UNSIGNED NULL,
  `image_url` VARCHAR(500) NOT NULL,
  `predicted_class` VARCHAR(100) NOT NULL,
  `confidence` DECIMAL(5,4) NULL,
  `created_at` DATETIME NOT NULL,
  PRIMARY KEY (`classification_id`),
  CONSTRAINT `fk_leaf_classifications_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
