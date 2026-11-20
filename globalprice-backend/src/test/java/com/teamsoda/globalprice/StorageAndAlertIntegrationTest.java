package com.teamsoda.globalprice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.teamsoda.globalprice.alert.AlertEvaluator;
import com.teamsoda.globalprice.scheduler.WatchedRefreshJob;
import com.teamsoda.globalprice.support.IntegrationTestBase;
import com.teamsoda.globalprice.support.MockHttp.Response;

/**
 * 4단계: eBay(가짜 서버) 검색 → DB 저장 → 다시 해도 중복 없음, price_history 중복 방지.
 * 8단계: 가짜 가격 하락으로 알림 생성, 하루 1회 제한. 12단계: 행사 시작 알림.
 */
@org.junit.jupiter.api.condition.EnabledIf("com.teamsoda.globalprice.support.IntegrationTestBase#databaseAvailable")
class StorageAndAlertIntegrationTest extends IntegrationTestBase {

    @Autowired WatchedRefreshJob job;
    @Autowired AlertEvaluator evaluator;

    static String searchBody(String itemId, String price) {
        return """
                {"total":1,"itemSummaries":[{"itemId":"%s","title":"Test Camera %s","price":{"value":"%s","currency":"USD"},
                 "shippingOptions":[{"shippingCost":{"value":"10.00","currency":"USD"}}],"condition":"New","conditionId":"1000",
                 "itemWebUrl":"https://www.ebay.com/itm/%s","itemLocation":{"country":"US"}}]}"""
                .formatted(itemId, itemId, price, itemId);
    }

    static String itemBody(String itemId, String price) {
        return """
                {"itemId":"%s","title":"Test Camera %s","price":{"value":"%s","currency":"USD"},
                 "shippingOptions":[{"shippingCost":{"value":"10.00","currency":"USD"}}],"condition":"New","conditionId":"1000",
                 "itemWebUrl":"https://www.ebay.com/itm/%s","itemLocation":{"country":"US"},
                 "estimatedAvailabilities":[{"estimatedAvailabilityStatus":"IN_STOCK"}]}"""
                .formatted(itemId, itemId, price, itemId);
    }

    @Test
    void ebaySearch_savesOnce_andHistoryIsDeduplicated() throws Exception {
        String itemId = unique("v1|it|");
        EXTERNAL.on("/identity/v1/oauth2/token", Response.json("{\"access_token\":\"t\",\"expires_in\":7200}"));
        EXTERNAL.on("/buy/browse/v1/item_summary/search", Response.json(searchBody(itemId, "150.00")));

        String q = unique("camera");
        mvc.perform(get("/api/products").param("q", q)).andExpect(status().isOk())
                .andExpect(jsonPath("$.sources[?(@.storeId=='ebay')].status").value("ok"))
                .andExpect(jsonPath("$.items[0].offers[0].storeId").value("ebay"))
                // 04 시드의 eBay SALE 5%(최대 $30) 적용: $150 → 210,000 − 10,500 + 배송 14,000
                // 물품 $142.5 면세 / 수수료 2,135 (213,500 × 1%) → 215,635
                .andExpect(jsonPath("$.items[0].bestTotal").value(215635));

        clearSearchCache();
        mvc.perform(get("/api/products").param("q", q)).andExpect(status().isOk());

        Integer mallProducts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM mall_products WHERE external_product_id = ?", Integer.class, itemId);
        Integer products = jdbc.queryForObject("""
                SELECT COUNT(*) FROM products p JOIN mall_products mp ON mp.product_id = p.product_id
                WHERE mp.external_product_id = ?""", Integer.class, itemId);
        Integer history = jdbc.queryForObject("""
                SELECT COUNT(*) FROM price_history h JOIN mall_product_variants v ON v.mall_variant_id = h.mall_variant_id
                JOIN mall_products mp ON mp.mall_product_id = v.mall_product_id WHERE mp.external_product_id = ?""",
                Integer.class, itemId);
        assertThat(mallProducts).isEqualTo(1);
        assertThat(products).isEqualTo(1);
        assertThat(history).isEqualTo(1);   // 같은 가격 · 6시간 이내 → 한 번만

        // 가격이 바뀌면 이력 추가
        EXTERNAL.on("/buy/browse/v1/item_summary/search", Response.json(searchBody(itemId, "140.00")));
        clearSearchCache();
        mvc.perform(get("/api/products").param("q", q)).andExpect(status().isOk());
        Integer history2 = jdbc.queryForObject("""
                SELECT COUNT(*) FROM price_history h JOIN mall_product_variants v ON v.mall_variant_id = h.mall_variant_id
                JOIN mall_products mp ON mp.mall_product_id = v.mall_product_id WHERE mp.external_product_id = ?""",
                Integer.class, itemId);
        assertThat(history2).isEqualTo(2);
    }

