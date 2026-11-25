package com.teamsoda.globalprice.source;

import static com.teamsoda.globalprice.source.EbaySourceTest.sample;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.teamsoda.globalprice.config.GlobalPriceProperties;
import com.teamsoda.globalprice.rate.KoreaEximClient;
import com.teamsoda.globalprice.source.MallSource.MallOffer;
import com.teamsoda.globalprice.source.MallSource.SearchOptions;
import com.teamsoda.globalprice.source.MallSource.StockStatus;
import com.teamsoda.globalprice.source.aliexpress.AliExpressSource;
import com.teamsoda.globalprice.source.etsy.EtsySource;
import com.teamsoda.globalprice.source.shopify.ShopifySource;
import com.teamsoda.globalprice.support.MockHttp;
import com.teamsoda.globalprice.support.MockHttp.Response;
import com.teamsoda.globalprice.support.TestProps;

/** Shopify (JSON · SSE · 최소 통화 단위) · AliExpress (서명) · Etsy · 수출입은행 환율 */
class OtherSourcesTest {

    MockHttp http;
    GlobalPriceProperties props;

    @BeforeEach
    void setUp() {
        http = new MockHttp();
        props = TestProps.props(http.baseUrl(), Duration.ofMillis(800));
    }

    @AfterEach
    void tearDown() {
        http.close();
    }

    // ---------------- Shopify ----------------

    /** 실제 응답(2026-11-14 캡처, 설명만 줄임): 3199 USD 최소 단위 → 31.99 */
    @Test
    void shopify_json_realSample() {
        http.on("/api/ucp/mcp", Response.json(sample("shopify-search.json")));
        ShopifySource s = new ShopifySource(props, TestProps.rest(props), TestProps.MAPPER);
        List<MallOffer> offers = s.search("wireless headphones", new SearchOptions(20));
        assertThat(offers).isNotEmpty();
        MallOffer o = offers.getFirst();
        assertThat(o.externalProductId()).startsWith("gid://shopify/ProductVariant/");
        assertThat(o.price()).isEqualByComparingTo("31.99");
        assertThat(o.currencyCode()).isEqualTo("USD");
        assertThat(o.stock()).isEqualTo(StockStatus.IN_STOCK);
        assertThat(o.imageUrl()).startsWith("https://cdn.shopify.com/");
        assertThat(o.productUrl()).contains("utm_source=shopify");   // attribution 파라미터 유지

        String body = http.requests().getFirst().body();
        assertThat(body).contains("\"name\":\"search_catalog\"").contains("\"ucp-agent\"")
                .contains("\"ships_to\":{\"country\":\"KR\"}").contains("\"currency\":\"USD\"");
    }

    @Test
    void shopify_sse_sameResultAsJson() {
        http.on("/api/ucp/mcp", new Response(200, sample("shopify-search.sse"), "text/event-stream", 0));
        ShopifySource s = new ShopifySource(props, TestProps.rest(props), TestProps.MAPPER);
        List<MallOffer> sse = s.search("wireless headphones", new SearchOptions(20));
        http.on("/api/ucp/mcp", Response.json(sample("shopify-search.json")));
        List<MallOffer> json = s.search("wireless headphones", new SearchOptions(20));
        assertThat(sse).isEqualTo(json);
    }

    @Test
    void shopify_minorUnits_byCurrencyDigits() {
        assertThat(ShopifySource.minorToMajor(new BigDecimal("12345"), "USD")).isEqualByComparingTo("123.45");
        assertThat(ShopifySource.minorToMajor(new BigDecimal("12345"), "JPY")).isEqualByComparingTo("12345");
        assertThat(ShopifySource.minorToMajor(new BigDecimal("12345"), "KWD")).isEqualByComparingTo("12.345");
    }

    @Test
    void shopify_rpcError_isBadResponse() {
        http.on("/api/ucp/mcp", Response.json("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32602,\"message\":\"bad profile\"}}"));
        ShopifySource s = new ShopifySource(props, TestProps.rest(props), TestProps.MAPPER);
        assertThatThrownBy(() -> s.search("x", new SearchOptions(5))).isInstanceOf(SourceException.BadResponse.class)
                .hasMessageContaining("bad profile");
    }

