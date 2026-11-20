package com.teamsoda.globalprice.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import com.teamsoda.globalprice.config.GlobalPriceProperties;
import com.teamsoda.globalprice.source.MallSource.MallOffer;
import com.teamsoda.globalprice.source.MallSource.SearchOptions;
import com.teamsoda.globalprice.source.MallSource.StockStatus;
import com.teamsoda.globalprice.source.ebay.EbaySource;
import com.teamsoda.globalprice.support.MockHttp;
import com.teamsoda.globalprice.support.MockHttp.Response;
import com.teamsoda.globalprice.support.TestProps;

class EbaySourceTest {

    static final String SEARCH = "/buy/browse/v1/item_summary/search";
    static final String TOKEN = "/identity/v1/oauth2/token";

    MockHttp http;
    EbaySource source;

    @BeforeEach
    void setUp() {
        http = new MockHttp();
        http.on(TOKEN, Response.json("{\"access_token\":\"tok-123\",\"expires_in\":7200,\"token_type\":\"Application Access Token\"}"));
        GlobalPriceProperties props = TestProps.props(http.baseUrl(), Duration.ofMillis(500));
        source = new EbaySource(props, TestProps.rest(props), TestProps.MAPPER, Clock.systemUTC());
    }

    @AfterEach
    void tearDown() {
        http.close();
    }

    @Test
    void search_mapsFieldsAndSkipsMalformed() {
        http.on(SEARCH, Response.json(sample("ebay-search.json")));
        List<MallOffer> offers = source.search("iphone 17", new SearchOptions(20));

        assertThat(offers).hasSize(2);   // 가격 없는 1건은 건너뜀
        MallOffer o = offers.getFirst();
        assertThat(o.externalProductId()).isEqualTo("v1|110551234567|0");
        assertThat(o.externalVariantId()).isEqualTo("DEFAULT");
        assertThat(o.price()).isEqualByComparingTo("799.00");
        assertThat(o.currencyCode()).isEqualTo("USD");
        assertThat(o.shippingFee()).isEqualByComparingTo("25.00");   // 가장 싼 배송 옵션
        assertThat(o.condition()).isEqualTo("NEW");
        assertThat(o.originCountry()).isEqualTo("US");
        assertThat(o.productUrl()).contains("campid=TEST");          // 제휴 링크 우선
        assertThat(o.imageUrl()).endsWith("s-l225.jpg");
        assertThat(offers.get(1).condition()).isEqualTo("SELLER REFURBISHED");   // 걸러내기는 ResultFilter 몫

        MockHttp.Request req = http.requests().stream().filter(r -> r.path().equals(SEARCH)).findFirst().orElseThrow();
        assertThat(req.header("Authorization")).isEqualTo("Bearer tok-123");
        assertThat(req.header("X-EBAY-C-MARKETPLACE-ID")).isEqualTo("EBAY_US");
        assertThat(req.header("X-EBAY-C-ENDUSERCTX")).isEqualTo("contextualLocation=country=KR");
        String query = java.net.URLDecoder.decode(req.query(), StandardCharsets.UTF_8);
        assertThat(query).contains("q=iphone 17").contains("filter=deliveryCountry:KR,conditions:{NEW}")
                .contains("limit=20").doesNotContain("sort=");
        assertThat(req.header("Accept-Language")).isEqualTo("en-US");
    }

    /**
     * 운영 API 실제 응답: value 는 KRW 로 바뀌어 오고 원래 값은 convertedFromValue · convertedFromCurrency.
     * 원래 통화(USD · EUR · CAD · GBP)로 저장해야 우리 환율로 계산된다. conditionId 1500(New other)도 새 상품.
     */
    @Test
    void realSample_usesOriginalCurrency() {
        http.on(SEARCH, Response.json(sample("ebay-search-real.json")));
        List<MallOffer> offers = source.search("iphone 17", new SearchOptions(20));
        assertThat(offers).hasSize(6);
        MallOffer us = offers.getFirst();
        assertThat(us.price()).isEqualByComparingTo("550.00");
        assertThat(us.currencyCode()).isEqualTo("USD");
        assertThat(us.shippingFee()).isEqualByComparingTo("29.90");
        assertThat(offers).extracting(MallOffer::currencyCode).containsExactly("USD", "EUR", "EUR", "CAD", "USD", "GBP");
        MallOffer de = offers.get(2);
        assertThat(de.condition()).isEqualTo("NEW");   // 1500
        assertThat(de.originCountry()).isEqualTo("DE");
        assertThat(de.price()).isEqualByComparingTo("859.00");
    }

