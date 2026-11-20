-- =====================================================
-- GlobalPrice
-- STEP 02 : Create 19 Tables
-- MySQL 8.0.16+ (CHECK 제약), 8.0.13+ (함수형 인덱스)
--
-- 공통 규칙
--  - 테이블 이름은 소문자 snake_case (Spring JPA 기본 네이밍과 일치, Linux 배포 대비)
--  - PK는 AUTO_INCREMENT라 값이 바뀌지 않으므로 ON UPDATE CASCADE는 쓰지 않음
--  - 엔진은 MySQL 기본값(InnoDB) 사용
--  - 금액 컬럼 중 _krw 가 붙은 것은 원화, 나머지는 해당 쇼핑몰 통화
--  - 금액 · 비율 컬럼은 CHECK 로 음수 / 범위 밖 값을 막음 (비율은 0 ~ 1)
--  - 상태 · 유형 컬럼은 CHECK IN (...) 으로 허용값 고정 (JPA @Enumerated(STRING) 과 맞춤)
--  - NULL 이 섞인 조합의 중복은 UNIQUE ((IFNULL(...))) 함수형 인덱스로 막음
--    (일반 UNIQUE 는 NULL 끼리 서로 다른 값으로 취급해 중복을 허용함)
--  - 쇼핑몰 · 상품 · 행사는 알림(price_alerts)이 RESTRICT 로 참조하므로
--    삭제 대신 is_active = FALSE 로 비활성화하는 것을 기본으로 함
--  - CREATE TABLE IF NOT EXISTS 라 이미 있는 테이블은 바뀌지 않음.
--    구조 변경 후에는 기존 DB 백업 및 변경 영향 검토 후
-- ALTER TABLE 또는 별도 마이그레이션 SQL을 통해 반영
-- 기존 데이터베이스를 임의로 삭제하지 않음
-- =====================================================

USE globalprice;


-- =====================================================
-- 1. users : 회원 정보
-- =====================================================
CREATE TABLE IF NOT EXISTS users (
    user_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    nickname VARCHAR(50) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP
);


-- =====================================================
-- 2. brands : 브랜드 정보
--    utf8mb4_unicode_ci 라 'Nike' / 'NIKE' 는 같은 값으로 보고 중복 차단
-- =====================================================
CREATE TABLE IF NOT EXISTS brands (
    brand_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    brand_name VARCHAR(100) NOT NULL UNIQUE
);


-- =====================================================
-- 3. categories : 상품 카테고리 (상위 카테고리 자기참조)
--    같은 상위 아래 같은 이름 금지 (최상위는 parent 를 0 으로 보고 비교)
-- =====================================================
CREATE TABLE IF NOT EXISTS categories (
    category_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    category_name VARCHAR(100) NOT NULL,
    parent_category_id BIGINT NULL,

    CONSTRAINT fk_categories_parent
        FOREIGN KEY (parent_category_id)
        REFERENCES categories(category_id)
        ON DELETE SET NULL,

    CONSTRAINT uq_category_parent_name
        UNIQUE ((IFNULL(parent_category_id, 0)), category_name)
);


-- =====================================================
-- 4. products : 기본 상품 정보 (판매처와 무관한 "같은 상품")
--    같은 브랜드의 같은 모델번호 = 같은 상품 (쇼핑몰 간 상품 매칭 기준)
--    model_number 가 NULL 이면 중복 검사 대상 아님
-- =====================================================
CREATE TABLE IF NOT EXISTS products (
    product_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    brand_id BIGINT NULL,
    category_id BIGINT NULL,
    product_name VARCHAR(255) NOT NULL,
    model_number VARCHAR(100),
    description TEXT,
    image_url VARCHAR(1000),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_products_brand
        FOREIGN KEY (brand_id)
        REFERENCES brands(brand_id)
        ON DELETE SET NULL,

    CONSTRAINT fk_products_category
        FOREIGN KEY (category_id)
        REFERENCES categories(category_id)
        ON DELETE SET NULL,

    CONSTRAINT uq_product_brand_model
        UNIQUE (brand_id, model_number)
);


