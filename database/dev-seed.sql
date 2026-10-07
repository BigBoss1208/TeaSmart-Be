-- DEVELOPMENT / TEST DATA ONLY
-- Synthetic local fixtures; not real products or research/experimental data.
-- Run manually against local MySQL only. Never loaded by Spring Boot.
-- Fixed IDs 55001..55007 belong to this fixture set.
-- Existing matching identity is skipped; an ID/UNIQUE collision fails rather than overwriting.
-- No UPDATE/DELETE/DROP/TRUNCATE. Run with mysql default stop-on-error (no --force).
USE teasmart;
START TRANSACTION;

INSERT INTO categories (category_id, name, slug, description, status, created_at, updated_at)
SELECT 55001, 'DEV Category B', 'dev-055d-category-1', 'DEVELOPMENT / TEST DATA ONLY', 'ACTIVE', '2026-10-07 12:00:00', '2026-10-07 12:00:00'
WHERE NOT EXISTS (SELECT 1 FROM categories WHERE category_id = 55001 AND name = 'DEV Category B' AND slug = 'dev-055d-category-1');

INSERT INTO categories (category_id, name, slug, description, status, created_at, updated_at)
SELECT 55002, 'DEV Category A', 'dev-055d-category-2', 'DEVELOPMENT / TEST DATA ONLY', 'ACTIVE', '2026-10-07 12:00:00', '2026-10-07 12:00:00'
WHERE NOT EXISTS (SELECT 1 FROM categories WHERE category_id = 55002 AND name = 'DEV Category A' AND slug = 'dev-055d-category-2');

INSERT INTO categories (category_id, name, slug, description, status, created_at, updated_at)
SELECT 55003, 'DEV Category Hidden', 'dev-055d-category-3', 'DEVELOPMENT / TEST DATA ONLY', 'INACTIVE', '2026-10-07 12:00:00', '2026-10-07 12:00:00'
WHERE NOT EXISTS (SELECT 1 FROM categories WHERE category_id = 55003 AND name = 'DEV Category Hidden' AND slug = 'dev-055d-category-3');

INSERT INTO tea_regions (region_id, name, description, status, created_at, updated_at)
SELECT 55001, 'DEV Region B', 'DEVELOPMENT / TEST DATA ONLY', 'ACTIVE', '2026-10-07 12:00:00', '2026-10-07 12:00:00'
WHERE NOT EXISTS (SELECT 1 FROM tea_regions WHERE region_id = 55001 AND name = 'DEV Region B');

INSERT INTO tea_regions (region_id, name, description, status, created_at, updated_at)
SELECT 55002, 'DEV Region A', 'DEVELOPMENT / TEST DATA ONLY', 'ACTIVE', '2026-10-07 12:00:00', '2026-10-07 12:00:00'
WHERE NOT EXISTS (SELECT 1 FROM tea_regions WHERE region_id = 55002 AND name = 'DEV Region A');

INSERT INTO tea_regions (region_id, name, description, status, created_at, updated_at)
SELECT 55003, 'DEV Region Hidden', 'DEVELOPMENT / TEST DATA ONLY', 'INACTIVE', '2026-10-07 12:00:00', '2026-10-07 12:00:00'
WHERE NOT EXISTS (SELECT 1 FROM tea_regions WHERE region_id = 55003 AND name = 'DEV Region Hidden');

INSERT INTO stores (store_id, name, description, status, created_at, updated_at)
SELECT 55001, 'DEV Store B', 'DEVELOPMENT / TEST DATA ONLY', 'ACTIVE', '2026-10-07 12:00:00', '2026-10-07 12:00:00'
WHERE NOT EXISTS (SELECT 1 FROM stores WHERE store_id = 55001 AND name = 'DEV Store B');

INSERT INTO stores (store_id, name, description, status, created_at, updated_at)
SELECT 55002, 'DEV Store A', 'DEVELOPMENT / TEST DATA ONLY', 'ACTIVE', '2026-10-07 12:00:00', '2026-10-07 12:00:00'
WHERE NOT EXISTS (SELECT 1 FROM stores WHERE store_id = 55002 AND name = 'DEV Store A');

INSERT INTO stores (store_id, name, description, status, created_at, updated_at)
SELECT 55003, 'DEV Store Hidden', 'DEVELOPMENT / TEST DATA ONLY', 'INACTIVE', '2026-10-07 12:00:00', '2026-10-07 12:00:00'
WHERE NOT EXISTS (SELECT 1 FROM stores WHERE store_id = 55003 AND name = 'DEV Store Hidden');

