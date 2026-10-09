-- Manual migration: verify backup, duplicates and writers before executing once.
ALTER TABLE teasmart.reviews
    ADD CONSTRAINT uk_reviews_user_id_product_id
    UNIQUE (user_id, product_id);