-- =====================================================
-- 5. shopping_malls : 쇼핑몰 정보 (해외 쇼핑몰 + 국내 직구관)
-- =====================================================
CREATE TABLE IF NOT EXISTS shopping_malls (
    mall_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    mall_name VARCHAR(100) NOT NULL UNIQUE,
    mall_url VARCHAR(1000),
    country_code CHAR(2),                      -- 출발국 (US, CN, JP, KR ...)
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);


-- =====================================================
-- 6. mall_products : 쇼핑몰별 상품 정보
--    API 수집 시 (mall_id, external_product_id) 로 UPSERT
-- =====================================================
CREATE TABLE IF NOT EXISTS mall_products (
    mall_product_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_id BIGINT NOT NULL,
    mall_id BIGINT NOT NULL,
    external_product_id VARCHAR(255) NOT NULL, -- 시드 데이터도 값 필수 (UNIQUE 보장)
    product_url VARCHAR(1000),
    currency_code CHAR(3) NOT NULL,
    shipping_fee DECIMAL(15,2) NULL,           -- 상품별 배송비. NULL이면 shipping_rules 사용
    last_fetched_at DATETIME,

    INDEX idx_mall_products_fetched (last_fetched_at),  -- 오래된 상품부터 재수집

    CONSTRAINT fk_mall_products_product
        FOREIGN KEY (product_id)
        REFERENCES products(product_id)
        ON DELETE CASCADE,

    CONSTRAINT fk_mall_products_mall
        FOREIGN KEY (mall_id)
        REFERENCES shopping_malls(mall_id)
        ON DELETE CASCADE,

    CONSTRAINT uq_mall_external_product
        UNIQUE (mall_id, external_product_id),

    CONSTRAINT chk_mall_product_shipping
        CHECK (shipping_fee IS NULL OR shipping_fee >= 0)
);


-- =====================================================
-- 7. mall_product_variants : 상품 옵션 및 현재 가격
--    API 수집 시 (mall_product_id, external_variant_id) 로 UPSERT
--    옵션이 없는 상품은 external_variant_id = 'DEFAULT' 한 건
-- =====================================================
CREATE TABLE IF NOT EXISTS mall_product_variants (
    mall_variant_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    mall_product_id BIGINT NOT NULL,
    external_variant_id VARCHAR(255) NOT NULL DEFAULT 'DEFAULT', -- 쇼핑몰 SKU / 옵션 ID
    variant_name VARCHAR(255),                 -- 예: '270mm / Black'
    current_price DECIMAL(15,2) NOT NULL,      -- 쇼핑몰 통화 기준
    stock_status VARCHAR(30) NOT NULL DEFAULT 'IN_STOCK',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT fk_variants_mall_product
        FOREIGN KEY (mall_product_id)
        REFERENCES mall_products(mall_product_id)
        ON DELETE CASCADE,

    CONSTRAINT uq_variant_external
        UNIQUE (mall_product_id, external_variant_id),

    CONSTRAINT chk_variant_price
        CHECK (current_price >= 0),

    CONSTRAINT chk_variant_stock
        CHECK (stock_status IN ('IN_STOCK', 'OUT_OF_STOCK', 'UNKNOWN'))
);


