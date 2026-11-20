-- =====================================================
-- GlobalPrice
-- MIGRATION 003 : mall_product_variants.normalized_option_key (쇼핑몰 간 같은 옵션 비교용)
--  - 01 → 02 → 03_migration_001 실행 후 적용. 05_migration_002 와 순서 상관없음
--  - ★ 03_seed_data.sql 을 쓸 거면 반드시 03 을 먼저 실행
--      03 은 mall_product_variants 에 컬럼 이름 없이 값 7개로 INSERT 하므로,
--      이 컬럼이 생긴 뒤(8개)에 실행하면 "Column count doesn't match" 오류로 멈춤
--  - 기존 데이터는 지우지 않음 (컬럼 · 인덱스 · CHECK 추가 + 옵션 없는 행만 'default' 로 채움)
--  - 컬럼 · 인덱스 · CHECK 가 이미 있으면 건너뜀 (여러 번 실행해도 안전)
--
-- 용도
--  - 쇼핑몰마다 옵션 표기가 달라서 variant_name 으로는 비교할 수 없음
--      예: '256GB / Black' · 'Black, 256 GB' · '블랙 256기가' → 모두 'capacity:256gb|color:black'
--  - 같은 상품(products 의 brand_id + model_number) 안에서
--    normalized_option_key 가 같은 옵션끼리 가격을 비교
--
-- 형식 규칙 (값은 백엔드가 정규화해서 채움. DB 는 빈 문자열만 막음)
--  - '속성:값' 을 '|' 로 연결, 속성 이름 알파벳 순 정렬
--      예: 'capacity:256gb|color:black', 'color:black|size:270mm'
--  - 모두 소문자, 공백 없음, 단위는 붙여 씀 (256gb, 270mm, 1tb)
--  - 색상 등 값은 영어로 통일 (블랙 → black)
--  - NULL      : 아직 정규화하지 않음 (기존 데이터 · 백엔드가 채우기 전)
--  - 'default' : 옵션이 없는 상품 (external_variant_id = 'DEFAULT' 이고 variant_name 도 없음)
--      external_variant_id 가 'DEFAULT' 여도 variant_name 이 있으면 옵션이 있는 것
--      (예: 03 시드의 'DEFAULT' / '256GB' → 'capacity:256gb')
--  - UNIQUE 아님 : 같은 쇼핑몰 상품 안에서도 SKU 는 다르고 옵션은 같을 수 있음
--  - 대소문자는 collation(utf8mb4_unicode_ci)이 같게 보므로 CHECK 로 못 막음 → 소문자 저장은 백엔드가 지킴
-- =====================================================

USE globalprice;


-- ---------- 1. 컬럼 ----------
SET @has_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mall_product_variants'
      AND COLUMN_NAME = 'normalized_option_key');

SET @ddl := IF(@has_col = 0,
    'ALTER TABLE mall_product_variants ADD COLUMN normalized_option_key VARCHAR(255) NULL AFTER variant_name',
    'SELECT ''normalized_option_key already exists'' AS migration_003');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;


-- ---------- 2. 인덱스 (같은 옵션 찾기) ----------
SET @has_idx := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mall_product_variants'
      AND INDEX_NAME = 'idx_variants_option_key');

SET @ddl := IF(@has_idx = 0,
    'ALTER TABLE mall_product_variants ADD INDEX idx_variants_option_key (normalized_option_key)',
    'SELECT ''idx_variants_option_key already exists'' AS migration_003');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;


-- ---------- 3. CHECK (NULL 이거나 빈 문자열이 아님) ----------
SET @has_chk := (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mall_product_variants'
      AND CONSTRAINT_TYPE = 'CHECK'
      AND CONSTRAINT_NAME = 'chk_variant_option_key');

SET @ddl := IF(@has_chk = 0,
    'ALTER TABLE mall_product_variants ADD CONSTRAINT chk_variant_option_key CHECK (normalized_option_key IS NULL OR TRIM(normalized_option_key) <> '''')',
    'SELECT ''chk_variant_option_key already exists'' AS migration_003');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;


-- ---------- 4. 옵션 없는 기존 행 채우기 ----------
--  - external_variant_id = 'DEFAULT' 이고 variant_name 이 비어 있고 아직 NULL 인 행만 'default' 로
--    (이미 채운 값은 그대로)
--  - 옵션이 있는 행(variant_name 있음)은 정규화 로직이 필요하므로 NULL 로 두고 백엔드가 채움
--  - updated_at 은 ON UPDATE CURRENT_TIMESTAMP 라 그대로 두면 가격 갱신 시각이 바뀜 → 원래 값 유지
--  - MySQL Workbench 기본 safe update 모드(오류 1175)에 막히지 않도록 잠깐 끄고 원래대로 되돌림
SET @old_safe_updates := @@SQL_SAFE_UPDATES;
SET SQL_SAFE_UPDATES = 0;

UPDATE mall_product_variants
SET normalized_option_key = 'default',
    updated_at = updated_at
WHERE external_variant_id = 'DEFAULT'
  AND (variant_name IS NULL OR TRIM(variant_name) = '')
  AND normalized_option_key IS NULL;

SET SQL_SAFE_UPDATES = @old_safe_updates;


SHOW COLUMNS FROM mall_product_variants;
SELECT COUNT(*)                                     AS variant_count,
       SUM(normalized_option_key = 'default')       AS default_option_count,
       SUM(normalized_option_key IS NULL)           AS not_normalized_count
FROM mall_product_variants;