INSERT INTO products (product_id, category_id, region_id, store_id, name, slug, description, price, stock_quantity, weight_grams, taste_note, strength_level, astringency_level, aroma_level, aftertaste_level, status, created_at, updated_at)
SELECT 55001, 55001, 55001, 55001, 'DEV Green One', 'dev-055d-public-one', 'DEVELOPMENT / TEST DATA ONLY', 100000.00, 20, 100, 'Synthetic test taste note', 2, 1, 3, 2, 'ACTIVE', '2026-10-07 12:00:00', '2026-10-07 12:00:00'
WHERE NOT EXISTS (SELECT 1 FROM products WHERE product_id = 55001 AND slug = 'dev-055d-public-one');

INSERT INTO products (product_id, category_id, region_id, store_id, name, slug, description, price, stock_quantity, weight_grams, taste_note, strength_level, astringency_level, aroma_level, aftertaste_level, status, created_at, updated_at)
SELECT 55002, 55002, 55002, 55002, 'DEV Green Two', 'dev-055d-public-two', 'DEVELOPMENT / TEST DATA ONLY', 110000.00, 20, 100, 'Synthetic test taste note', 2, 1, 3, 2, 'ACTIVE', '2026-10-07 12:00:00', '2026-10-07 12:00:00'
WHERE NOT EXISTS (SELECT 1 FROM products WHERE product_id = 55002 AND slug = 'dev-055d-public-two');

INSERT INTO products (product_id, category_id, region_id, store_id, name, slug, description, price, stock_quantity, weight_grams, taste_note, strength_level, astringency_level, aroma_level, aftertaste_level, status, created_at, updated_at)
SELECT 55003, 55001, 55002, 55001, 'DEV Black Three', 'dev-055d-public-three', 'DEVELOPMENT / TEST DATA ONLY', 120000.00, 20, 100, 'Synthetic test taste note', 2, 1, 3, 2, 'ACTIVE', '2026-10-07 12:00:00', '2026-10-07 12:00:00'
WHERE NOT EXISTS (SELECT 1 FROM products WHERE product_id = 55003 AND slug = 'dev-055d-public-three');

INSERT INTO products (product_id, category_id, region_id, store_id, name, slug, description, price, stock_quantity, weight_grams, taste_note, strength_level, astringency_level, aroma_level, aftertaste_level, status, created_at, updated_at)
SELECT 55004, 55001, 55001, 55001, 'DEV Hidden Product', 'dev-055d-inactive-product', 'DEVELOPMENT / TEST DATA ONLY', 130000.00, 20, 100, 'Synthetic test taste note', 2, 1, 3, 2, 'INACTIVE', '2026-10-07 12:00:00', '2026-10-07 12:00:00'
WHERE NOT EXISTS (SELECT 1 FROM products WHERE product_id = 55004 AND slug = 'dev-055d-inactive-product');

INSERT INTO products (product_id, category_id, region_id, store_id, name, slug, description, price, stock_quantity, weight_grams, taste_note, strength_level, astringency_level, aroma_level, aftertaste_level, status, created_at, updated_at)
SELECT 55005, 55003, 55001, 55001, 'DEV Hidden Category', 'dev-055d-inactive-category', 'DEVELOPMENT / TEST DATA ONLY', 140000.00, 20, 100, 'Synthetic test taste note', 2, 1, 3, 2, 'ACTIVE', '2026-10-07 12:00:00', '2026-10-07 12:00:00'
WHERE NOT EXISTS (SELECT 1 FROM products WHERE product_id = 55005 AND slug = 'dev-055d-inactive-category');

INSERT INTO products (product_id, category_id, region_id, store_id, name, slug, description, price, stock_quantity, weight_grams, taste_note, strength_level, astringency_level, aroma_level, aftertaste_level, status, created_at, updated_at)
SELECT 55006, 55001, 55003, 55001, 'DEV Hidden Region', 'dev-055d-inactive-region', 'DEVELOPMENT / TEST DATA ONLY', 150000.00, 20, 100, 'Synthetic test taste note', 2, 1, 3, 2, 'ACTIVE', '2026-10-07 12:00:00', '2026-10-07 12:00:00'
WHERE NOT EXISTS (SELECT 1 FROM products WHERE product_id = 55006 AND slug = 'dev-055d-inactive-region');

INSERT INTO products (product_id, category_id, region_id, store_id, name, slug, description, price, stock_quantity, weight_grams, taste_note, strength_level, astringency_level, aroma_level, aftertaste_level, status, created_at, updated_at)
SELECT 55007, 55001, 55001, 55003, 'DEV Hidden Store', 'dev-055d-inactive-store', 'DEVELOPMENT / TEST DATA ONLY', 160000.00, 20, 100, 'Synthetic test taste note', 2, 1, 3, 2, 'ACTIVE', '2026-10-07 12:00:00', '2026-10-07 12:00:00'
WHERE NOT EXISTS (SELECT 1 FROM products WHERE product_id = 55007 AND slug = 'dev-055d-inactive-store');

COMMIT;