-- =====================================================
-- 8. exchange_rates : 환율 정보
--    rate_to_krw 는 항상 "1 단위" 기준으로 저장
--    (수출입은행 API의 JPY(100) · IDR(100)은 100으로 나눠서 저장
--     → IDR 은 1단위가 0.08xx 원이라 소수 6자리까지 보관)
--    base_date   : 수출입은행 고시일 (같은 통화 · 같은 날은 1건, UPSERT)
--    recorded_at : 실제로 받아온 시각
--    주말 · 공휴일 · 오전 11시 이전에는 API가 빈 결과를 주므로
--    "가장 최근 base_date" 환율을 사용
-- =====================================================
CREATE TABLE IF NOT EXISTS exchange_rates (
    exchange_rate_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    currency_code CHAR(3) NOT NULL,            -- USD, JPY, EUR ...
    rate_to_krw DECIMAL(18,6) NOT NULL,
    base_date DATE NOT NULL,
    recorded_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_exchange_currency_date
        UNIQUE (currency_code, base_date),     -- 최신 환율 조회 인덱스 겸용

    CONSTRAINT chk_exchange_rate
        CHECK (rate_to_krw > 0)
);


-- =====================================================
-- 9. shipping_rules : 쇼핑몰별 기본 배송비 규칙 (한국 도착 기준)
--    우선순위: mall_products.shipping_fee > shipping_rules
--    쇼핑몰당 활성 규칙은 1건 (비활성 규칙은 이력으로 여러 건 가능)
-- =====================================================
CREATE TABLE IF NOT EXISTS shipping_rules (
    shipping_rule_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    mall_id BIGINT NOT NULL,
    base_fee DECIMAL(15,2) NOT NULL DEFAULT 0,
    free_shipping_threshold DECIMAL(15,2),     -- 이 금액 이상이면 무료배송
    currency_code CHAR(3) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,

    CONSTRAINT fk_shipping_mall
        FOREIGN KEY (mall_id)
        REFERENCES shopping_malls(mall_id)
        ON DELETE CASCADE,

    CONSTRAINT uq_shipping_active_mall
        UNIQUE ((IF(is_active, mall_id, NULL))),

    CONSTRAINT chk_shipping_amount
        CHECK (base_fee >= 0
               AND (free_shipping_threshold IS NULL OR free_shipping_threshold >= 0))
);


-- =====================================================
-- 10. tax_rules : 관세 및 부가세 규칙
--     origin_country : 출발국 (면세 한도가 미국 $200, 기타 $150 으로 다름)
--                      NULL 이면 "기타 국가" 기본 규칙
--     category_id    : NULL 이면 전 품목 기본 규칙
--     (category_id, origin_country) 조합당 활성 규칙은 1건
--     적용 우선순위: 카테고리+국가 > 카테고리 > 국가 > 기본
-- =====================================================
CREATE TABLE IF NOT EXISTS tax_rules (
    tax_rule_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    category_id BIGINT NULL,
    origin_country CHAR(2) NULL,
    duty_rate DECIMAL(7,4) NOT NULL DEFAULT 0, -- 0.1300 = 13%
    vat_rate DECIMAL(7,4) NOT NULL DEFAULT 0,  -- 0.1000 = 10%
    exemption_amount_usd DECIMAL(15,2) NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,

    -- 카테고리 삭제 시 품목 전용 규칙이 "전 품목 규칙"으로 바뀌지 않도록 RESTRICT(기본값)
    CONSTRAINT fk_tax_category
        FOREIGN KEY (category_id)
        REFERENCES categories(category_id),

    CONSTRAINT uq_tax_active_scope
        UNIQUE ((IF(is_active,
                    CONCAT(IFNULL(category_id, 0), ':', IFNULL(origin_country, '--')),
                    NULL))),

    CONSTRAINT chk_tax_rate
        CHECK (duty_rate BETWEEN 0 AND 1
               AND vat_rate BETWEEN 0 AND 1
               AND exemption_amount_usd >= 0)
);


-- =====================================================
-- 11. fee_rules : 결제 수수료 규칙
--     원화로 판매하는 국내 직구관에는 적용하지 않음 (PriceCalculator 에서 처리)
-- =====================================================
CREATE TABLE IF NOT EXISTS fee_rules (
    fee_rule_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    fee_name VARCHAR(50) NOT NULL UNIQUE,      -- 예: '해외결제 수수료'
    fee_rate DECIMAL(7,4) NOT NULL DEFAULT 0,  -- 0.0100 = 1%
    fixed_fee_krw DECIMAL(15,2) NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,

    CONSTRAINT chk_fee_amount
        CHECK (fee_rate BETWEEN 0 AND 1 AND fixed_fee_krw >= 0)
);


