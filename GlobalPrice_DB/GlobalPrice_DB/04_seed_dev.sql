-- =====================================================
-- GlobalPrice
-- SEED (개발용) : 1차 쇼핑몰 4곳 + 국내 직구관 · API 소스 · 규칙 · 테스트 행사
--  - 01 → 02 → 03_migration_001 실행 후 적용. 03_seed_data.sql(기존 테스트 데이터)와 함께 써도 됨
--  - 이름(UNIQUE) 기준으로 이미 있으면 건너뜀 (INSERT IGNORE / NOT EXISTS) → 여러 번 실행해도 안전
--  - ★ 표시 값은 임시값. 팀 확정 후 수정 (BACKEND_SPEC 13장)
-- =====================================================

USE globalprice;
SET NAMES utf8mb4;

-- ---------- 쇼핑몰 ----------
INSERT IGNORE INTO shopping_malls (mall_name, mall_url, country_code, is_active) VALUES
    ('eBay US',          'https://www.ebay.com',        'US', TRUE),
    ('Shopify',          'https://shop.app',            'US', TRUE),
    ('AliExpress',       'https://www.aliexpress.com',  'CN', TRUE),
    ('Etsy',             'https://www.etsy.com',        'US', TRUE),
    ('국내 직구관 시드', NULL,                          'KR', TRUE);

-- ---------- API 소스 (source_name 은 코드의 MallSource.sourceName() 과 같아야 함) ----------
INSERT IGNORE INTO api_sources (mall_id, source_name, is_active) VALUES
    (NULL, 'KOREAEXIM_RATE', TRUE),
    ((SELECT mall_id FROM shopping_malls WHERE mall_name = 'eBay US'),    'EBAY_BROWSE', TRUE),
    ((SELECT mall_id FROM shopping_malls WHERE mall_name = 'Shopify'),    'SHOPIFY_CATALOG', TRUE),
    ((SELECT mall_id FROM shopping_malls WHERE mall_name = 'AliExpress'), 'ALIEXPRESS_AFFILIATE', TRUE),
    ((SELECT mall_id FROM shopping_malls WHERE mall_name = 'Etsy'),       'ETSY_OPEN_API', TRUE),
    ((SELECT mall_id FROM shopping_malls WHERE mall_name = '국내 직구관 시드'), 'SEED', TRUE);

-- ---------- 배송비 규칙 (쇼핑몰당 활성 1건, 이미 활성 규칙이 있으면 건너뜀) ★ ----------
INSERT INTO shipping_rules (mall_id, base_fee, free_shipping_threshold, currency_code, is_active)
SELECT m.mall_id, v.base_fee, v.threshold, v.cur, TRUE
FROM (SELECT 'eBay US' AS name, 20.00 AS base_fee, NULL AS threshold, 'USD' AS cur
      UNION ALL SELECT 'Shopify',          15.00, NULL, 'USD'
      UNION ALL SELECT 'AliExpress',        3.00, 10.00, 'USD'
      UNION ALL SELECT 'Etsy',             15.00, NULL, 'USD'
      UNION ALL SELECT '국내 직구관 시드',  0.00, NULL, 'KRW') v
JOIN shopping_malls m ON m.mall_name = v.name
WHERE NOT EXISTS (SELECT 1 FROM shipping_rules s WHERE s.mall_id = m.mall_id AND s.is_active);

-- ---------- 세금 규칙 (전 품목) ★ 전자기기 가정: 관세 0% ----------
--  기본(기타 국가) : 관세 0, 부가세 10%, 면세 $150
--  미국            : 관세 0, 부가세 10%, 면세 $200
INSERT INTO tax_rules (category_id, origin_country, duty_rate, vat_rate, exemption_amount_usd, is_active)
SELECT NULL, NULL, 0.0000, 0.1000, 150.00, TRUE FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM tax_rules WHERE category_id IS NULL AND origin_country IS NULL AND is_active);

INSERT INTO tax_rules (category_id, origin_country, duty_rate, vat_rate, exemption_amount_usd, is_active)
SELECT NULL, 'US', 0.0000, 0.1000, 200.00, TRUE FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM tax_rules WHERE category_id IS NULL AND origin_country = 'US' AND is_active);

-- ---------- 결제 수수료 ★ (팀 확정 전: 1.25~1.35% 범위 중 선택 필요. 기존 시드와 같은 이름이면 건너뜀) ----------
INSERT IGNORE INTO fee_rules (fee_name, fee_rate, fixed_fee_krw, is_active) VALUES
    ('해외 결제 수수료', 0.0100, 0.00, TRUE);

-- ---------- 테스트 행사 ----------
INSERT INTO store_events (mall_id, event_name, event_type, description, discount_type, discount_value,
                          min_purchase_amount, max_discount_amount, start_at, end_at, is_active)
SELECT m.mall_id, v.name, v.type, v.descr, v.dtype, v.dval, v.minp, v.maxd, v.s, v.e, TRUE
FROM (SELECT 'eBay US' AS mall, 'eBay 테스트 세일' AS name, 'SALE' AS type, '5% 할인 (최대 $30)' AS descr,
             'RATE' AS dtype, 0.0500 AS dval, NULL AS minp, 30.00 AS maxd,
             TIMESTAMP('2026-11-01 00:00:00') AS s, TIMESTAMP('2026-12-31 23:59:59') AS e
      UNION ALL SELECT 'eBay US', 'eBay 테스트 쿠폰', 'COUPON', '$200 이상 $15 할인',
             'AMOUNT', 15.0000, 200.00, NULL, TIMESTAMP('2026-11-01 00:00:00'), TIMESTAMP('2026-12-31 23:59:59')
      UNION ALL SELECT 'AliExpress', '광군제 (테스트)', 'SALE', '행사 진행 중 (가격에 이미 반영)',
             NULL, NULL, NULL, NULL, TIMESTAMP('2026-11-11 00:00:00'), TIMESTAMP('2026-11-19 23:59:59')
      UNION ALL SELECT 'AliExpress', 'AliExpress 테스트 쿠폰', 'COUPON', '$50 이상 10% (최대 $10)',
             'RATE', 0.1000, 50.00, 10.00, TIMESTAMP('2026-11-01 00:00:00'), TIMESTAMP('2026-12-31 23:59:59')) v
JOIN shopping_malls m ON m.mall_name = v.mall
WHERE NOT EXISTS (SELECT 1 FROM store_events x WHERE x.mall_id = m.mall_id AND x.event_name = v.name);

SELECT mall_id, mall_name, country_code FROM shopping_malls;
SELECT * FROM api_sources;
