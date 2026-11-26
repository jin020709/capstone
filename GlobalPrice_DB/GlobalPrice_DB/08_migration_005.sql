-- =====================================================
-- GlobalPrice
-- MIGRATION 005 : search_aliases 를 검색어 한영 변환의 기준으로 (백엔드 aliases.csv 대체)
--  - 05_migration_002.sql (search_aliases 테이블) 실행 후 적용
--  - 기존 데이터는 지우지 않음 (컬럼 1개 추가 + 별칭 행 추가)
--  - 컬럼 · CHECK 가 이미 있으면 건너뛰고, 같은 alias_text 의 변환 행이 이미 있으면 넣지 않음 (여러 번 실행해도 안전)
--
-- 바뀌는 점
--  - 백엔드가 aliases.csv 대신 이 테이블(model_number IS NULL · is_active 인 행)을 읽는다 (10분마다 다시 읽음)
--  - 한 단어씩 바꾼다. alias_text 에 띄어쓰기가 있는 행(예: '아이폰 17')은 모델번호 매칭용이라 변환에는 안 씀
--  - 사전 단어로 시작하고 뒤에 숫자 · 영문이 붙으면 나눠서 바꾼다 (갤럭시폴드7 → galaxy z fold 7)
--
-- alias_source (새 컬럼)
--  - 'MANUAL' : 사람이 넣거나 검토한 값 (이 파일 · 002 의 행 전부)
--  - 'AUTO'   : 나중에 번역 API 로 자동 추가할 값 (아직 없음). 같은 말에 MANUAL 이 있으면 MANUAL 을 쓴다
--
-- 데이터
--  - 11/18 백엔드 aliases.csv 에만 있던 19개 + 전자기기 검색어 (휴대폰 · 액세서리 · 생활가전 · 브랜드 · 색상 · 소재)
--  - 번역은 쇼핑몰 검색에 맞춘 값 (예: 필름 → screen protector, 자급제 → unlocked, 젠더 → adapter)
--  - 뜻이 둘인 말은 넣지 않음 (레이저: Razer · laser, 브라운: Braun · brown)
--  - 고칠 때는 UPDATE 로 search_term 을 바꾸고, 끌 때는 is_active = FALSE
-- =====================================================

USE globalprice;
SET NAMES utf8mb4;


-- ---------- 1. alias_source 컬럼 ----------
SET @has_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'search_aliases'
      AND COLUMN_NAME = 'alias_source');

SET @ddl := IF(@has_col = 0,
    'ALTER TABLE search_aliases ADD COLUMN alias_source VARCHAR(10) NOT NULL DEFAULT ''MANUAL'' AFTER model_number',
    'SELECT ''search_aliases.alias_source already exists'' AS migration_005');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_chk := (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'search_aliases'
      AND CONSTRAINT_TYPE = 'CHECK'
      AND CONSTRAINT_NAME = 'chk_search_alias_source');

SET @ddl := IF(@has_chk = 0,
    'ALTER TABLE search_aliases ADD CONSTRAINT chk_search_alias_source CHECK (alias_source IN (''MANUAL'', ''AUTO''))',
    'SELECT ''chk_search_alias_source already exists'' AS migration_005');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;


-- ---------- 2. 별칭 목록 (임시 테이블) ----------
DROP TEMPORARY TABLE IF EXISTS tmp_alias_seed_005;
CREATE TEMPORARY TABLE tmp_alias_seed_005 (
    alias_text   VARCHAR(100) NOT NULL,
    search_term  VARCHAR(255) NOT NULL,
    brand_name   VARCHAR(100) NULL
) DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