-- =====================================================
-- 12. store_events : 쇼핑몰 행사 · 쿠폰 정보
--
--     할인 정책
--       SALE   : 정률(RATE) · 정액(AMOUNT) 할인 모두 지원.
--                할인 정보가 없는 SALE 도 허용 (쇼핑몰 가격에 이미 세일이 반영된 경우,
--                행사 안내 · EVENT_START 알림용). 이때 계산에서 추가 할인하지 않음
--       COUPON : 할인 정보 필수. 조건(기간 · is_active · 최소 구매금액)을
--                모두 충족할 때만 적용. min_purchase_amount 가 NULL 이면 조건 없음
--       중복   : 한 옵션 계산에는 행사 1건만 적용 (price_calculations.applied_event_id)
--
--     discount_type / discount_value 는 둘 다 NULL 이거나 둘 다 값이 있어야 함
--     max_discount_amount (최대 할인금액) 는 정률 할인에만 의미가 있으므로 RATE 에만 허용
-- =====================================================
CREATE TABLE IF NOT EXISTS store_events (
    store_event_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    mall_id BIGINT NOT NULL,
    event_name VARCHAR(255) NOT NULL,
    event_type VARCHAR(50) NOT NULL,           -- SALE / COUPON
    description TEXT,
    discount_type VARCHAR(10) NULL,            -- RATE(정률) / AMOUNT(정액)
    discount_value DECIMAL(15,4) NULL,         -- RATE: 0.1000 = 10%, AMOUNT: 쇼핑몰 통화 금액
    min_purchase_amount DECIMAL(15,2) NULL,    -- 최소 구매금액 (쇼핑몰 통화)
    max_discount_amount DECIMAL(15,2) NULL,    -- 최대 할인금액 (쇼핑몰 통화)
    start_at DATETIME NOT NULL,
    end_at DATETIME NOT NULL,
    event_url VARCHAR(1000),
    banner_image_url VARCHAR(1000),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_event_start (start_at),          -- EVENT_START 알림 배치
    INDEX idx_event_end (end_at),              -- COUPON_EXPIRY 알림 배치

    CONSTRAINT fk_store_event_mall
        FOREIGN KEY (mall_id)
        REFERENCES shopping_malls(mall_id)
        ON DELETE CASCADE,

    CONSTRAINT chk_event_period
        CHECK (end_at >= start_at),

    CONSTRAINT chk_event_type
        CHECK (event_type IN ('SALE', 'COUPON')),

    -- 할인 정보 형태 : 없음(NULL, NULL) 또는 (RATE|AMOUNT, 값). COUPON 은 없음 불가
    CONSTRAINT chk_event_discount
        CHECK (
            (
                (discount_type IS NULL AND discount_value IS NULL)
                OR
                (discount_type IS NOT NULL
                    AND discount_type IN ('RATE', 'AMOUNT')
                    AND discount_value IS NOT NULL)
            )
            AND (event_type <> 'COUPON' OR discount_type IS NOT NULL)
        ),

    -- 할인 값 범위 : RATE 0 초과 1 이하, AMOUNT 0 초과, 최대 할인금액은 RATE 에만
    CONSTRAINT chk_event_discount_value
        CHECK (
            (discount_type IS NULL OR discount_value > 0)
            AND (discount_type IS NULL OR discount_type <> 'RATE' OR discount_value <= 1)
            AND (min_purchase_amount IS NULL OR min_purchase_amount >= 0)
            AND (max_discount_amount IS NULL
                OR (max_discount_amount > 0 AND IFNULL(discount_type, '') = 'RATE'))
        )
);


