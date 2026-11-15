-- Test-first manual migration. Requires 1105. No production application in step 2.
ALTER TABLE products
 ADD COLUMN seller_listing_status VARCHAR(20) NULL,
 ADD CONSTRAINT ck_seller_private_product CHECK (shop_id IS NULL OR status='INACTIVE'),
 ADD CONSTRAINT ck_seller_listing CHECK (seller_listing_status IS NULL OR seller_listing_status IN ('ACTIVE','INACTIVE'));
CREATE TABLE seller_stock_adjustments (
 adjustment_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 shop_id BIGINT UNSIGNED NOT NULL,
 product_id BIGINT UNSIGNED NOT NULL,
 actor_user_id BIGINT UNSIGNED NOT NULL,
 request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 delta BIGINT NOT NULL,
 reason VARCHAR(500) NOT NULL,
 stock_after BIGINT UNSIGNED NOT NULL,
 created_at DATETIME NOT NULL,
 UNIQUE KEY uk_seller_stock_key(shop_id,request_key),
 CONSTRAINT fk_stock_shop FOREIGN KEY(shop_id) REFERENCES shops(shop_id),
 CONSTRAINT fk_stock_product FOREIGN KEY(product_id) REFERENCES products(product_id),
 CONSTRAINT fk_stock_actor FOREIGN KEY(actor_user_id) REFERENCES users(user_id)
) ENGINE=InnoDB;
