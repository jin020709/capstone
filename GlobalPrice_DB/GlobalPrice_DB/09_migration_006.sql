-- =====================================================
-- GlobalPrice
-- MIGRATION 006 : model_codes (제조사 모델번호 → 모델). 같은 상품 묶기에 씀
--  - 08_migration_005.sql 실행 후 적용
--  - 테이블이 이미 있으면 만들지 않고, 같은 model_code 행이 이미 있으면 넣지 않음 (여러 번 실행해도 안전)
--
-- 왜 필요한가
--  - 갤럭시는 같은 모델이 나라마다 모델번호가 다르고(SM-S938B · S938U · S938N), 상품명에 모델명 대신
--    모델번호만 쓰는 판매처가 많다. 예전에는 번호 11개를 백엔드 코드에 직접 적어 두어서
--    목록에 없는 모델(Fold5 F946, A55 A556 등)은 같은 상품으로 묶이지 않았다
--  - 모델 하나에 번호 여러 개를 넣을 수 있다 (예: Galaxy A15 ← A155 (LTE) · A156 (5G))
--
-- 컬럼
--  - model_code : 지역 글자 · "SM-" 를 뺀 소문자 번호 (SM-S938B/DS → s938). 상품명에서는
--                 "SM-S938B" 도, "Samsung Galaxy ... (S938B)" 처럼 SM- 없이 쓴 것도 알아본다
--  - model_key  : 백엔드 묶기 키에서 용량을 뺀 앞부분. 갤럭시는 galaxy|시리즈|번호|접미사
--                 (접미사: '' · '+' · ultra · fe · edge). 용량은 상품명에서 따로 읽는다
--  - model_name : 사람이 보는 이름 (확인용, 백엔드는 쓰지 않음)
--
-- 백엔드
--  - is_active 인 행을 10분마다 다시 읽는다 (행을 고치면 서버를 다시 켜지 않아도 반영)
--  - 테이블이 없거나 비어 있으면 백엔드에 내장된 최소 목록(S24 · S25 · Fold/Flip 6 · 7)을 쓴다
--  - 상품명 속 모델명과 모델번호가 서로 다른 모델이면 묶지 않는다 (잘못 묶는 것보다 덜 묶는 게 낫다)
--  - 이미 저장된 상품은 다음 검색 · 갱신 때 새 키로 다시 묶인다
--
-- 고칠 때는 INSERT 로 번호를 더하고, 끌 때는 is_active = FALSE
-- =====================================================

USE globalprice;
SET NAMES utf8mb4;


-- ---------- 1. 테이블 ----------
CREATE TABLE IF NOT EXISTS model_codes (
    model_code_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    model_code VARCHAR(20) NOT NULL,
    model_key VARCHAR(100) NOT NULL,
    model_name VARCHAR(100) NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_model_codes_code UNIQUE (model_code),
    CONSTRAINT chk_model_codes_code
        CHECK (model_code = LOWER(TRIM(model_code)) AND model_code <> '' AND model_code NOT LIKE 'sm-%'),
    CONSTRAINT chk_model_codes_key
        CHECK (TRIM(model_key) <> '')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ---------- 2. 갤럭시 모델번호 ----------
INSERT IGNORE INTO model_codes (model_code, model_key, model_name) VALUES
    -- S 시리즈
    ('s901', 'galaxy|s|22|',        'Galaxy S22'),
    ('s906', 'galaxy|s|22|+',       'Galaxy S22+'),
    ('s908', 'galaxy|s|22|ultra',   'Galaxy S22 Ultra'),
    ('s911', 'galaxy|s|23|',        'Galaxy S23'),
    ('s916', 'galaxy|s|23|+',       'Galaxy S23+'),
    ('s918', 'galaxy|s|23|ultra',   'Galaxy S23 Ultra'),
    ('s711', 'galaxy|s|23|fe',      'Galaxy S23 FE'),
    ('s921', 'galaxy|s|24|',        'Galaxy S24'),
    ('s926', 'galaxy|s|24|+',       'Galaxy S24+'),
    ('s928', 'galaxy|s|24|ultra',   'Galaxy S24 Ultra'),
    ('s721', 'galaxy|s|24|fe',      'Galaxy S24 FE'),
    ('s931', 'galaxy|s|25|',        'Galaxy S25'),
    ('s936', 'galaxy|s|25|+',       'Galaxy S25+'),
    ('s938', 'galaxy|s|25|ultra',   'Galaxy S25 Ultra'),
    ('s937', 'galaxy|s|25|edge',    'Galaxy S25 Edge'),
    ('s731', 'galaxy|s|25|fe',      'Galaxy S25 FE'),
    -- Z 폴드 · 플립
    ('f926', 'galaxy|z fold|3|',    'Galaxy Z Fold3'),
    ('f711', 'galaxy|z flip|3|',    'Galaxy Z Flip3'),
    ('f936', 'galaxy|z fold|4|',    'Galaxy Z Fold4'),
    ('f721', 'galaxy|z flip|4|',    'Galaxy Z Flip4'),
    ('f946', 'galaxy|z fold|5|',    'Galaxy Z Fold5'),
    ('f731', 'galaxy|z flip|5|',    'Galaxy Z Flip5'),
    ('f956', 'galaxy|z fold|6|',    'Galaxy Z Fold6'),
    ('f741', 'galaxy|z flip|6|',    'Galaxy Z Flip6'),
    ('f966', 'galaxy|z fold|7|',    'Galaxy Z Fold7'),
    ('f766', 'galaxy|z flip|7|',    'Galaxy Z Flip7'),
    ('f761', 'galaxy|z flip|7|fe',  'Galaxy Z Flip7 FE'),
    -- A 시리즈 (LTE · 5G 번호가 다른 모델은 두 행)
    ('a155', 'galaxy|a|15|',        'Galaxy A15 (LTE)'),
    ('a156', 'galaxy|a|15|',        'Galaxy A15 5G'),
    ('a165', 'galaxy|a|16|',        'Galaxy A16 (LTE)'),
    ('a166', 'galaxy|a|16|',        'Galaxy A16 5G'),
    ('a256', 'galaxy|a|25|',        'Galaxy A25 5G'),
    ('a266', 'galaxy|a|26|',        'Galaxy A26 5G'),
    ('a346', 'galaxy|a|34|',        'Galaxy A34 5G'),
    ('a356', 'galaxy|a|35|',        'Galaxy A35 5G'),
    ('a366', 'galaxy|a|36|',        'Galaxy A36 5G'),
    ('a546', 'galaxy|a|54|',        'Galaxy A54 5G'),
    ('a556', 'galaxy|a|55|',        'Galaxy A55 5G'),
    ('a566', 'galaxy|a|56|',        'Galaxy A56 5G');


-- ---------- 확인 ----------
SELECT COUNT(*) AS model_code_count,
       COUNT(DISTINCT model_key) AS model_count
FROM model_codes;
