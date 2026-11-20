-- =====================================================
-- GlobalPrice
-- MIGRATION 004 : 같은 상품 묶기 (다나와식)
--  - products.match_key       : 묶기 키 (예: iphone|17 pro|256). 같은 키면 같은 products 행을 씀
--  - mall_products.listing_title : 쇼핑몰에 올라온 원래 상품명 (판매처 줄에 표시)
--  - 01 · 02 · 03_migration_001 실행 후 적용. 기존 데이터는 지우지 않음
--  - 이미 있으면 아무것도 하지 않음 (여러 번 실행해도 안전)
--  - 기존 상품(1 리스팅 = 1 상품)은 다음 검색 때 서버가 같은 상품끼리 자동으로 합침
-- =====================================================

USE globalprice;

-- Workbench 의 안전 업데이트 모드(1175 오류) 대비
SET @old_safe := @@SQL_SAFE_UPDATES;
SET SQL_SAFE_UPDATES = 0;

-- products.match_key
SET @has_key := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'products' AND COLUMN_NAME = 'match_key');
SET @ddl := IF(@has_key = 0,
    'ALTER TABLE products ADD COLUMN match_key VARCHAR(255) NULL AFTER model_number, ADD CONSTRAINT uq_products_match_key UNIQUE (match_key)',
    'SELECT ''products.match_key already exists'' AS migration_002');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- mall_products.listing_title
SET @has_title := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mall_products' AND COLUMN_NAME = 'listing_title');
SET @ddl := IF(@has_title = 0,
    'ALTER TABLE mall_products ADD COLUMN listing_title VARCHAR(255) NULL AFTER external_product_id',
    'SELECT ''mall_products.listing_title already exists'' AS migration_002');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 기존 리스팅은 지금 상품명을 원래 상품명으로 채워 둠 (아직 1 리스팅 = 1 상품이라 같은 값)
UPDATE mall_products mp JOIN products p ON p.product_id = mp.product_id
SET mp.listing_title = p.product_name
WHERE mp.listing_title IS NULL;

SET SQL_SAFE_UPDATES = @old_safe;

SHOW COLUMNS FROM products LIKE 'match_key';
SHOW COLUMNS FROM mall_products LIKE 'listing_title';