-- =====================================================
-- 13. price_calculations : 예상 구매비용 계산 결과 (옵션당 최신 1건)
--     total = product_price_krw + shipping_fee_krw - discount_amount_krw
--           + customs_duty_krw + vat_krw + payment_fee_krw
--     (CHECK 로 강제 → 각 항목을 먼저 반올림한 뒤 합산해서 저장할 것)
--
--     할인 적용 (store_events 할인 정책)
--       - 적용 가능한 행사 중 할인액이 가장 큰 1건만 적용 (SALE · COUPON 중복 불가)
--       - 적용한 행사는 applied_event_id, 할인액은 discount_amount_krw
--       - 할인 없으면 applied_event_id = NULL, discount_amount_krw = 0
--       - 할인은 상품가에만 적용 (배송비 할인 없음) → discount <= product_price_krw
--       - RATE   : product × discount_value, max_discount_amount 로 상한
--       - AMOUNT : discount_value (쇼핑몰 통화) 를 원화 환산
--       - 행사의 mall_id 와 옵션의 쇼핑몰이 같은지, 기간 · is_active 는 코드에서 확인
--
--     계산 기준 (PriceCalculator)
--       과세가격    = product + shipping - discount      (실제 지급액, 원화 환산)
--       면세 판정   = 과세가격(USD 환산) <= exemption_amount_usd 이면 관세 · 부가세 0
--       관세        = 과세가격 × duty_rate
--       부가세      = (과세가격 + 관세) × vat_rate
--       결제수수료  = (product + shipping - discount) × fee_rate + fixed_fee_krw
--     stale 여부는 calculated_at 과 현재 시각 차이로 판단 (별도 컬럼 없음)
-- =====================================================
CREATE TABLE IF NOT EXISTS price_calculations (
    calculation_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    mall_variant_id BIGINT NOT NULL UNIQUE,
    exchange_rate_id BIGINT NULL,              -- 환율 기준 시각 표시용
    shipping_rule_id BIGINT NULL,
    tax_rule_id BIGINT NULL,
    fee_rule_id BIGINT NULL,
    applied_event_id BIGINT NULL,              -- 적용한 행사 1건 (SALE 또는 COUPON)
    product_price_krw DECIMAL(15,2) NOT NULL,
    shipping_fee_krw DECIMAL(15,2) NOT NULL DEFAULT 0,
    discount_amount_krw DECIMAL(15,2) NOT NULL DEFAULT 0,  -- 행사 할인액
    customs_duty_krw DECIMAL(15,2) NOT NULL DEFAULT 0,
    vat_krw DECIMAL(15,2) NOT NULL DEFAULT 0,
    payment_fee_krw DECIMAL(15,2) NOT NULL DEFAULT 0,
    total_amount_krw DECIMAL(15,2) NOT NULL,
    calculated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_calculation_time (calculated_at), -- 오래된 계산부터 재계산

    CONSTRAINT fk_calculation_variant
        FOREIGN KEY (mall_variant_id)
        REFERENCES mall_product_variants(mall_variant_id)
        ON DELETE CASCADE,

    CONSTRAINT fk_calculation_exchange
        FOREIGN KEY (exchange_rate_id)
        REFERENCES exchange_rates(exchange_rate_id)
        ON DELETE SET NULL,

    CONSTRAINT fk_calculation_shipping
        FOREIGN KEY (shipping_rule_id)
        REFERENCES shipping_rules(shipping_rule_id)
        ON DELETE SET NULL,

    CONSTRAINT fk_calculation_tax
        FOREIGN KEY (tax_rule_id)
        REFERENCES tax_rules(tax_rule_id)
        ON DELETE SET NULL,

    CONSTRAINT fk_calculation_fee
        FOREIGN KEY (fee_rule_id)
        REFERENCES fee_rules(fee_rule_id)
        ON DELETE SET NULL,

    CONSTRAINT fk_calculation_event
        FOREIGN KEY (applied_event_id)
        REFERENCES store_events(store_event_id)
        ON DELETE SET NULL,

    CONSTRAINT chk_calculation_parts
        CHECK (product_price_krw >= 0
               AND shipping_fee_krw >= 0
               AND discount_amount_krw >= 0
               AND discount_amount_krw <= product_price_krw
               AND customs_duty_krw >= 0
               AND vat_krw >= 0
               AND payment_fee_krw >= 0),

    CONSTRAINT chk_calculation_total
        CHECK (total_amount_krw = product_price_krw + shipping_fee_krw
                                  - discount_amount_krw
                                  + customs_duty_krw + vat_krw + payment_fee_krw)
);


