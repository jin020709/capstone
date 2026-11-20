-- =====================================================
-- GlobalPrice
-- MIGRATION 002 : search_aliases (검색어 별칭 → API 검색어 · 브랜드 · 모델번호)
--  - 01 → 02 → 03_migration_001 실행 후 적용
--  - 기존 19개 테이블과 데이터는 바꾸지 않음 (테이블 1개 추가 + 별칭 데이터)
--  - CREATE TABLE IF NOT EXISTS · INSERT IGNORE · NOT EXISTS 라 여러 번 실행해도 안전
--
-- 실행 순서
--  - 테이블 생성 · 한영 / 브랜드 별칭(데이터 1)은 시드 순서와 상관없음
--      brands 에 없는 브랜드는 brand_id NULL 로 들어가고, 브랜드를 추가한 뒤 다시 실행하면 연결됨
--  - 모델번호 별칭(데이터 2)은 03_seed_data.sql 의 상품이 products 에 있어야 들어감
--      → 03 뒤에 실행. 03 을 나중에 넣었으면 이 파일을 한 번 더 실행
--
-- 용도
--  - 사용자가 한글 · 별칭으로 검색하면 alias_text 로 찾아서
--      search_term  : 쇼핑몰 API 에 보낼 영문 검색어
--      model_number : 결과 중 같은 상품을 골라내는 정확 매칭 기준
--    을 얻는다
--  - 행 종류
--      brand_id NULL,     model_number NULL  → 단순 한영 변환 (백엔드 aliases.csv 와 같은 역할)
--      brand_id 있음,     model_number NULL  → 브랜드 검색 ("애플" → Apple). 모델이 많으므로 키워드 검색 유지
--      brand_id 있음,     model_number 있음  → 특정 모델 ("갤럭시 s25" → Samsung + 모델번호)
--  - 한 별칭이 여러 모델번호로 이어질 수 있음 (용량 · 국내판 / 해외판 등) → 행을 여러 개 둔다
--  - products 를 FK 로 참조하지 않음
--      products 행은 API 수집 때 생기므로 별칭이 가리킬 상품이 미리 없을 수 있음.
--      대신 (brand_id, model_number) 값으로 products 와 맞춘다
--      (products.uq_product_brand_model 과 같은 기준)
--  - alias_text 는 앞뒤 공백 제거 · 연속 공백 1개로 저장. 대소문자는 collation(utf8mb4_unicode_ci)이
--    같게 봄. "아이폰17" / "아이폰 17" 처럼 띄어쓰기만 다른 말은 행을 따로 둔다
--  - 브랜드가 지워지면 그 브랜드의 별칭도 함께 지움 (CASCADE). 끌 때는 is_active = FALSE
-- =====================================================

USE globalprice;
SET NAMES utf8mb4;


-- =====================================================
-- 20. search_aliases : 검색어 별칭
-- =====================================================
CREATE TABLE IF NOT EXISTS search_aliases (
    alias_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    alias_text VARCHAR(100) NOT NULL,          -- 사용자가 입력하는 말 (한글 · 영문 · 줄임말)
    search_term VARCHAR(255) NOT NULL,         -- 쇼핑몰 API 에 보낼 검색어 (영문)
    brand_id BIGINT NULL,
    model_number VARCHAR(100) NULL,            -- products.model_number 와 같은 형식
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_search_aliases_model (brand_id, model_number),   -- 모델번호 → 별칭 역조회, FK 인덱스 겸용

    CONSTRAINT fk_search_aliases_brand
        FOREIGN KEY (brand_id)
        REFERENCES brands(brand_id)
        ON DELETE CASCADE,

    -- 같은 별칭 → 같은 (브랜드, 모델) 중복 금지. NULL 끼리도 중복으로 봄
    -- alias_text 를 맨 앞에 둬서 검색어 조회에도 이 인덱스를 씀
    CONSTRAINT uq_search_alias_target
        UNIQUE (alias_text, (IFNULL(brand_id, 0)), (IFNULL(model_number, ''))),

    CONSTRAINT chk_search_alias_text
        CHECK (TRIM(alias_text) <> '' AND TRIM(search_term) <> ''),

    CONSTRAINT chk_search_alias_model
        CHECK (model_number IS NULL OR TRIM(model_number) <> '')
);


-- =====================================================
-- 별칭 목록 (임시 테이블)
--  - 아래 INSERT · UPDATE 가 같은 목록을 쓰도록 한 번만 적음
--  - 컬럼 collation 을 테이블과 같게(utf8mb4_unicode_ci) 둬서 비교할 때 collation 충돌이 없음
--  - model_number 가 NULL 이면 데이터 1(한영 · 브랜드), 있으면 데이터 2(모델번호)
--  - 접속이 끝나면 자동으로 사라지지만, 끝에서 직접 지움
-- =====================================================
DROP TEMPORARY TABLE IF EXISTS tmp_alias_seed;
CREATE TEMPORARY TABLE tmp_alias_seed (
    alias_text   VARCHAR(100) NOT NULL,
    search_term  VARCHAR(255) NOT NULL,
    brand_name   VARCHAR(100) NULL,
    model_number VARCHAR(100) NULL
) DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