INSERT INTO tmp_alias_seed_005 (alias_text, search_term, brand_name) VALUES
    -- 11/18 aliases.csv 추가분 (002 에 없던 것)
    ('에어',           'air',                NULL),
    ('미니',           'mini',               NULL),
    ('플러스',         'plus',               NULL),
    ('폴드',           'fold',               NULL),
    ('플립',           'flip',               NULL),
    ('워치',           'watch',              NULL),
    ('버즈',           'buds',               NULL),
    ('픽셀',           'pixel',              'Google'),
    ('구글',           'google',             'Google'),
    ('라이트',         'lite',               NULL),
    ('엣지',           'edge',               NULL),
    ('슬림',           'slim',               NULL),
    ('로지텍',         'logitech',           'Logitech'),
    ('앤커',           'anker',              'Anker'),
    ('기계식',         'mechanical',         NULL),
    ('보조배터리',     'power bank',         NULL),
    ('아이맥',         'imac',               'Apple'),
    ('맥미니',         'mac mini',           'Apple'),
    ('엑스박스',       'xbox',               'Microsoft'),

    -- 휴대폰 · 기기
    ('핸드폰',         'phone',              NULL),
    ('휴대폰',         'phone',              NULL),
    ('휴대전화',       'phone',              NULL),
    ('폰',             'phone',              NULL),
    ('자급제',         'unlocked',           NULL),
    ('언락',           'unlocked',           NULL),
    ('스마트워치',     'smartwatch',         NULL),
    ('탭',             'tab',                NULL),
    ('데스크탑',       'desktop',            NULL),
    ('데스크톱',       'desktop',            NULL),
    ('컴퓨터',         'computer',           NULL),
    ('게이밍',         'gaming',             NULL),
    ('게임기',         'game console',       NULL),
    ('콘솔',           'console',            NULL),
    ('스피커',         'speaker',            NULL),
    ('블루투스',       'bluetooth',          NULL),
    ('사운드바',       'soundbar',           NULL),
    ('헤드셋',         'headset',            NULL),
    ('노이즈캔슬링',   'noise cancelling',   NULL),
    ('노캔',           'noise cancelling',   NULL),
    ('마이크',         'microphone',         NULL),
    ('웹캠',           'webcam',             NULL),
    ('프린터',         'printer',            NULL),
    ('공유기',         'wifi router',        NULL),
    ('라우터',         'router',             NULL),
    ('외장하드',       'external hard drive', NULL),
    ('외장',           'external',           NULL),
    ('메모리',         'memory',             NULL),
    ('메모리카드',     'memory card',        NULL),
    ('램',             'ram',                NULL),
    ('그래픽카드',     'graphics card',      NULL),
    ('액션캠',         'action camera',      NULL),
    ('미러리스',       'mirrorless camera',  NULL),
    ('렌즈',           'lens',               NULL),
    ('드론',           'drone',              NULL),
    ('짐벌',           'gimbal',             NULL),
    ('전자책',         'e-reader',           NULL),
    ('이북리더기',     'e-reader',           NULL),
    ('프로젝터',       'projector',          NULL),
    ('빔프로젝터',     'projector',          NULL),
    ('티비',           'tv',                 NULL),
    ('텔레비전',       'tv',                 NULL),
    ('무선이어폰',     'wireless earbuds',   NULL),
    ('블루투스이어폰', 'bluetooth earbuds',  NULL),
    ('무선마우스',     'wireless mouse',     NULL),
    ('게이밍마우스',   'gaming mouse',       NULL),
    ('기계식키보드',   'mechanical keyboard', NULL),
    ('무선키보드',     'wireless keyboard',  NULL),

    -- 액세서리 (검색어에 있으면 걸러내기에서도 예외가 되도록 쇼핑몰 표기와 같은 영어로)
    ('케이스',         'case',               NULL),
    ('폰케이스',       'phone case',         NULL),
    ('커버',           'cover',              NULL),
    ('범퍼',           'bumper',             NULL),
    ('필름',           'screen protector',   NULL),
    ('보호필름',       'screen protector',   NULL),
    ('액정보호필름',   'screen protector',   NULL),
    ('강화유리',       'tempered glass',     NULL),
    ('충전기',         'charger',            NULL),
    ('고속충전기',     'fast charger',       NULL),
    ('무선충전기',     'wireless charger',   NULL),
    ('충전',           'charging',           NULL),
    ('케이블',         'cable',              NULL),
    ('충전케이블',     'charging cable',     NULL),
    ('어댑터',         'adapter',            NULL),
    ('젠더',           'adapter',            NULL),
    ('허브',           'hub',                NULL),
    ('도킹스테이션',   'docking station',    NULL),
    ('거치대',         'stand',              NULL),
    ('스탠드',         'stand',              NULL),
    ('삼각대',         'tripod',             NULL),
    ('셀카봉',         'selfie stick',       NULL),
    ('스트랩',         'strap',              NULL),
    ('밴드',           'band',               NULL),
    ('파우치',         'pouch',              NULL),
    ('가방',           'bag',                NULL),
    ('노트북가방',     'laptop bag',         NULL),
    ('백팩',           'backpack',           NULL),
    ('펜슬',           'pencil',             NULL),
    ('애플펜슬',       'apple pencil',       'Apple'),
    ('스타일러스',     'stylus',             NULL),
    ('키캡',           'keycap',             NULL),
    ('맥세이프',       'magsafe',            NULL),

    -- 제품 이름 (붙여 쓴 말)
    ('에어팟프로',     'airpods pro',        'Apple'),
    ('에어팟맥스',     'airpods max',        'Apple'),
    ('맥북프로',       'macbook pro',        'Apple'),
    ('맥북에어',       'macbook air',        'Apple'),
    ('아이패드프로',   'ipad pro',           'Apple'),
    ('아이패드에어',   'ipad air',           'Apple'),
    ('아이패드미니',   'ipad mini',          'Apple'),
    ('홈팟',           'homepod',            'Apple'),
    ('에어태그',       'airtag',             'Apple'),
    ('매직마우스',     'magic mouse',        'Apple'),
    ('매직키보드',     'magic keyboard',     'Apple'),
    ('갤럭시버즈',     'galaxy buds',        'Samsung'),
    ('갤럭시워치',     'galaxy watch',       'Samsung'),
    ('갤럭시탭',       'galaxy tab',         'Samsung'),
    ('갤럭시북',       'galaxy book',        'Samsung'),
    ('갤럭시폴드',     'galaxy z fold',      'Samsung'),
    ('갤럭시플립',     'galaxy z flip',      'Samsung'),
    ('닌텐도스위치',   'nintendo switch',    'Nintendo'),
    ('플스',           'playstation',        'Sony'),
    ('엑박',           'xbox',               'Microsoft'),

    -- 생활가전
    ('로봇청소기',     'robot vacuum',       NULL),
    ('청소기',         'vacuum',             NULL),
    ('무선청소기',     'cordless vacuum',    NULL),
    ('공기청정기',     'air purifier',       NULL),
    ('전동칫솔',       'electric toothbrush', NULL),
    ('면도기',         'shaver',             NULL),
    ('전기면도기',     'electric shaver',    NULL),
    ('드라이기',       'hair dryer',         NULL),
    ('헤어드라이어',   'hair dryer',         NULL),
    ('에어랩',         'airwrap',            'Dyson'),
    ('커피머신',       'coffee machine',     NULL),
    ('에어프라이어',   'air fryer',          NULL),

    -- 브랜드
    ('샤오미',         'xiaomi',             'Xiaomi'),
    ('화웨이',         'huawei',             'Huawei'),
    ('원플러스',       'oneplus',            'OnePlus'),
    ('레노버',         'lenovo',             'Lenovo'),
    ('에이수스',       'asus',               'ASUS'),
    ('아수스',         'asus',               'ASUS'),
    ('에이서',         'acer',               'Acer'),
    ('델',             'dell',               'Dell'),
    ('엘지',           'lg',                 'LG'),
    ('마이크로소프트', 'microsoft',          'Microsoft'),
    ('서피스',         'surface',            'Microsoft'),
    ('보스',           'bose',               'Bose'),
    ('젠하이저',       'sennheiser',         'Sennheiser'),
    ('제이비엘',       'jbl',                'JBL'),
    ('비츠',           'beats',              'Beats'),
    ('캐논',           'canon',              'Canon'),
    ('니콘',           'nikon',              'Nikon'),
    ('후지필름',       'fujifilm',           'Fujifilm'),
    ('고프로',         'gopro',              'GoPro'),
    ('디제이아이',     'dji',                'DJI'),
    ('가민',           'garmin',             'Garmin'),
    ('커세어',         'corsair',            'Corsair'),
    ('키크론',         'keychron',           'Keychron'),
    ('필립스',         'philips',            'Philips'),
    ('벨킨',           'belkin',             'Belkin'),
    ('슈피겐',         'spigen',             'Spigen'),
    ('스피겐',         'spigen',             'Spigen'),
    ('오터박스',       'otterbox',           'OtterBox'),
    ('케이스티파이',   'casetify',           'CASETiFY'),
    ('킨들',           'kindle',             'Amazon'),

    -- 색상 · 소재
    ('블랙',           'black',              NULL),
    ('화이트',         'white',              NULL),
    ('실버',           'silver',             NULL),
    ('골드',           'gold',               NULL),
    ('블루',           'blue',               NULL),
    ('핑크',           'pink',               NULL),
    ('레드',           'red',                NULL),
    ('그린',           'green',              NULL),
    ('퍼플',           'purple',             NULL),
    ('그레이',         'gray',               NULL),
    ('네이비',         'navy',               NULL),
    ('투명',           'clear',              NULL),
    ('실리콘',         'silicone',           NULL),
    ('가죽',           'leather',            NULL),
    ('지갑형',         'wallet',             NULL);


