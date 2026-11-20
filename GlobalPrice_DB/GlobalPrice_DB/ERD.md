# GlobalPrice ERD

`02_create_tables.sql` 기준 · MySQL 8.0.16+ · 19개 테이블 · 외래 키 24개
`05_migration_002.sql` 적용 후 · 20개 테이블 · 외래 키 25개 (`search_aliases` 추가)
`06_migration_003.sql` 적용 후 · `mall_product_variants.normalized_option_key` 컬럼 추가

- 표기: `PK` 기본키, `FK` 외래키, `UK` 단일 컬럼 UNIQUE
- 여러 컬럼을 묶은 UNIQUE(복합 · 함수형)는 그림에 표시하지 않고 [5. UNIQUE 제약](#5-unique-제약) 표에 정리
- 관계선: `||` 정확히 1, `|o` 0 또는 1, `o{` 0 이상

## 1. 관계 개요

```mermaid
erDiagram
    categories |o--o{ categories : "상위 카테고리"
    brands |o--o{ products : "브랜드"
    brands |o--o{ search_aliases : "검색어 별칭"
    categories |o--o{ products : "분류"
    products ||--o{ mall_products : "판매처별 등록"
    shopping_malls ||--o{ mall_products : "판매"
    mall_products ||--o{ mall_product_variants : "옵션"
    shopping_malls ||--o{ shipping_rules : "배송 규칙"
    categories |o--o{ tax_rules : "품목별 세율"
    shopping_malls ||--o{ store_events : "행사 쿠폰"
    mall_product_variants ||--o| price_calculations : "최신 계산 1건"
    exchange_rates |o--o{ price_calculations : "적용 환율"
    shipping_rules |o--o{ price_calculations : "적용 배송"
    tax_rules |o--o{ price_calculations : "적용 세금"
    fee_rules |o--o{ price_calculations : "적용 수수료"
    store_events |o--o{ price_calculations : "적용 행사 1건"
    mall_product_variants ||--o{ price_history : "가격 이력"
    shopping_malls |o--o{ api_sources : "연동 API"
    api_sources ||--o{ api_sync_logs : "동기화 기록"
    users ||--o{ wishlists : "찜"
    products ||--o{ wishlists : "찜 대상"
    users ||--o{ price_alerts : "알림 조건"
    products |o--o{ price_alerts : "가격 알림 대상"
    store_events |o--o{ price_alerts : "행사 알림 대상"
    price_alerts ||--o{ notifications : "발송 내역"
```

## 2. 영역별 테이블

5개 영역 17개 테이블 + 추가 제안 2개 = 19개

| 영역 | 수 | 테이블 | 저장하는 것 |
|---|---|---|---|
| 회원 · 상품 | 4 | USERS · BRANDS · CATEGORIES · PRODUCTS | 회원, 상품 기본정보 · 분류 |
| 쇼핑몰 · 판매 | 3 | SHOPPING_MALLS · MALL_PRODUCTS · MALL_PRODUCT_VARIANTS | 쇼핑몰별 가격 · 옵션 · 재고 |
| 구매 비용 계산 | 5 | EXCHANGE_RATES · SHIPPING_RULES · TAX_RULES · FEE_RULES · PRICE_CALCULATIONS | 환율 · 배송비 · 세금 · 수수료를 반영한 총액 |
| API 연동 관리 | 2 | API_SOURCES · API_SYNC_LOGS | API 정보, 수집 결과 · 오류 기록 |
| 사용자 편의 (선택) | 3 | WISHLISTS · PRICE_ALERTS · NOTIFICATIONS | 찜, 목표 가격, 알림 |
| 추가 제안 | +2 | PRICE_HISTORY · STORE_EVENTS | 가격 변동 그래프, 이번 달 행사 · 쿠폰 화면용 |
| 검색 (마이그레이션 002) | +1 | SEARCH_ALIASES | 한글 · 별칭 검색어 → API 검색어 · 브랜드 · 모델번호 |

설계 원칙

- **상품 ↔ 판매정보 분리** : 같은 상품(PRODUCTS)을 여러 판매처(MALL_PRODUCTS)에서 비교
- **규칙 테이블 + 계산 저장** : 환율 · 배송 · 세금 · 수수료 규칙으로 계산한 결과를 PRICE_CALCULATIONS 에 저장
- **API 수집 기록 분리** : 수집 상태 · 오류를 API_SYNC_LOGS 로 추적

## 3. 상세 (컬럼 포함)

```mermaid
erDiagram
    USERS {
        BIGINT user_id PK
        VARCHAR email UK
        VARCHAR password_hash
        VARCHAR nickname
        DATETIME created_at
        DATETIME updated_at
    }
    BRANDS {
        BIGINT brand_id PK
        VARCHAR brand_name UK
    }
    CATEGORIES {
        BIGINT category_id PK
        VARCHAR category_name
        BIGINT parent_category_id FK
    }
    PRODUCTS {
        BIGINT product_id PK
        BIGINT brand_id FK
        BIGINT category_id FK
        VARCHAR product_name
        VARCHAR model_number
        TEXT description
        VARCHAR image_url
        DATETIME created_at
    }
    SHOPPING_MALLS {
        BIGINT mall_id PK
        VARCHAR mall_name UK
        VARCHAR mall_url
        CHAR country_code
        BOOLEAN is_active
    }
    MALL_PRODUCTS {
        BIGINT mall_product_id PK
        BIGINT product_id FK
        BIGINT mall_id FK
        VARCHAR external_product_id
        VARCHAR product_url
        CHAR currency_code
        DECIMAL shipping_fee
        DATETIME last_fetched_at
    }
    MALL_PRODUCT_VARIANTS {
        BIGINT mall_variant_id PK
        BIGINT mall_product_id FK
        VARCHAR external_variant_id
        VARCHAR variant_name
        VARCHAR normalized_option_key
        DECIMAL current_price
        VARCHAR stock_status
        DATETIME updated_at
    }
    EXCHANGE_RATES {
        BIGINT exchange_rate_id PK
        CHAR currency_code
        DECIMAL rate_to_krw
        DATE base_date
        DATETIME recorded_at
    }
    SHIPPING_RULES {
        BIGINT shipping_rule_id PK
        BIGINT mall_id FK
        DECIMAL base_fee
        DECIMAL free_shipping_threshold
        CHAR currency_code
        BOOLEAN is_active
    }
    TAX_RULES {
        BIGINT tax_rule_id PK
        BIGINT category_id FK
        CHAR origin_country
        DECIMAL duty_rate
        DECIMAL vat_rate
        DECIMAL exemption_amount_usd
        BOOLEAN is_active
    }
    FEE_RULES {
        BIGINT fee_rule_id PK
        VARCHAR fee_name UK
        DECIMAL fee_rate
        DECIMAL fixed_fee_krw
        BOOLEAN is_active
    }
    STORE_EVENTS {
        BIGINT store_event_id PK
        BIGINT mall_id FK
        VARCHAR event_name
        VARCHAR event_type
        TEXT description
        VARCHAR discount_type
        DECIMAL discount_value
        DECIMAL min_purchase_amount
        DECIMAL max_discount_amount
        DATETIME start_at
        DATETIME end_at
        VARCHAR event_url
        VARCHAR banner_image_url
        BOOLEAN is_active
        DATETIME created_at
    }
    PRICE_CALCULATIONS {
        BIGINT calculation_id PK
        BIGINT mall_variant_id FK,UK
        BIGINT exchange_rate_id FK
        BIGINT shipping_rule_id FK
        BIGINT tax_rule_id FK
        BIGINT fee_rule_id FK
        BIGINT applied_event_id FK
        DECIMAL product_price_krw
        DECIMAL shipping_fee_krw
        DECIMAL discount_amount_krw
        DECIMAL customs_duty_krw
        DECIMAL vat_krw
        DECIMAL payment_fee_krw
        DECIMAL total_amount_krw
        DATETIME calculated_at
    }
    API_SOURCES {
        BIGINT api_source_id PK
        BIGINT mall_id FK
        VARCHAR source_name UK
        BOOLEAN is_active
    }
    API_SYNC_LOGS {
        BIGINT sync_log_id PK
        BIGINT api_source_id FK
        VARCHAR sync_type
        VARCHAR status
        INT http_status
        INT records_fetched
        INT records_skipped
        TEXT error_message
        DATETIME started_at
        DATETIME completed_at
    }
    WISHLISTS {
        BIGINT wishlist_id PK
        BIGINT user_id FK
        BIGINT product_id FK
        DATETIME created_at
    }
    PRICE_ALERTS {
        BIGINT alert_id PK
        BIGINT user_id FK
        BIGINT product_id FK
        BIGINT store_event_id FK
        VARCHAR alert_basis
        DECIMAL target_price_krw
        BOOLEAN is_active
        DATETIME last_notified_at
        DATETIME created_at
    }
    NOTIFICATIONS {
        BIGINT notification_id PK
        BIGINT alert_id FK
        TEXT message
        DATETIME sent_at
        DATETIME read_at
    }
    PRICE_HISTORY {
        BIGINT history_id PK
        BIGINT mall_variant_id FK
        DECIMAL product_price
        CHAR currency_code
        DECIMAL total_amount_krw
        DATETIME recorded_at
    }
    SEARCH_ALIASES {
        BIGINT alias_id PK
        VARCHAR alias_text
        VARCHAR search_term
        BIGINT brand_id FK
        VARCHAR model_number
        BOOLEAN is_active
        DATETIME created_at
    }

    CATEGORIES |o--o{ CATEGORIES : parent_of
    BRANDS |o--o{ PRODUCTS : owns
    BRANDS |o--o{ SEARCH_ALIASES : aliased_by
    CATEGORIES |o--o{ PRODUCTS : classifies
    PRODUCTS ||--o{ MALL_PRODUCTS : listed_as
    SHOPPING_MALLS ||--o{ MALL_PRODUCTS : sells
    MALL_PRODUCTS ||--o{ MALL_PRODUCT_VARIANTS : offers
    SHOPPING_MALLS ||--o{ SHIPPING_RULES : defines
    CATEGORIES |o--o{ TAX_RULES : applies_to
    SHOPPING_MALLS ||--o{ STORE_EVENTS : runs
    MALL_PRODUCT_VARIANTS ||--o| PRICE_CALCULATIONS : latest_calculation
    EXCHANGE_RATES |o--o{ PRICE_CALCULATIONS : converts_with
    SHIPPING_RULES |o--o{ PRICE_CALCULATIONS : applies
    TAX_RULES |o--o{ PRICE_CALCULATIONS : applies
    FEE_RULES |o--o{ PRICE_CALCULATIONS : uses
    STORE_EVENTS |o--o{ PRICE_CALCULATIONS : discounts
    MALL_PRODUCT_VARIANTS ||--o{ PRICE_HISTORY : records
    SHOPPING_MALLS |o--o{ API_SOURCES : provides
    API_SOURCES ||--o{ API_SYNC_LOGS : logs
    USERS ||--o{ WISHLISTS : saves
    PRODUCTS ||--o{ WISHLISTS : wished
    USERS ||--o{ PRICE_ALERTS : sets
    PRODUCTS |o--o{ PRICE_ALERTS : monitors
    STORE_EVENTS |o--o{ PRICE_ALERTS : triggers
    PRICE_ALERTS ||--o{ NOTIFICATIONS : generates
```

## 4. 외래 키와 삭제 동작

| 자식 테이블.컬럼 | 부모 테이블 | ON DELETE |
|---|---|---|
| categories.parent_category_id | categories | SET NULL |
| products.brand_id | brands | SET NULL |
| products.category_id | categories | SET NULL |
| mall_products.product_id | products | CASCADE |
| mall_products.mall_id | shopping_malls | CASCADE |
| mall_product_variants.mall_product_id | mall_products | CASCADE |
| shipping_rules.mall_id | shopping_malls | CASCADE |
| tax_rules.category_id | categories | RESTRICT |
| store_events.mall_id | shopping_malls | CASCADE |
| price_calculations.mall_variant_id | mall_product_variants | CASCADE |
| price_calculations.exchange_rate_id | exchange_rates | SET NULL |
| price_calculations.shipping_rule_id | shipping_rules | SET NULL |
| price_calculations.tax_rule_id | tax_rules | SET NULL |
| price_calculations.fee_rule_id | fee_rules | SET NULL |
| price_calculations.applied_event_id | store_events | SET NULL |
| api_sources.mall_id | shopping_malls | SET NULL |
| api_sync_logs.api_source_id | api_sources | CASCADE |
| wishlists.user_id | users | CASCADE |
| wishlists.product_id | products | CASCADE |
| price_alerts.user_id | users | CASCADE |
| price_alerts.product_id | products | RESTRICT |
| price_alerts.store_event_id | store_events | RESTRICT |
| notifications.alert_id | price_alerts | CASCADE |
| price_history.mall_variant_id | mall_product_variants | CASCADE |
| search_aliases.brand_id | brands | CASCADE |

- `search_aliases.model_number`는 FK가 아니에요. `(brand_id, model_number)` 값으로 `products`(uq_product_brand_model)와 맞춰요.

## 5. UNIQUE 제약

| 테이블 | 제약 이름 | 대상 |
|---|---|---|
| users | email | email |
| brands | brand_name | brand_name |
| shopping_malls | mall_name | mall_name |
| fee_rules | fee_name | fee_name |
| api_sources | source_name | source_name |
| price_calculations | mall_variant_id | mall_variant_id (옵션당 계산 1건) |
| categories | uq_category_parent_name | IFNULL(parent_category_id, 0), category_name |
| products | uq_product_brand_model | brand_id, model_number |
| mall_products | uq_mall_external_product | mall_id, external_product_id |
| mall_product_variants | uq_variant_external | mall_product_id, external_variant_id |
| exchange_rates | uq_exchange_currency_date | currency_code, base_date |
| shipping_rules | uq_shipping_active_mall | IF(is_active, mall_id, NULL) |
| tax_rules | uq_tax_active_scope | IF(is_active, category_id:origin_country, NULL) |
| wishlists | uq_user_product | user_id, product_id |
| price_alerts | uq_alert_user_target | user_id, alert_basis, IFNULL(product_id, 0), IFNULL(store_event_id, 0) |
| search_aliases | uq_search_alias_target | alias_text, IFNULL(brand_id, 0), IFNULL(model_number, '') |

## 6. CHECK 제약 (18개)

| 테이블 | 제약 이름 | 조건 |
|---|---|---|
| mall_products | chk_mall_product_shipping | shipping_fee 가 NULL 이거나 0 이상 |
| mall_product_variants | chk_variant_price | current_price ≥ 0 |
| mall_product_variants | chk_variant_stock | stock_status ∈ IN_STOCK, OUT_OF_STOCK, UNKNOWN |
| exchange_rates | chk_exchange_rate | rate_to_krw > 0 |
| shipping_rules | chk_shipping_amount | base_fee ≥ 0, free_shipping_threshold 가 NULL 이거나 0 이상 |
| tax_rules | chk_tax_rate | duty_rate · vat_rate 0~1, exemption_amount_usd ≥ 0 |
| fee_rules | chk_fee_amount | fee_rate 0~1, fixed_fee_krw ≥ 0 |
| store_events | chk_event_period | end_at ≥ start_at |
| store_events | chk_event_type | event_type ∈ SALE, COUPON |
| store_events | chk_event_discount | 할인 유형 · 값은 둘 다 없거나 둘 다 있음(RATE/AMOUNT). COUPON 은 할인 필수, SALE 은 선택 |
| store_events | chk_event_discount_value | RATE 0 초과 1 이하, AMOUNT 0 초과, 최소구매 ≥ 0, 최대할인은 RATE 에만 (> 0) |
| price_calculations | chk_calculation_parts | 모든 금액 ≥ 0, 할인 ≤ 상품가 |
| price_calculations | chk_calculation_total | total = 상품가 + 배송비 − 할인 + 관세 + 부가세 + 수수료 |

## 7. 할인 정책

| 구분 | 규칙 |
|---|---|
| SALE | 정률(RATE) · 정액(AMOUNT) 모두 지원. 할인 정보 없는 SALE 도 허용 (가격에 이미 반영된 세일, 안내 · 알림용) |
| COUPON | 할인 정보 필수. 기간 · is_active · 최소 구매금액 조건을 충족할 때만 적용 |
| 중복 적용 | 불가. 계산 1건에 행사 1건 (`price_calculations.applied_event_id`), 할인액은 `discount_amount_krw` |
| api_sync_logs | chk_sync_status | status ∈ RUNNING, SUCCESS, PARTIAL, FAILED |
| api_sync_logs | chk_sync_counts | 건수 ≥ 0, completed_at ≥ started_at |
| price_alerts | chk_alert_target | 알림 유형별 product_id / store_event_id / target_price_krw 조합 |
| price_alerts | chk_alert_price | target_price_krw 가 NULL 이거나 0 초과 |
| price_history | chk_history_price | product_price ≥ 0, total_amount_krw 가 NULL 이거나 0 이상 |
| search_aliases | chk_search_alias_text | alias_text · search_term 이 빈 문자열이 아님 (마이그레이션 002) |
| search_aliases | chk_search_alias_model | model_number 가 NULL 이거나 빈 문자열이 아님 (마이그레이션 002) |
| mall_product_variants | chk_variant_option_key | normalized_option_key 가 NULL 이거나 빈 문자열이 아님 (마이그레이션 003) |