    @Test
    void detail_refreshFails_marksStale() throws Exception {
        String itemId = unique("v1|stale|");
        EXTERNAL.on("/identity/v1/oauth2/token", Response.json("{\"access_token\":\"t\",\"expires_in\":7200}"));
        EXTERNAL.on("/buy/browse/v1/item_summary/search", Response.json(searchBody(itemId, "99.00")));
        String q = unique("stale");
        mvc.perform(get("/api/products").param("q", q)).andExpect(status().isOk());
        Long productId = jdbc.queryForObject("SELECT product_id FROM mall_products WHERE external_product_id = ?",
                Long.class, itemId);
        // 7시간 전에 받은 값으로 만들고, 단건 갱신은 503
        jdbc.update("UPDATE mall_products SET last_fetched_at = ? WHERE external_product_id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusHours(7)), itemId);
        EXTERNAL.on("/buy/browse/v1/item/*", Response.status(503, "{}"));
        mvc.perform(get("/api/products/" + productId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.priceStatus").value("stale"))
                .andExpect(jsonPath("$.offers[0].status").value("stale"));
        // 갱신 성공 → ok
        EXTERNAL.on("/buy/browse/v1/item/*", Response.json(itemBody(itemId, "95.00")));
        mvc.perform(get("/api/products/" + productId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.priceStatus").value("ok"))
                .andExpect(jsonPath("$.offers[0].price").value(95.0));
    }

    @Test
    void watchedRefresh_createsTargetPriceAndLowestAlerts_oncePerDay() throws Exception {
        String itemId = unique("v1|watch|");
        EXTERNAL.on("/identity/v1/oauth2/token", Response.json("{\"access_token\":\"t\",\"expires_in\":7200}"));
        EXTERNAL.on("/buy/browse/v1/item_summary/search", Response.json(searchBody(itemId, "300.00")));
        mvc.perform(get("/api/products").param("q", unique("watch"))).andExpect(status().isOk());
        Long productId = jdbc.queryForObject("SELECT product_id FROM mall_products WHERE external_product_id = ?",
                Long.class, itemId);

        // 회원 + 목표가 알림 + 최저가 알림. 지난주 이력은 더 비쌌던 것으로
        jdbc.update("INSERT INTO users (email, password_hash, nickname) VALUES (?, 'x', 'w')", unique("w") + "@e.com");
        Long userId = jdbc.queryForObject("SELECT MAX(user_id) FROM users", Long.class);
        jdbc.update("""
                INSERT INTO price_alerts (user_id, product_id, alert_basis, target_price_krw) VALUES (?, ?, 'TARGET_PRICE', 400000)""",
                userId, productId);
        jdbc.update("INSERT INTO price_alerts (user_id, product_id, alert_basis) VALUES (?, ?, 'PERIOD_LOWEST')",
                userId, productId);
        Long variantId = jdbc.queryForObject("""
                SELECT v.mall_variant_id FROM mall_product_variants v JOIN mall_products mp
                ON mp.mall_product_id = v.mall_product_id WHERE mp.external_product_id = ?""", Long.class, itemId);
        jdbc.update("""
                INSERT INTO price_history (mall_variant_id, product_price, currency_code, total_amount_krw, recorded_at)
                VALUES (?, 320.00, 'USD', 500000, ?)""", variantId, Timestamp.valueOf(LocalDateTime.now().minusDays(7)));

        // 가짜 가격 하락 $300 → $240 (eBay SALE 5% 적용, 쿠폰 제외):
        //   $300: 420,000 − 21,000 + 14,000 = 413,000 / 부가세 41,300 / 수수료 4,130 → 458,430 (검색 때 이력)
        //   $240: 336,000 − 16,800 + 14,000 = 333,200 / 부가세 33,320 / 수수료 3,332 → 369,852 ≤ 목표 400,000
        //   지난 30일 최저 458,430 보다 낮음 → PERIOD_LOWEST 도 알림
        EXTERNAL.on("/buy/browse/v1/item/*", Response.json(itemBody(itemId, "240.00")));
        WatchedRefreshJob.RunResult r1 = job.run();
        assertThat(r1.refreshed()).isGreaterThanOrEqualTo(1);

        Integer notes = jdbc.queryForObject("""
                SELECT COUNT(*) FROM notifications n JOIN price_alerts a ON a.alert_id = n.alert_id WHERE a.user_id = ?""",
                Integer.class, userId);
        assertThat(notes).isEqualTo(2);
        String message = jdbc.queryForObject("""
                SELECT n.message FROM notifications n JOIN price_alerts a ON a.alert_id = n.alert_id
                WHERE a.user_id = ? AND a.alert_basis = 'TARGET_PRICE'""", String.class, userId);
        assertThat(message).contains("369,852원").contains("400,000원");

        // 같은 날 다시 돌려도 추가 알림 없음 (하루 1회)
        EXTERNAL.on("/buy/browse/v1/item/*", Response.json(itemBody(itemId, "230.00")));
        job.run();
        Integer notes2 = jdbc.queryForObject("""
                SELECT COUNT(*) FROM notifications n JOIN price_alerts a ON a.alert_id = n.alert_id WHERE a.user_id = ?""",
                Integer.class, userId);
        assertThat(notes2).isEqualTo(2);

        Integer logs = jdbc.queryForObject("""
                SELECT COUNT(*) FROM api_sync_logs l JOIN api_sources s ON s.api_source_id = l.api_source_id
                WHERE s.source_name = 'EBAY_BROWSE' AND l.sync_type = 'PRODUCT_REFRESH'""", Integer.class);
        assertThat(logs).isGreaterThanOrEqualTo(2);
    }

    @Test
    void eventStartAndCouponExpiryAlerts() {
        Long mallId = jdbc.queryForObject("SELECT mall_id FROM shopping_malls WHERE mall_name = 'eBay US'", Long.class);
        LocalDateTime today = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0);
        String startName = unique("오늘 시작 ");
        String expiryName = unique("내일 마감 ");
        jdbc.update("""
                INSERT INTO store_events (mall_id, event_name, event_type, start_at, end_at)
                VALUES (?, ?, 'SALE', ?, ?)""", mallId, startName, Timestamp.valueOf(today.plusHours(1)),
                Timestamp.valueOf(today.plusDays(5)));
        jdbc.update("""
                INSERT INTO store_events (mall_id, event_name, event_type, discount_type, discount_value, start_at, end_at)
                VALUES (?, ?, 'COUPON', 'AMOUNT', 5, ?, ?)""", mallId, expiryName,
                Timestamp.valueOf(today.minusDays(3)), Timestamp.valueOf(today.plusDays(1).plusHours(23)));
        Long startId = jdbc.queryForObject("SELECT store_event_id FROM store_events WHERE event_name = ?", Long.class, startName);
        Long expiryId = jdbc.queryForObject("SELECT store_event_id FROM store_events WHERE event_name = ?", Long.class, expiryName);
        jdbc.update("INSERT INTO users (email, password_hash, nickname) VALUES (?, 'x', 'e')", unique("e") + "@e.com");
        Long userId = jdbc.queryForObject("SELECT MAX(user_id) FROM users", Long.class);
        jdbc.update("INSERT INTO price_alerts (user_id, store_event_id, alert_basis) VALUES (?, ?, 'EVENT_START')",
                userId, startId);
        jdbc.update("INSERT INTO price_alerts (user_id, store_event_id, alert_basis) VALUES (?, ?, 'COUPON_EXPIRY')",
                userId, expiryId);

        try {
            evaluator.evaluateEvents();
            evaluator.evaluateEvents();   // 하루 1회
            Integer notes = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM notifications n JOIN price_alerts a ON a.alert_id = n.alert_id WHERE a.user_id = ?""",
                    Integer.class, userId);
            assertThat(notes).isEqualTo(2);
        } finally {
            // GP_TEST_DB_URL 로 개발 DB 에서 돌려도 홈 · 상세 행사 목록에 테스트 행사가 남지 않게
            jdbc.update("UPDATE store_events SET is_active = FALSE WHERE store_event_id IN (?, ?)", startId, expiryId);
        }
    }
}