-- =====================================================
-- 14. api_sources : 외부 API 정보
--     URL · 인증 방식 · API 키는 application.yml / 환경변수에서 관리
-- =====================================================
CREATE TABLE IF NOT EXISTS api_sources (
    api_source_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    mall_id BIGINT NULL,                       -- 환율 API처럼 쇼핑몰과 무관하면 NULL
    source_name VARCHAR(100) NOT NULL UNIQUE,  -- KOREAEXIM_RATE, EBAY_BROWSE, SEED ...
    is_active BOOLEAN NOT NULL DEFAULT TRUE,

    CONSTRAINT fk_api_source_mall
        FOREIGN KEY (mall_id)
        REFERENCES shopping_malls(mall_id)
        ON DELETE SET NULL
);


-- =====================================================
-- 15. api_sync_logs : API 동기화 기록
-- =====================================================
CREATE TABLE IF NOT EXISTS api_sync_logs (
    sync_log_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    api_source_id BIGINT NOT NULL,
    sync_type VARCHAR(50),                     -- EXCHANGE_RATE / PRODUCT
    status VARCHAR(30) NOT NULL,               -- RUNNING / SUCCESS / PARTIAL / FAILED
    http_status INT NULL,                      -- 429, 401, 5xx 등 오류 구분용
    records_fetched INT NOT NULL DEFAULT 0,
    records_skipped INT NOT NULL DEFAULT 0,    -- 형식 오류 · 비정상 값으로 건너뛴 건수
    error_message TEXT,
    started_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at DATETIME,

    -- 소스별 최근 동기화 조회 (FK 인덱스 겸용)
    INDEX idx_sync_source_time (api_source_id, started_at),

    CONSTRAINT fk_sync_api_source
        FOREIGN KEY (api_source_id)
        REFERENCES api_sources(api_source_id)
        ON DELETE CASCADE,

    CONSTRAINT chk_sync_status
        CHECK (status IN ('RUNNING', 'SUCCESS', 'PARTIAL', 'FAILED')),

    CONSTRAINT chk_sync_counts
        CHECK (records_fetched >= 0
               AND records_skipped >= 0
               AND (completed_at IS NULL OR completed_at >= started_at))
);


-- =====================================================
-- 16. wishlists : 관심 상품
-- =====================================================
CREATE TABLE IF NOT EXISTS wishlists (
    wishlist_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_wishlist_user
        FOREIGN KEY (user_id)
        REFERENCES users(user_id)
        ON DELETE CASCADE,

    CONSTRAINT fk_wishlist_product
        FOREIGN KEY (product_id)
        REFERENCES products(product_id)
        ON DELETE CASCADE,

    CONSTRAINT uq_user_product
        UNIQUE (user_id, product_id)
);


