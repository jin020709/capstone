CREATE DATABASE  IF NOT EXISTS `globalprice` /*!40100 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci */ /*!80016 DEFAULT ENCRYPTION='N' */;
USE `globalprice`;
-- =====================================================
-- GlobalPrice 테스트 데이터 47건 (01 → 02 실행 후 한 번만 실행)
--
-- MySQL Workbench Data Export(Dump Data Only)로 내보낸 뒤 아래를 고침
--   - price_history : INSERT 에 컬럼 이름을 붙임 (원본 DB 의 컬럼 순서가 02 와 달라서
--                     이름 없이 넣으면 currency_code · recorded_at 에 엉뚱한 값이 들어감)
--   - fee_rules     : 해외 결제 수수료 1.0000(100%) → 0.0100(1%)
--   - mall_products : product_url 을 각 쇼핑몰 검색 결과 주소로 채움 (테스트 상품이라 실제 상품 페이지 없음)
--   - SQL_MODE      : 엄격 모드로 실행 → 값이 맞지 않으면 조용히 잘리지 않고 오류로 멈춤
--   - SET NAMES     : utf8 → utf8mb4
-- =====================================================
-- MySQL dump 10.13  Distrib 8.0.46, for Win64 (x86_64)
--
-- Host: localhost    Database: globalprice
-- ------------------------------------------------------
-- Server version	8.0.46

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Dumping data for table `api_sources`
--