    @Test
    void shopify_refresh_matchesRequestedVariant() {
        http.on("/api/ucp/mcp", Response.json(sample("shopify-search.json")));
        ShopifySource s = new ShopifySource(props, TestProps.rest(props), TestProps.MAPPER);
        String id = s.search("x", new SearchOptions(20)).getFirst().externalProductId();
        List<MallOffer> refreshed = s.refresh(List.of(id));
        assertThat(refreshed).extracting(MallOffer::externalProductId).containsExactly(id);
        assertThat(http.requests().getLast().body()).contains("\"name\":\"lookup_catalog\"").contains(id);
    }

    // ---------------- AliExpress ----------------

    /**
     * 서명: key 오름차순 key+value 연결 → HMAC-SHA256(secret) 대문자 hex. sign 자신은 제외.
     * 기대값은 python 으로 따로 계산:
     * hmac.new(b"secret", b"app_keykeyformatjsonmethodtestv2.0", hashlib.sha256).hexdigest().upper()
     */
    @Test
    void aliexpress_signature() {
        Map<String, String> p = new TreeMap<>(Map.of("v", "2.0", "method", "test", "format", "json", "app_key", "key"));
        p.put("sign", "IGNORED");
        assertThat(AliExpressSource.sign(p, "secret"))
                .isEqualTo("82D346EC49E2DEDA44490BF178E2E059795D524F51FBAFC2BED41B2F0ACB78A9");
    }

    @Test
    void aliexpress_parsesProducts_andSendsSignedRequest() {
        http.on("/sync", Response.json(sample("aliexpress-query.json")));
        Clock clock = Clock.fixed(Instant.parse("2026-11-14T00:00:00Z"), ZoneOffset.UTC);
        AliExpressSource s = new AliExpressSource(props, TestProps.rest(props), TestProps.MAPPER, clock);
        List<MallOffer> offers = s.search("wireless headphones", new SearchOptions(20));
        assertThat(offers).singleElement().satisfies(o -> {
            assertThat(o.externalProductId()).isEqualTo("1005006000000001");
            assertThat(o.price()).isEqualByComparingTo("18.99");
            assertThat(o.productUrl()).isEqualTo("https://s.click.aliexpress.com/e/_TEST1");   // 제휴 링크
            assertThat(o.shipDays()).isEqualTo(12);
            assertThat(o.shippingFee()).isNull();
        });
        String body = http.requests().getFirst().body();
        assertThat(body).contains("method=aliexpress.affiliate.product.query").contains("ship_to_country=KR")
                .contains("sign_method=sha256").contains("sign=").contains("timestamp=1794614400000");
    }

    /**
     * 실제 응답 (2026-11-17, 제휴 앱 키): products.product[] · 문자열 가격 · USD.
     * tracking_id 가 없으면 tracking_id · sort 를 보내지 않고, 구매 링크는 product_detail_url.
     */
    @Test
    void aliexpress_realSample_withoutTrackingId() {
        http.on("/sync", Response.json(sample("aliexpress-query-real.json")));
        GlobalPriceProperties noTracking = new GlobalPriceProperties(props.zone(), props.search(), props.pricing(),
                props.home(), props.filter(), new GlobalPriceProperties.Sources(props.sources().ebay(),
                        props.sources().shopify(), new GlobalPriceProperties.AliExpress(true, "key", "secret", "", 3000,
                                http.baseUrl() + "/sync"), props.sources().etsy(), props.sources().koreaexim()),
                props.outliers(), props.display());
        AliExpressSource s = new AliExpressSource(noTracking, TestProps.rest(noTracking), TestProps.MAPPER,
                Clock.systemUTC());
        assertThat(s.enabled()).isTrue();   // tracking_id 없이도 동작
        List<MallOffer> offers = s.search("wireless headphones", new SearchOptions(20));
        assertThat(offers).hasSize(5);
        MallOffer jbl = offers.stream().filter(o -> o.title().startsWith("JBL")).findFirst().orElseThrow();
        assertThat(jbl.price()).isEqualByComparingTo("28.06");
        assertThat(jbl.currencyCode()).isEqualTo("USD");
        assertThat(offers).allMatch(o -> "CN".equals(o.originCountry()));   // 응답에 발송지가 없어 중국 고정
        assertThat(jbl.productUrl()).startsWith("https://www.aliexpress.com/item/");   // 제휴 링크 아님
        assertThat(jbl.imageUrl()).isNotNull();
        String body = http.requests().getFirst().body();
        assertThat(body).doesNotContain("tracking_id").doesNotContain("sort=");
    }