INSERT INTO tmp_alias_seed (alias_text, search_term, brand_name, model_number) VALUES
    -- 데이터 1 : 백엔드 resources/aliases.csv 30개 (백엔드는 아직 CSV 를 읽음)
    ('아이폰',         'iphone',        'Apple',    NULL),
    ('갤럭시',         'galaxy',        'Samsung',  NULL),
    ('맥북',           'macbook',       'Apple',    NULL),
    ('아이패드',       'ipad',          'Apple',    NULL),
    ('에어팟',         'airpods',       'Apple',    NULL),
    ('애플워치',       'apple watch',   'Apple',    NULL),
    ('삼성',           'samsung',       'Samsung',  NULL),
    ('애플',           'apple',         'Apple',    NULL),
    ('소니',           'sony',          'Sony',     NULL),
    ('노트북',         'laptop',        NULL,       NULL),
    ('태블릿',         'tablet',        NULL,       NULL),
    ('스마트폰',       'smartphone',    NULL,       NULL),
    ('헤드폰',         'headphones',    NULL,       NULL),
    ('이어폰',         'earbuds',       NULL,       NULL),
    ('무선',           'wireless',      NULL,       NULL),
    ('러닝화',         'running shoes', NULL,       NULL),
    ('운동화',         'sneakers',      NULL,       NULL),
    ('키보드',         'keyboard',      NULL,       NULL),
    ('마우스',         'mouse',         NULL,       NULL),
    ('모니터',         'monitor',       NULL,       NULL),
    ('카메라',         'camera',        NULL,       NULL),
    ('닌텐도',         'nintendo',      'Nintendo', NULL),
    ('스위치',         'switch',        NULL,       NULL),
    ('플레이스테이션', 'playstation',   'Sony',     NULL),
    ('다이슨',         'dyson',         'Dyson',    NULL),
    ('나이키',         'nike',          'Nike',     NULL),
    ('아디다스',       'adidas',        'Adidas',   NULL),
    ('프로',           'pro',           NULL,       NULL),
    ('맥스',           'max',           NULL,       NULL),
    ('울트라',         'ultra',         NULL,       NULL),
    -- 데이터 2 : 03_seed_data.sql 테스트 상품 모델번호 (-TEST 라 실제 쇼핑몰 모델번호가 아님)
    ('아이폰 17',      'iphone 17',     'Apple',    'IPHONE17-TEST'),
    ('아이폰17',       'iphone 17',     'Apple',    'IPHONE17-TEST'),
    ('iphone 17',      'iphone 17',     'Apple',    'IPHONE17-TEST'),
    ('갤럭시 s25',     'galaxy s25',    'Samsung',  'GALAXYS25-TEST'),
    ('갤럭시s25',      'galaxy s25',    'Samsung',  'GALAXYS25-TEST'),
    ('galaxy s25',     'galaxy s25',    'Samsung',  'GALAXYS25-TEST');


-- =====================================================
-- 별칭 데이터 1 : 한영 변환 · 브랜드
--  - 브랜드 이름은 brands 에 있으면 brand_id 를 연결, 없으면 NULL
--  - 같은 alias_text 의 변환 행(model_number NULL)이 이미 있으면 건너뜀 → 중복 행이 생기지 않음
-- =====================================================
INSERT INTO search_aliases (alias_text, search_term, brand_id)
SELECT v.alias_text, v.search_term, b.brand_id
FROM tmp_alias_seed v
LEFT JOIN brands b ON b.brand_name = v.brand_name
WHERE v.model_number IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM search_aliases a
      WHERE a.alias_text = v.alias_text AND a.model_number IS NULL
  );

-- 처음 실행 때 brands 에 없어서 brand_id NULL 로 들어간 행을, 이제 브랜드가 있으면 연결
--  - brand_id 가 NULL 인 행만 채움 (직접 다른 브랜드로 바꿔 둔 행은 덮어쓰지 않음)
--  - 위 INSERT 가 alias_text 당 변환 행을 1개만 두므로 UNIQUE 충돌 없음
--  - MySQL Workbench 기본 safe update 모드(오류 1175)에 막히지 않도록 잠깐 끄고 원래대로 되돌림
SET @old_safe_updates := @@SQL_SAFE_UPDATES;
SET SQL_SAFE_UPDATES = 0;

UPDATE search_aliases a
JOIN tmp_alias_seed v ON v.alias_text = a.alias_text AND v.model_number IS NULL
JOIN brands b         ON b.brand_name = v.brand_name
SET a.brand_id = b.brand_id
WHERE a.brand_id IS NULL
  AND a.model_number IS NULL;

SET SQL_SAFE_UPDATES = @old_safe_updates;


-- =====================================================
-- 별칭 데이터 2 : 모델번호 매핑
--  - products 에 그 (브랜드, 모델번호) 상품이 있을 때만 넣음 → 03 을 안 돌렸으면 0건
--  - 실제 모델번호는 팀 확정 후 새 마이그레이션으로 추가
-- =====================================================
INSERT IGNORE INTO search_aliases (alias_text, search_term, brand_id, model_number)
SELECT v.alias_text, v.search_term, p.brand_id, p.model_number
FROM tmp_alias_seed v
JOIN brands b   ON b.brand_name = v.brand_name
JOIN products p ON p.brand_id = b.brand_id AND p.model_number = v.model_number
WHERE v.model_number IS NOT NULL;


DROP TEMPORARY TABLE IF EXISTS tmp_alias_seed;

SHOW COLUMNS FROM search_aliases;
SELECT COUNT(*)                                          AS alias_count,
       SUM(model_number IS NOT NULL)                     AS model_alias_count,
       SUM(brand_id IS NULL AND model_number IS NULL)    AS no_brand_alias_count
FROM search_aliases;
