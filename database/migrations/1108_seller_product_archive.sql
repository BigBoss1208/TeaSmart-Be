-- Manual additive migration. Requires 1105/1106. Test first; never auto-run on teasmart.
-- Existing rows remain unarchived. Existing Seller status constraints are unchanged.
ALTER TABLE products
 ADD COLUMN seller_archived_at DATETIME NULL,
 ADD COLUMN seller_archived_by BIGINT UNSIGNED NULL,
 ADD KEY ix_seller_product_archive (shop_id,seller_archived_at,product_id),
 ADD CONSTRAINT fk_seller_product_archive_actor FOREIGN KEY (seller_archived_by) REFERENCES users(user_id),
 ADD CONSTRAINT ck_seller_product_archive CHECK (
   (seller_archived_at IS NULL AND seller_archived_by IS NULL) OR
   (seller_archived_at IS NOT NULL AND seller_archived_by IS NOT NULL AND shop_id IS NOT NULL
    AND seller_listing_status IS NOT NULL AND seller_listing_status='INACTIVE')
 );
