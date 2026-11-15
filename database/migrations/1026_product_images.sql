-- Prepared only: NOT APPLIED to teasmart. Backup and separate apply approval required.
-- No USE/CREATE DATABASE. Verify selected database and existing metadata before apply.
-- No modification to existing products.image_url or other rows.
CREATE TABLE `product_images` (
 `image_id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 `product_id` BIGINT UNSIGNED NOT NULL,
 `image_url` VARCHAR(500) NOT NULL,
 `sort_order` TINYINT UNSIGNED NOT NULL,
 `created_at` DATETIME NOT NULL,
 PRIMARY KEY (`image_id`),
 UNIQUE KEY `uk_product_image_order` (`product_id`,`sort_order`),
 CONSTRAINT `fk_product_images_product` FOREIGN KEY (`product_id`) REFERENCES `products` (`product_id`)
) ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
