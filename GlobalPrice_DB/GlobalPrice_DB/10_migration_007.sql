-- =====================================================
-- GlobalPrice
-- MIGRATION 007 : device_models (휴대폰 · 태블릿 모델 목록) + model_codes 에 출처 · 제조사
--  - 09_migration_006.sql 실행 후 적용
--  - 테이블 · 컬럼이 이미 있으면 건너뜀 (여러 번 실행해도 안전). 기존 행은 지우지 않음
--
-- 왜 필요한가
--  - 같은 상품 묶기 규칙이 아이폰 · 갤럭시 · 픽셀 · 맥북 · 아이패드 5개뿐이라, 샤오미 · 원플러스 · 모토로라 등
--    다른 휴대폰은 상품명이 글자까지 같아야 묶였다
--  - 백엔드가 Google Play 지원 기기 목록(제조사 · 모델 이름 · 모델번호 약 5만 행, 공개 CSV)을 받아 이 테이블들에 넣고,
--    상품명 속 모델 이름이나 모델번호로 같은 모델을 찾는다 (BACKEND_SPEC.md 4-5)
--
-- device_models : 모델 하나에 한 행
--  - model_key  : 묶기 키에서 용량을 뺀 앞부분 dev|제조사|모델 이름 (소문자, 5G · LTE 표기 뺌)
--  - brand      : 보여 줄 제조사 (Xiaomi, OnePlus …), model_name : 보여 줄 모델 이름 (POCO X6 Pro 5G)
--  - source     : MANUAL (사람이 넣음) · GPLAY (Google Play 목록에서 자동). 자동 갱신은 GPLAY 행만 지우고 다시 넣는다
--
-- model_codes : 모델번호 → model_key (09 에서 만든 테이블)
--  - 갤럭시 번호는 galaxy|… 키, 그 밖의 기기 번호는 dev|… 키
--  - 같은 번호가 MANUAL 로 이미 있으면 GPLAY 는 넣지 않음 (사람이 넣은 값 우선)
--
-- 고칠 때는 MANUAL 행을 넣거나, 끌 때는 is_active = FALSE (GPLAY 행을 끄면 다음 갱신 때 다시 켜지므로 MANUAL 로 덮어쓸 것)
-- =====================================================

USE globalprice;
SET NAMES utf8mb4;


-- ---------- 1. device_models ----------
CREATE TABLE IF NOT EXISTS device_models (
    device_model_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    model_key VARCHAR(255) NOT NULL,
    brand VARCHAR(100) NOT NULL,
    model_name VARCHAR(200) NOT NULL,
    source VARCHAR(10) NOT NULL DEFAULT 'MANUAL',
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_device_models_key UNIQUE (model_key),
    CONSTRAINT chk_device_models_source CHECK (source IN ('MANUAL', 'GPLAY')),
    CONSTRAINT chk_device_models_key CHECK (model_key LIKE 'dev|%')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ---------- 2. model_codes: 출처 · 제조사 컬럼, 키 · 번호 길이 늘림 ----------
SET @has_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'model_codes' AND COLUMN_NAME = 'source');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE model_codes
        ADD COLUMN brand VARCHAR(100) NULL AFTER model_name,
        ADD COLUMN source VARCHAR(10) NOT NULL DEFAULT ''MANUAL'' AFTER brand,
        MODIFY model_code VARCHAR(50) NOT NULL,
        MODIFY model_key VARCHAR(255) NOT NULL,
        MODIFY model_name VARCHAR(200) NULL,
        ADD CONSTRAINT chk_model_codes_source CHECK (source IN (''MANUAL'', ''GPLAY'')),
        ADD INDEX idx_model_codes_source (source)',
    'SELECT ''model_codes.source already exists'' AS migration_007');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE model_codes SET brand = 'Samsung' WHERE brand IS NULL AND model_key LIKE 'galaxy|%';


-- ---------- 확인 ----------
SELECT (SELECT COUNT(*) FROM device_models) AS device_model_count,
       (SELECT COUNT(*) FROM model_codes) AS model_code_count;
