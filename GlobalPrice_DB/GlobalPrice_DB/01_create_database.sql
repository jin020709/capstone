-- ==========================================
-- GlobalPrice
-- STEP 01 : Database Creation
-- ==========================================

-- 데이터베이스 생성
CREATE DATABASE IF NOT EXISTS globalprice
DEFAULT CHARACTER SET utf8mb4
DEFAULT COLLATE utf8mb4_unicode_ci;

-- 데이터베이스 선택
USE globalprice;

-- 데이터베이스 확인
SELECT DATABASE() AS selected_database;

-- 데이터베이스 문자셋 · 정렬규칙 · 버전 확인
-- (DB가 이미 있으면 IF NOT EXISTS 때문에 문자셋이 바뀌지 않으므로 여기서 확인)
-- 02 의 CHECK 제약은 8.0.16+, 함수형 UNIQUE 인덱스는 8.0.13+ 필요
SELECT @@character_set_database AS charset,
       @@collation_database     AS collation,
       VERSION()                AS mysql_version;

-- 테이블 목록 확인
SHOW TABLES;