    /** tracking_id 가 있으면 promotion_link 를 구매 링크로 */
    @Test
    void aliexpress_withTrackingId_usesPromotionLink() {
        http.on("/sync", Response.json(sample("aliexpress-query-real.json")));
        AliExpressSource s = new AliExpressSource(props, TestProps.rest(props), TestProps.MAPPER, Clock.systemUTC());
        List<MallOffer> offers = s.search("wireless headphones", new SearchOptions(20));
        assertThat(offers.getFirst().productUrl()).startsWith("https://s.click.aliexpress.com/");
        assertThat(http.requests().getFirst().body()).contains("tracking_id=track");
    }

    /** 실제 응답 (2026-11-14): 제휴 API 권한이 없는 앱 키 → AuthFailed (api_sync_logs · 로그에 인증 실패로 남음) */
    @Test
    void aliexpress_realInsufficientPermission_isAuthFailed() {
        http.on("/sync", Response.json("{\"error_response\":{\"type\":\"ISV\",\"code\":\"InsufficientPermission\","
                + "\"msg\":\"App does not have permission to access this api\",\"request_id\":\"212a640e17945839035856870\"}}"));
        AliExpressSource s = new AliExpressSource(props, TestProps.rest(props), TestProps.MAPPER, Clock.systemUTC());
        assertThatThrownBy(() -> s.search("x", new SearchOptions(5))).isInstanceOf(SourceException.AuthFailed.class)
                .hasMessageContaining("InsufficientPermission");
    }

    /** 실제 응답 (2026-11-14): 서명이 틀리면 IncompleteSignature */
    @Test
    void aliexpress_errorResponse_signIsAuthFailed() {
        http.on("/sync", Response.json("{\"error_response\":{\"code\":\"IncompleteSignature\",\"msg\":\"x\"}}"));
        AliExpressSource s = new AliExpressSource(props, TestProps.rest(props), TestProps.MAPPER, Clock.systemUTC());
        assertThatThrownBy(() -> s.search("x", new SearchOptions(5))).isInstanceOf(SourceException.AuthFailed.class);
    }

    // ---------------- Etsy ----------------

    @Test
    void etsy_priceAmountDivisor_andApiKeyHeader() {
        http.on("/v3/application/listings/active", Response.json(sample("etsy-active.json")));
        EtsySource s = new EtsySource(props, TestProps.rest(props), TestProps.MAPPER);
        List<MallOffer> offers = s.search("headphone stand", new SearchOptions(20));
        assertThat(offers).hasSize(2);
        assertThat(offers.get(0).price()).isEqualByComparingTo("45.50");
        assertThat(offers.get(0).stock()).isEqualTo(StockStatus.IN_STOCK);
        assertThat(offers.get(1).currencyCode()).isEqualTo("EUR");
        assertThat(offers.get(1).stock()).isEqualTo(StockStatus.OUT_OF_STOCK);
        assertThat(http.requests().getFirst().header("x-api-key")).isEqualTo("keystring:secret");
        assertThat(http.requests().getFirst().query()).contains("sort_on=score").doesNotContain("sort_on=price");
    }

