-- =====================================================
-- 90. 시연용 가격 이력 (가짜 데이터)  ※ 발표 · 시연 확인용. 운영 DB 에 넣지 말 것
--
-- 지금 DB 에 있는 상품마다 "가장 최근 실제 기록"을 기준으로 지난 365일치 이력을 만들어 넣는다.
--   - 2주 단위로 가격이 유지되다 바뀌는 형태 (실제 쇼핑몰 가격처럼 계단형)
--   - 과거로 갈수록 평균 몇 % 비싸고, 중간중간 지금보다 싼 구간도 있음
--   - 가짜 기록은 시각이 전부 04:44:44 → 이걸로 구분해서 지운다
--
-- 실행 전: 서버에서 상품을 한 번이라도 검색해 둘 것 (검색된 상품에만 기준 기록이 있음)
-- 다시 실행해도 됨: 맨 위에서 기존 가짜 기록을 먼저 지움
-- 지우기만 할 때:  DELETE FROM price_history WHERE TIME(recorded_at) = '04:44:44';
-- =====================================================
USE globalprice;

DELETE FROM price_history WHERE TIME(recorded_at) = '04:44:44';

INSERT INTO price_history (mall_variant_id, product_price, currency_code, total_amount_krw, recorded_at)
WITH RECURSIVE days (d) AS (
    SELECT 1
    UNION ALL
    SELECT d + 1 FROM days WHERE d < 365
),
latest AS (   -- 옵션(variant)별 가장 최근 실제 기록 1건
    SELECT h.mall_variant_id, h.product_price, h.currency_code, h.total_amount_krw,
           ROW_NUMBER() OVER (PARTITION BY h.mall_variant_id ORDER BY h.recorded_at DESC, h.history_id DESC) AS rn
    FROM price_history h
    WHERE h.total_amount_krw IS NOT NULL
),
factor AS (   -- 날짜별 배율: 1년 전 +8% 추세 + 2주마다 바뀌는 ±3.5% 출렁임
    SELECT l.mall_variant_id, l.product_price, l.currency_code, l.total_amount_krw, days.d,
           1 + 0.08 * FLOOR(days.d / 30) / 12
             + 0.035 * SIN(FLOOR(days.d / 14) * 1.7 + l.mall_variant_id) AS k
    FROM latest l
    CROSS JOIN days
    WHERE l.rn = 1
)
SELECT mall_variant_id,
       ROUND(product_price * k, 2),
       currency_code,
       ROUND(total_amount_krw * k, -2),
       TIMESTAMP(CURDATE() - INTERVAL d DAY, '04:44:44')
FROM factor;

-- 결과 확인: 상품별 가짜 기록 수 (보통 상품당 365)
SELECT COUNT(*) AS demo_rows, COUNT(DISTINCT mall_variant_id) AS variants
FROM price_history WHERE TIME(recorded_at) = '04:44:44';