-- ---------- 3. 넣기 ----------
--  - 같은 alias_text 의 변환 행(model_number NULL)이 이미 있으면 건너뜀 → 002 · 직접 고친 값은 그대로
--  - brands 에 있는 브랜드면 brand_id 연결, 없으면 NULL
INSERT INTO search_aliases (alias_text, search_term, brand_id, alias_source)
SELECT v.alias_text, v.search_term, b.brand_id, 'MANUAL'
FROM tmp_alias_seed_005 v
LEFT JOIN brands b ON b.brand_name = v.brand_name
WHERE NOT EXISTS (
    SELECT 1 FROM search_aliases a
    WHERE a.alias_text = v.alias_text AND a.model_number IS NULL
);

-- brands 를 나중에 추가했으면 다시 실행할 때 연결 (brand_id 가 NULL 인 변환 행만)
SET @old_safe_updates := @@SQL_SAFE_UPDATES;
SET SQL_SAFE_UPDATES = 0;

UPDATE search_aliases a
JOIN tmp_alias_seed_005 v ON v.alias_text = a.alias_text
JOIN brands b             ON b.brand_name = v.brand_name
SET a.brand_id = b.brand_id
WHERE a.brand_id IS NULL
  AND a.model_number IS NULL;

SET SQL_SAFE_UPDATES = @old_safe_updates;

DROP TEMPORARY TABLE IF EXISTS tmp_alias_seed_005;

SELECT COUNT(*)                          AS alias_count,
       SUM(model_number IS NULL)         AS translation_count,
       SUM(alias_source = 'AUTO')        AS auto_count
FROM search_aliases;
