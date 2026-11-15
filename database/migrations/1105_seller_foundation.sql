-- Phase 2 step 1. Manual, one-time additive migration; no legacy backfill.
-- Select and verify the target database before execution. Never auto-run at startup.
-- MySQL DDL commits independently: inspect partial completion before retrying.
ALTER TABLE shops
 ADD COLUMN catalog_store_id BIGINT UNSIGNED NULL,
 ADD COLUMN catalog_verified_by BIGINT UNSIGNED NULL,
 ADD COLUMN catalog_verified_at DATETIME NULL,
 ADD KEY ix_shop_catalog_store (catalog_store_id),
 ADD CONSTRAINT fk_shop_catalog_store FOREIGN KEY (catalog_store_id) REFERENCES stores(store_id),
 ADD CONSTRAINT fk_shop_catalog_verifier FOREIGN KEY (catalog_verified_by) REFERENCES users(user_id);
ALTER TABLE products
 ADD COLUMN shop_id BIGINT UNSIGNED NULL,
 ADD KEY ix_product_shop_status (shop_id,status,product_id),
 ADD CONSTRAINT fk_product_shop FOREIGN KEY (shop_id) REFERENCES shops(shop_id);
ALTER TABLE orders
 ADD COLUMN shop_id BIGINT UNSIGNED NULL,
 ADD COLUMN shop_name VARCHAR(150) NULL,
 ADD KEY ix_order_shop_created (shop_id,created_at,order_id),
 ADD KEY ix_order_shop_status (shop_id,order_status,created_at,order_id),
 ADD CONSTRAINT fk_order_shop FOREIGN KEY (shop_id) REFERENCES shops(shop_id);
ALTER TABLE checkout_sessions
 ADD COLUMN shop_id BIGINT UNSIGNED NULL,
 ADD COLUMN shop_name VARCHAR(150) NULL,
 ADD KEY ix_session_shop_status (shop_id,status,checkout_session_id),
 ADD CONSTRAINT fk_session_shop FOREIGN KEY (shop_id) REFERENCES shops(shop_id);
