package com.teamsoda.globalprice;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import com.teamsoda.globalprice.support.IntegrationTestBase;

/**
 * API 통합 테스트: 서버가 실제 MySQL 스키마로 켜지고, 시드 데이터로 계산 · 회원 · 찜 · 알림이 동작하는지.
 * 시드 기준 금액은 03_seed_data.sql (iPhone 17 · Amazon US 1,187,256원 / Rakuten 1,254,855원).
 */
@org.junit.jupiter.api.condition.EnabledIf("com.teamsoda.globalprice.support.IntegrationTestBase#databaseAvailable")
class GlobalPriceApplicationTests extends IntegrationTestBase {

    @Test
    void exchangeRates_fromSeed() throws Exception {
        mvc.perform(get("/api/exchange-rates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.base").value("KRW"))
                .andExpect(jsonPath("$.rates.USD").exists())
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void productDetail_seedTotals() throws Exception {
        mvc.perform(get("/api/products/1").param("coupon", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("iPhone 17"))
                .andExpect(jsonPath("$.offers[?(@.storeName=='Amazon US')].total").value(1187256))
                .andExpect(jsonPath("$.offers[?(@.storeName=='Amazon US')].totalNormal").value(1264956))
                .andExpect(jsonPath("$.offers[?(@.storeName=='Rakuten Japan')].total").value(1254855))
                .andExpect(jsonPath("$.bestTotal").value(1187256))
                .andExpect(jsonPath("$.colors").isArray());   // 색상별 최저가 (색상이 2개 미만이면 빈 배열)
        mvc.perform(get("/api/products/999999")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
        mvc.perform(get("/api/products/1/price-history").param("period", "1y")).andExpect(status().isOk())
                .andExpect(jsonPath("$.points").isArray());
        mvc.perform(get("/api/products/1/price-history").param("period", "5d")).andExpect(status().isBadRequest());
    }

    @org.springframework.beans.factory.annotation.Autowired
    com.teamsoda.globalprice.search.QueryNormalizer normalizer;

    /** 검색어 별칭은 DB search_aliases 에서 읽는다 (05 · 08 마이그레이션). 모델번호 행 · 띄어쓰기 별칭은 단어 변환에 안 씀 */
    @Test
    void searchAliases_fromDatabase() {
        org.assertj.core.api.Assertions.assertThat(normalizer.normalize("아이폰 17 케이스").sourceQuery())
                .isEqualTo("iphone 17 case");
        org.assertj.core.api.Assertions.assertThat(normalizer.normalize("핸드폰 필름").sourceQuery())
                .isEqualTo("phone screen protector");
        Integer modelRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM search_aliases WHERE model_number IS NOT NULL", Integer.class);
        org.assertj.core.api.Assertions.assertThat(modelRows).isPositive();   // 03 시드 상품의 모델번호 별칭이 있어도 영향 없음
    }

    /** 갤럭시 모델번호는 DB model_codes 에서 읽는다 (09 마이그레이션). 내장 목록에 없는 Fold5 도 묶인다 */
    @Test
    void galaxyModelCodes_fromDatabase(@org.springframework.beans.factory.annotation.Autowired com.teamsoda.globalprice.catalog.ModelCodes modelCodes) {
        modelCodes.reload();
        try {
            org.assertj.core.api.Assertions.assertThat(com.teamsoda.globalprice.catalog.ProductMatcher
                    .match("Samsung SM-F946B/DS 512GB").key()).isEqualTo("galaxy|z fold|5||512");
        } finally {
            com.teamsoda.globalprice.catalog.ProductMatcher.useGalaxyCodes(null);
        }
    }

    @Test
    void home_listsEvents() throws Exception {
        mvc.perform(get("/api/home")).andExpect(status().isOk())
                .andExpect(jsonPath("$.events").isArray())
                .andExpect(jsonPath("$.popular").isEmpty());
    }

    @Test
    void signupLoginWishlistAlerts() throws Exception {
        String email = unique("it") + "@example.com";
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"nickname\":\"it\",\"password\":\"password123\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.password").doesNotExist());
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"nickname\":\"it\",\"password\":\"password123\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_EMAIL"));

        mvc.perform(get("/api/wishlist")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"wrong-pass\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("LOGIN_FAILED"));

        MvcResult login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);

        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));

        // 찜: 201 → 같은 상품 다시 200 (멱등) → 목록 1건 → 삭제 204
        mvc.perform(post("/api/wishlist").session(session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":1}")).andExpect(status().isCreated());
        mvc.perform(post("/api/wishlist").session(session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":1}")).andExpect(status().isOk());
        mvc.perform(get("/api/wishlist").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].bestTotal").isNumber());
        mvc.perform(get("/api/products/1").session(session)).andExpect(jsonPath("$.wished").value(true));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/wishlist/1").session(session)).andExpect(status().isNoContent());

        // 알림: 201 → 같은 조건 200(UPDATE) → 조합 오류 400 → 남의 알림 삭제 404
        mvc.perform(post("/api/alerts").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alertBasis\":\"TARGET_PRICE\",\"productId\":1,\"targetPriceKrw\":1000000}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/alerts").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alertBasis\":\"TARGET_PRICE\",\"productId\":1,\"targetPriceKrw\":900000}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.targetPriceKrw").value(900000));
        mvc.perform(post("/api/alerts").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alertBasis\":\"EVENT_START\",\"productId\":1,\"storeEventId\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/alerts").session(session)).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/alerts/1").session(session)).andExpect(status().isNotFound());   // 시드 회원 1의 알림
        mvc.perform(get("/api/notifications").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(0));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .patch("/api/notifications/1/read").session(session)).andExpect(status().isNotFound());

        mvc.perform(post("/api/auth/logout").session(session)).andExpect(status().isNoContent());
    }

    @Test
    void loginRenewsSessionId() throws Exception {
        String email = unique("sid") + "@example.com";
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"nickname\":\"s\",\"password\":\"password123\"}"));
        MockHttpSession before = new MockHttpSession();
        String oldId = before.getId();
        MvcResult r = mvc.perform(post("/api/auth/login").session(before).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk()).andExpect(cookie().doesNotExist("password")).andReturn();
        org.assertj.core.api.Assertions.assertThat(r.getRequest().getSession(false).getId()).isNotEqualTo(oldId);
    }
}