    /**
     * 실제 응답 (2026-11-14): 새 상품 · 1990s 빈티지 · 공예 재료 · SVG 다운로드 → condition 으로 구분,
     * 검색에 이미지가 없어 batch(includes=Images) 로 붙임
     */
    @Test
    void etsy_realSample_conditionsAndImages() {
        http.on("/v3/application/listings/active", Response.json(sample("etsy-active-real.json")));
        http.on("/v3/application/listings/batch", Response.json(sample("etsy-batch-real.json")));
        EtsySource s = new EtsySource(props, TestProps.rest(props), TestProps.MAPPER);
        List<MallOffer> offers = s.search("wireless headphones", new SearchOptions(20));
        assertThat(offers).extracting(MallOffer::condition).containsExactly("NEW", "VINTAGE", "SUPPLY", "DIGITAL");
        MallOffer first = offers.getFirst();
        assertThat(first.price()).isEqualByComparingTo("27.00");
        assertThat(first.currencyCode()).isEqualTo("GBP");
        assertThat(first.imageUrl()).startsWith("https://i.etsystatic.com/").contains("il_570xN");
        assertThat(offers.get(1).imageUrl()).isNull();   // batch 응답에 없던 상품
        assertThat(http.requests().getLast().query()).contains("includes=Images");
    }

    @Test
    void etsy_imageBatchFailure_keepsResults() {
        http.on("/v3/application/listings/active", Response.json(sample("etsy-active-real.json")));
        http.on("/v3/application/listings/batch", Response.status(500, "{}"));
        EtsySource s = new EtsySource(props, TestProps.rest(props), TestProps.MAPPER);
        assertThat(s.search("x", new SearchOptions(20))).hasSize(4);
    }

    @Test
    void etsy_429() {
        http.on("/v3/application/listings/active", Response.status(429, "{}"));
        EtsySource s = new EtsySource(props, TestProps.rest(props), TestProps.MAPPER);
        assertThatThrownBy(() -> s.search("x", new SearchOptions(5))).isInstanceOf(SourceException.RateLimited.class);
    }

    // ---------------- 수출입은행 ----------------

    /** 손으로 만든 샘플: 쉼표 숫자 · JPY(100) · IDR(100) · KRW 제외 */
    @Test
    void koreaexim_parse() {
        http.on("/exchangeJSON", Response.json(sample("koreaexim.json")));
        KoreaEximClient c = new KoreaEximClient(TestProps.rest(props), props, TestProps.MAPPER);
        List<KoreaEximClient.RawRate> rates = c.fetch(java.time.LocalDate.of(2026, 11, 13));
        assertThat(rates).extracting(KoreaEximClient.RawRate::currencyCode).containsExactly("AED", "JPY", "USD", "IDR");
        var usd = rates.get(2);
        assertThat(usd.rateToKrw()).isEqualByComparingTo("1380.5");
        assertThat(usd.ttsRate()).isEqualByComparingTo("1394.34");
        assertThat(rates.get(1).rateToKrw()).isEqualByComparingTo("9.408800");
        assertThat(rates.get(3).rateToKrw()).isEqualByComparingTo("0.084500");
        assertThat(http.requests().getFirst().query()).contains("searchdate=20261113").contains("data=AP01");
    }

    @Test
    void koreaexim_emptyArray_isEmpty() {
        http.on("/exchangeJSON", Response.json("[]"));
        KoreaEximClient c = new KoreaEximClient(TestProps.rest(props), props, TestProps.MAPPER);
        assertThat(c.fetch(java.time.LocalDate.of(2026, 11, 14))).isEmpty();
    }

    @Test
    void koreaexim_authError() {
        http.on("/exchangeJSON", Response.json("[{\"result\":3}]"));
        KoreaEximClient c = new KoreaEximClient(TestProps.rest(props), props, TestProps.MAPPER);
        assertThatThrownBy(() -> c.fetch(java.time.LocalDate.of(2026, 11, 13)))
                .isInstanceOf(KoreaEximClient.EximException.class).hasMessageContaining("인증키");
    }
}