-- =====================================================
-- 17. price_alerts : 알림 조건
--     TARGET_PRICE  : 상품(판매처 무관)의 최저 예상가가 목표가 이하
--     PERIOD_LOWEST : 상품의 최근 30일 최저 예상가 갱신
--     EVENT_START   : 행사 시작 당일
--     COUPON_EXPIRY : 쿠폰 마감 D-1
--
--     product_id / store_event_id 는 CHECK 에 쓰이므로 FK 동작(CASCADE/SET NULL)을
--     걸 수 없음 (MySQL 오류 3823) → 기본값 RESTRICT.
--     상품 · 행사를 지울 때는 관련 알림을 먼저 지우거나, 행사는 is_active=FALSE 로 비활성화.
--     쇼핑몰 삭제도 store_events CASCADE → 여기서 RESTRICT 에 막히므로
--     쇼핑몰은 shopping_malls.is_active = FALSE 로 비활성화.
--
--     같은 회원 · 같은 조건 · 같은 대상의 알림은 1건 (목표가 변경은 UPDATE)
-- =====================================================
CREATE TABLE IF NOT EXISTS price_alerts (
    alert_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    product_id BIGINT NULL,
    store_event_id BIGINT NULL,
    alert_basis VARCHAR(30) NOT NULL,
    target_price_krw DECIMAL(15,2) NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    last_notified_at DATETIME,                 -- 같은 조건 하루 1회 제한
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_alert_basis_active (alert_basis, is_active),  -- 알림 배치 조회

    CONSTRAINT fk_alert_user
        FOREIGN KEY (user_id)
        REFERENCES users(user_id)
        ON DELETE CASCADE,

    CONSTRAINT fk_alert_product
        FOREIGN KEY (product_id)
        REFERENCES products(product_id),

    CONSTRAINT fk_alert_event
        FOREIGN KEY (store_event_id)
        REFERENCES store_events(store_event_id),

    CONSTRAINT uq_alert_user_target
        UNIQUE (user_id, alert_basis,
                (IFNULL(product_id, 0)), (IFNULL(store_event_id, 0))),

    CONSTRAINT chk_alert_target
        CHECK (
            (alert_basis = 'TARGET_PRICE'
                AND product_id IS NOT NULL
                AND store_event_id IS NULL
                AND target_price_krw IS NOT NULL)
            OR
            (alert_basis = 'PERIOD_LOWEST'
                AND product_id IS NOT NULL
                AND store_event_id IS NULL
                AND target_price_krw IS NULL)
            OR
            (alert_basis IN ('EVENT_START', 'COUPON_EXPIRY')
                AND product_id IS NULL
                AND store_event_id IS NOT NULL
                AND target_price_krw IS NULL)
        ),

    CONSTRAINT chk_alert_price
        CHECK (target_price_krw IS NULL OR target_price_krw > 0)
);


-- =====================================================
-- 18. notifications : 사용자 알림 내역
--     read_at IS NULL 이면 안 읽은 알림
-- =====================================================
CREATE TABLE IF NOT EXISTS notifications (
    notification_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    alert_id BIGINT NOT NULL,
    message TEXT NOT NULL,
    sent_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_at DATETIME,

    CONSTRAINT fk_notification_alert
        FOREIGN KEY (alert_id)
        REFERENCES price_alerts(alert_id)
        ON DELETE CASCADE
);


-- =====================================================
-- 19. price_history : 가격 변동 이력 (그래프 · 30일 최저가 판정용)
--     currency_code 는 기록 당시 mall_products.currency_code 를 복사해 보관
-- =====================================================
CREATE TABLE IF NOT EXISTS price_history (
    history_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    mall_variant_id BIGINT NOT NULL,
    product_price DECIMAL(15,2) NOT NULL,
    currency_code CHAR(3) NOT NULL,             -- USD / JPY / EUR 등
    total_amount_krw DECIMAL(15,2),
    recorded_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_history_variant_time (mall_variant_id, recorded_at),

    CONSTRAINT fk_price_history_variant
        FOREIGN KEY (mall_variant_id)
        REFERENCES mall_product_variants(mall_variant_id)
        ON DELETE CASCADE,

    CONSTRAINT chk_history_price
        CHECK (product_price >= 0
               AND (total_amount_krw IS NULL OR total_amount_krw >= 0))
);


-- =====================================================
-- 생성 결과 확인
-- =====================================================
SHOW TABLES;
