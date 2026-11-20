-- =====================================================
-- GlobalPrice
-- MIGRATION 001 : exchange_rates.tts_rate (전신환매도율, 결제 금액 계산용)
--  - 01 · 02 실행 후 적용. 기존 데이터는 지우지 않음
--  - 컬럼이 이미 있으면 아무것도 하지 않음 (여러 번 실행해도 안전)
-- =====================================================

USE globalprice;

SET @has_tts := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'exchange_rates'
      AND COLUMN_NAME = 'tts_rate');

SET @ddl := IF(@has_tts = 0,
    'ALTER TABLE exchange_rates ADD COLUMN tts_rate DECIMAL(18,6) NULL AFTER rate_to_krw',
    'SELECT ''tts_rate already exists'' AS migration_001');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SHOW COLUMNS FROM exchange_rates;
