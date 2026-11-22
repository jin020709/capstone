package com.teamsoda.globalprice.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 출발국: API 값 → 쇼핑몰 기본 통화면 쇼핑몰 나라 → 아니면 통화의 나라 */
class OriginTest {

    @Test
    void apiValueWins() {
        assertThat(Origin.of("cn", "USD", "US")).isEqualTo("CN");
    }

    @Test
    void sameCurrencyAsMall_isMallCountry() {
        assertThat(Origin.of(null, "USD", "US")).isEqualTo("US");
        assertThat(Origin.of(null, "KRW", "KR")).isEqualTo("KR");
    }

    @Test
    void otherCurrency_isCurrencyCountry() {   // eBay US 의 캐나다 판매자, Shopify 의 두바이 상점
        assertThat(Origin.of(null, "CAD", "US")).isEqualTo("CA");
        assertThat(Origin.of(null, "AED", "US")).isEqualTo("AE");
        assertThat(Origin.of(null, "KWD", "US")).isEqualTo("KW");   // 쿠웨이트 상점 (tammstore.com)
        assertThat(Origin.of(null, "JPY", "US")).isEqualTo("JP");
        assertThat(Origin.of(null, "EUR", "US")).isNull();          // 유로권은 모름
        assertThat(Origin.of(null, "KRW", "US")).isEqualTo("US");   // 원화 현지가는 쇼핑몰 나라
    }

    @Test
    void aliExpress_isAlwaysChina() {   // target_currency=USD 로 받으므로 USD 여도 미국이 아님 (DB 에서 다시 읽은 리스팅 포함)
        assertThat(Origin.of(null, "USD", "CN")).isEqualTo("CN");
        assertThat(Origin.of(null, "EUR", "CN")).isEqualTo("CN");
        assertThat(Origin.of("CN", "USD", "CN")).isEqualTo("CN");
    }
}