    @Test
    void token_isCachedAcrossCalls() {
        http.on(SEARCH, Response.json("{\"total\":0}"));
        source.search("a", new SearchOptions(5));
        source.search("b", new SearchOptions(5));
        assertThat(http.count(TOKEN)).isEqualTo(1);
        MockHttp.Request tokenReq = http.requests().getFirst();
        assertThat(tokenReq.header("Authorization")).startsWith("Basic ");
        assertThat(tokenReq.body()).contains("grant_type=client_credentials").contains("api_scope");
    }

    @Test
    void emptyResult_returnsEmptyList() {
        http.on(SEARCH, Response.json("{\"total\":0,\"limit\":20}"));
        assertThat(source.search("zzz", new SearchOptions(20))).isEmpty();
    }

    @Test
    void refresh_usesGetItem_andReadsAvailability() {
        http.on("/buy/browse/v1/item/*", Response.json(sample("ebay-item.json")));
        List<MallOffer> offers = source.refresh(List.of("v1|110551234567|0"));
        assertThat(offers).singleElement().satisfies(o -> {
            assertThat(o.price()).isEqualByComparingTo("779.00");
            assertThat(o.stock()).isEqualTo(StockStatus.OUT_OF_STOCK);
            assertThat(o.gtin()).isEqualTo("0195949000000");
        });
    }

    @Test
    void status429_isRateLimited() {
        http.on(SEARCH, Response.status(429, "{}"));
        assertThatThrownBy(() -> source.search("x", new SearchOptions(5))).isInstanceOf(SourceException.RateLimited.class);
    }

    @Test
    void status503_isRetryableBadResponse() {
        http.on(SEARCH, Response.status(503, "{}"));
        assertThatThrownBy(() -> source.search("x", new SearchOptions(5)))
                .isInstanceOfSatisfying(SourceException.BadResponse.class, e -> {
                    assertThat(e.httpStatus()).isEqualTo(503);
                    assertThat(e.retryable()).isTrue();
                });
    }

    @Test
    void status401_isAuthFailed_withoutLeakingSecrets() {
        http.on(SEARCH, Response.status(401, "{\"errors\":[{\"message\":\"Invalid access token\"}]}"));
        assertThatThrownBy(() -> source.search("x", new SearchOptions(5)))
                .isInstanceOf(SourceException.AuthFailed.class)
                .hasMessageNotContaining("tok-123").hasMessageNotContaining("secret");
    }

    @Test
    void slowResponse_isTimeout() {
        http.on(SEARCH, Response.json("{}").delayed(2_000));   // 읽기 제한 500ms
        assertThatThrownBy(() -> source.search("x", new SearchOptions(5))).isInstanceOf(SourceException.Timeout.class);
    }

    @Test
    void brokenJson_isBadResponse() {
        http.on(SEARCH, Response.json("{not json"));
        assertThatThrownBy(() -> source.search("x", new SearchOptions(5)))
                .isInstanceOfSatisfying(SourceException.BadResponse.class, e -> assertThat(e.httpStatus()).isNull());
    }

    @Test
    void disabledWithoutKeys() {
        GlobalPriceProperties base = TestProps.props(http.baseUrl(), Duration.ofSeconds(1));
        var noKey = new GlobalPriceProperties(base.zone(), base.search(), base.pricing(), base.home(), base.filter(),
                new GlobalPriceProperties.Sources(new GlobalPriceProperties.Ebay(true, "", "", true, 10, null, null),
                        base.sources().shopify(), base.sources().aliexpress(), base.sources().etsy(),
                        base.sources().koreaexim()));
        assertThat(new EbaySource(noKey, TestProps.rest(noKey), TestProps.MAPPER, Clock.systemUTC()).enabled()).isFalse();
    }

    public static String sample(String name) {
        try {
            return new ClassPathResource("samples/" + name).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