LOCK TABLES `api_sources` WRITE;
/*!40000 ALTER TABLE `api_sources` DISABLE KEYS */;
INSERT INTO `api_sources` VALUES (1,1,'Amazon Product API',1),(2,2,'Rakuten Product API',1);
/*!40000 ALTER TABLE `api_sources` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Dumping data for table `api_sync_logs`
--

LOCK TABLES `api_sync_logs` WRITE;
/*!40000 ALTER TABLE `api_sync_logs` DISABLE KEYS */;
INSERT INTO `api_sync_logs` VALUES (1,1,'PRODUCT_SYNC','SUCCESS',200,10,0,NULL,'2026-11-11 16:00:00','2026-11-11 16:00:05'),(2,2,'PRODUCT_SYNC','SUCCESS',200,8,1,NULL,'2026-11-11 16:01:00','2026-11-11 16:01:06'),(3,1,'PRODUCT_SYNC','FAILED',503,0,0,'TEST ERROR: Amazon API service unavailable','2026-11-11 16:02:00','2026-11-11 16:02:03'),(4,2,'PRODUCT_SYNC','FAILED',401,0,0,'TEST ERROR: Rakuten API authentication failed','2026-11-11 16:03:00','2026-11-11 16:03:02');
/*!40000 ALTER TABLE `api_sync_logs` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Dumping data for table `brands`
--

LOCK TABLES `brands` WRITE;
/*!40000 ALTER TABLE `brands` DISABLE KEYS */;
INSERT INTO `brands` VALUES (1,'Apple'),(2,'Samsung');
/*!40000 ALTER TABLE `brands` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Dumping data for table `categories`
--

LOCK TABLES `categories` WRITE;
/*!40000 ALTER TABLE `categories` DISABLE KEYS */;
INSERT INTO `categories` VALUES (1,'스마트폰',NULL),(2,'노트북',NULL);
/*!40000 ALTER TABLE `categories` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Dumping data for table `exchange_rates`
--

LOCK TABLES `exchange_rates` WRITE;
/*!40000 ALTER TABLE `exchange_rates` DISABLE KEYS */;
INSERT INTO `exchange_rates` VALUES (1,'USD',1400.000000,'2026-11-10','2026-11-10 22:26:04'),(2,'JPY',9.500000,'2026-11-10','2026-11-10 22:26:04');
/*!40000 ALTER TABLE `exchange_rates` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Dumping data for table `fee_rules`
--

LOCK TABLES `fee_rules` WRITE;
/*!40000 ALTER TABLE `fee_rules` DISABLE KEYS */;
INSERT INTO `fee_rules` VALUES (1,'해외 결제 수수료',0.0100,0.00,1),(2,'기타 결제 수수료',0.0000,500.00,1);
/*!40000 ALTER TABLE `fee_rules` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Dumping data for table `mall_product_variants`
--

LOCK TABLES `mall_product_variants` WRITE;
/*!40000 ALTER TABLE `mall_product_variants` DISABLE KEYS */;
INSERT INTO `mall_product_variants` VALUES (1,1,'DEFAULT','256GB',799.00,'IN_STOCK','2026-11-10 22:25:27'),(2,2,'DEFAULT','256GB',119800.00,'IN_STOCK','2026-11-10 22:25:27'),(3,3,'DEFAULT','256GB',749.00,'IN_STOCK','2026-11-10 22:25:27');
/*!40000 ALTER TABLE `mall_product_variants` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Dumping data for table `mall_products`
--

LOCK TABLES `mall_products` WRITE;
/*!40000 ALTER TABLE `mall_products` DISABLE KEYS */;
INSERT INTO `mall_products` VALUES (1,1,1,'AMZ-IPHONE17-TEST','https://www.amazon.com/s?k=iPhone+17','USD',15.00,NULL),(2,1,2,'RAK-IPHONE17-TEST','https://search.rakuten.co.jp/search/mall/iPhone+17/','JPY',1200.00,NULL),(3,2,1,'AMZ-GALAXYS25-TEST','https://www.amazon.com/s?k=Galaxy+S25','USD',12.00,NULL);
/*!40000 ALTER TABLE `mall_products` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Dumping data for table `notifications`
--

LOCK TABLES `notifications` WRITE;
/*!40000 ALTER TABLE `notifications` DISABLE KEYS */;
INSERT INTO `notifications` VALUES (1,1,'iPhone 17의 목표 가격 1,000,000원에 도달했습니다.','2026-11-11 16:34:29',NULL),(2,2,'Galaxy S25의 목표 가격 900,000원에 도달했습니다.','2026-11-11 16:34:29',NULL),(3,3,'Amazon US에서 할인 이벤트가 시작되었습니다.','2026-11-11 16:34:29',NULL),(4,4,'Rakuten Japan에서 할인 이벤트가 시작되었습니다.','2026-11-11 16:34:29',NULL);
/*!40000 ALTER TABLE `notifications` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Dumping data for table `price_alerts`
--

LOCK TABLES `price_alerts` WRITE;
/*!40000 ALTER TABLE `price_alerts` DISABLE KEYS */;
INSERT INTO `price_alerts` VALUES (1,1,1,NULL,'TARGET_PRICE',1000000.00,1,NULL,'2026-11-11 16:31:21'),(2,1,2,NULL,'TARGET_PRICE',900000.00,1,NULL,'2026-11-11 16:31:21'),(3,1,NULL,1,'EVENT_START',NULL,1,NULL,'2026-11-11 16:31:21'),(4,1,NULL,2,'EVENT_START',NULL,1,NULL,'2026-11-11 16:31:21');
/*!40000 ALTER TABLE `price_alerts` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Dumping data for table `price_calculations`
--

LOCK TABLES `price_calculations` WRITE;
/*!40000 ALTER TABLE `price_calculations` DISABLE KEYS */;
INSERT INTO `price_calculations` VALUES (1,1,1,NULL,NULL,NULL,NULL,1118600.00,21000.00,0.00,0.00,0.00,0.00,1139600.00,'2026-11-11 15:29:27'),(2,2,2,NULL,NULL,NULL,NULL,1138100.00,11400.00,0.00,0.00,0.00,0.00,1149500.00,'2026-11-11 15:29:27'),(3,3,1,NULL,NULL,NULL,NULL,1048600.00,16800.00,0.00,0.00,0.00,0.00,1065400.00,'2026-11-11 15:29:27');
/*!40000 ALTER TABLE `price_calculations` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Dumping data for table `price_history`
--

LOCK TABLES `price_history` WRITE;
/*!40000 ALTER TABLE `price_history` DISABLE KEYS */;
INSERT INTO `price_history` (`history_id`,`mall_variant_id`,`product_price`,`total_amount_krw`,`recorded_at`,`currency_code`) VALUES (1,1,799.00,1139600.00,'2026-11-11 15:31:14','USD'),(2,2,119800.00,1149500.00,'2026-11-11 15:31:14','JPY'),(3,3,749.00,1065400.00,'2026-11-11 15:31:14','USD');
/*!40000 ALTER TABLE `price_history` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Dumping data for table `products`
--

LOCK TABLES `products` WRITE;
/*!40000 ALTER TABLE `products` DISABLE KEYS */;
INSERT INTO `products` VALUES (1,1,1,'iPhone 17','IPHONE17-TEST',NULL,NULL,'2026-11-10 20:21:19'),(2,2,1,'Galaxy S25','GALAXYS25-TEST',NULL,NULL,'2026-11-10 20:21:19');
/*!40000 ALTER TABLE `products` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Dumping data for table `shipping_rules`
--

LOCK TABLES `shipping_rules` WRITE;
/*!40000 ALTER TABLE `shipping_rules` DISABLE KEYS */;
INSERT INTO `shipping_rules` VALUES (1,1,15.00,NULL,'USD',1),(2,2,1200.00,NULL,'JPY',1);
/*!40000 ALTER TABLE `shipping_rules` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Dumping data for table `shopping_malls`
--

LOCK TABLES `shopping_malls` WRITE;
/*!40000 ALTER TABLE `shopping_malls` DISABLE KEYS */;
INSERT INTO `shopping_malls` VALUES (1,'Amazon US','https://www.amazon.com','US',1),(2,'Rakuten Japan','https://www.rakuten.co.jp','JP',1);
/*!40000 ALTER TABLE `shopping_malls` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Dumping data for table `store_events`
--

LOCK TABLES `store_events` WRITE;
/*!40000 ALTER TABLE `store_events` DISABLE KEYS */;
INSERT INTO `store_events` VALUES (1,1,'Amazon 테스트 할인','COUPON','GlobalPrice 테스트용 가상 할인 이벤트','RATE',0.1000,100.00,50.00,'2026-11-11 00:00:00','2026-12-31 23:59:59',NULL,NULL,1,'2026-11-11 16:22:55'),(2,2,'Rakuten 테스트 할인','COUPON','GlobalPrice 테스트용 가상 할인 이벤트','RATE',0.0500,10000.00,2000.00,'2026-11-11 00:00:00','2026-12-31 23:59:59',NULL,NULL,1,'2026-11-11 16:22:55');
/*!40000 ALTER TABLE `store_events` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Dumping data for table `tax_rules`
--

LOCK TABLES `tax_rules` WRITE;
/*!40000 ALTER TABLE `tax_rules` DISABLE KEYS */;
INSERT INTO `tax_rules` VALUES (1,1,'US',0.0000,0.1000,200.00,1),(2,2,'US',0.0000,0.1000,200.00,1);
/*!40000 ALTER TABLE `tax_rules` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Dumping data for table `users`
--

LOCK TABLES `users` WRITE;
/*!40000 ALTER TABLE `users` DISABLE KEYS */;
INSERT INTO `users` VALUES (1,'test@globalprice.com','TEST_HASH_VALUE','테스트사용자','2026-11-11 16:25:27','2026-11-11 16:25:27');
/*!40000 ALTER TABLE `users` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Dumping data for table `wishlists`
--

LOCK TABLES `wishlists` WRITE;
/*!40000 ALTER TABLE `wishlists` DISABLE KEYS */;
INSERT INTO `wishlists` VALUES (1,1,1,'2026-11-11 16:36:19'),(2,1,2,'2026-11-11 16:36:19');
/*!40000 ALTER TABLE `wishlists` ENABLE KEYS */;
UNLOCK TABLES;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-11-11 20:39:06
