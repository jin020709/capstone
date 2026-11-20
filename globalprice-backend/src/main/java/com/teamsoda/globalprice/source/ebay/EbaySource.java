package com.teamsoda.globalprice.source.ebay;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import com.teamsoda.globalprice.config.GlobalPriceProperties;
import com.teamsoda.globalprice.source.Json;
import com.teamsoda.globalprice.source.MallSource;
import com.teamsoda.globalprice.source.SourceException;
import com.teamsoda.globalprice.source.SourceHttp;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * eBay Browse API.
 * <ul>
 *   <li>검색: GET /buy/browse/v1/item_summary/search?q=&filter=deliveryCountry:KR,conditions:{NEW}&limit= (Best Match, 정렬은 우리 쪽)</li>
 *   <li>갱신: GET /buy/browse/v1/item/{itemId} (단건)</li>
 *   <li>인증: OAuth client credentials (scope https://api.ebay.com/oauth/api_scope). 토큰은 메모리에 두고 만료 1분 전 갱신</li>
 * </ul>
 * 검색 결과에는 옵션 구분이 없을 수 있어 옵션은 DEFAULT 1건으로 둔다.
 */
@Component
public class EbaySource implements MallSource {

    public static final String SOURCE_NAME = "EBAY_BROWSE";
    static final String SCOPE = "https://api.ebay.com/oauth/api_scope";
    private static final Logger log = LoggerFactory.getLogger(EbaySource.class);

    private final GlobalPriceProperties.Ebay props;
    private final RestClient rest;
    private final ObjectMapper mapper;
    private final Clock clock;

    private volatile String token;
    private volatile Instant tokenExpiresAt = Instant.EPOCH;

    public EbaySource(GlobalPriceProperties props, RestClient.Builder externalRestClientBuilder, ObjectMapper mapper,
                      Clock clock) {
        this.props = props.sources().ebay();
        this.rest = externalRestClientBuilder.build();
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override public String sourceName() { return SOURCE_NAME; }
    @Override public String storeKey() { return "ebay"; }
    @Override public int dailyBudget() { return props.dailyBudget(); }

    @Override
    public boolean enabled() {
        return props.enabled() && notBlank(props.clientId()) && notBlank(props.clientSecret());
    }

    @Override
    public List<MallOffer> search(String query, SearchOptions options) {
        String body = SourceHttp.call(SOURCE_NAME, () -> rest.get()
                // sort=price 는 쓰지 않는다: 실제 응답에서 최저가순 상위 20건이 전부 $0.99 케이스 · 필름이라
                // 걸러내기 후 0건이 됨 (2026-11-14 운영 호출 확인). Best Match 로 받아 우리 쪽에서 정렬한다
                .uri(props.resolvedBaseUrl() + "/buy/browse/v1/item_summary/search?q={q}&filter={f}&limit={l}",
                        query, "deliveryCountry:KR,conditions:{NEW}", options.limit())
                .headers(this::headers)
                .retrieve().body(String.class));
        JsonNode root = read(body);
        JsonNode items = root.path("itemSummaries");
        if (items.isMissingNode()) return List.of();   // 결과 0건이면 itemSummaries 가 없음
        if (!items.isArray()) throw new SourceException.BadResponse("eBay itemSummaries 형식 오류", null);
        List<MallOffer> out = new ArrayList<>();
        for (JsonNode n : items) {
            MallOffer o = map(n, false);
            if (o != null) out.add(o);
        }
        return out;
    }

    @Override
    public List<MallOffer> refresh(List<String> externalProductIds) {
        List<MallOffer> out = new ArrayList<>();
        for (String id : externalProductIds) {
            String body = SourceHttp.call(SOURCE_NAME, () -> rest.get()
                    .uri(props.resolvedBaseUrl() + "/buy/browse/v1/item/{id}", id)
                    .headers(this::headers)
                    .retrieve().body(String.class));
            MallOffer o = map(read(body), true);
            if (o != null) out.add(o);
        }
        return out;
    }

    /**
     * 공통 헤더. Accept-Language: en-US → 제목을 판매자 원문(영어)으로 받는다
     * (contextualLocation=KR 만 주면 제목이 한국어 기계번역으로 와서 걸러내기 키워드 · 상품명이 불안정)
     */
    private void headers(org.springframework.http.HttpHeaders h) {
        h.setBearerAuth(accessToken());
        h.set("X-EBAY-C-MARKETPLACE-ID", "EBAY_US");
        h.set("X-EBAY-C-ENDUSERCTX", "contextualLocation=country=KR");
        h.set("Accept-Language", "en-US");
    }

    /**
     * 금액 1건. contextualLocation=KR 이면 eBay 가 value 를 KRW 로 바꿔 주고 원래 값은 convertedFromValue ·
     * convertedFromCurrency 에 둔다 → 원래 통화 값을 써서 우리 환율(전신환매도율)로 계산한다.
     * @return [금액, 통화] 또는 null
     */
    static Object[] amount(JsonNode a) {
        BigDecimal from = Json.decimal(a.path("convertedFromValue"));
        String fromCur = Json.text(a, "convertedFromCurrency");
        if (from != null && fromCur != null) return new Object[] { from, fromCur };
        BigDecimal v = Json.decimal(a.path("value"));
        String c = Json.text(a, "currency");
        return v == null || c == null ? null : new Object[] { v, c };
    }

    /** itemSummary / item 1건 → MallOffer. 필수값(itemId · 가격)이 없으면 null (그 건만 건너뜀) */
    MallOffer map(JsonNode n, boolean detail) {
        String id = Json.text(n, "itemId");
        Object[] p = amount(n.path("price"));
        if (id == null || p == null) return null;
        BigDecimal price = (BigDecimal) p[0];
        String currency = (String) p[1];

        BigDecimal shipping = null;
        for (JsonNode s : n.path("shippingOptions")) {
            Object[] a = amount(s.path("shippingCost"));
            if (a == null || !currency.equals(a[1])) continue;
            BigDecimal v = (BigDecimal) a[0];
            if (shipping == null || v.compareTo(shipping) < 0) shipping = v;
        }
        // 1000 = New, 1500 = New other(미개봉 · 박스 없음 등). eBay 의 conditions:{NEW} 필터도 둘 다 포함한다
        String conditionId = Json.text(n, "conditionId");
        String condition = "1000".equals(conditionId) || "1500".equals(conditionId) ? "NEW"
                : Json.text(n, "condition") == null ? null : Json.text(n, "condition").toUpperCase(Locale.ROOT);

        StockStatus stock = StockStatus.IN_STOCK;
        if (detail) {
            stock = StockStatus.UNKNOWN;
            for (JsonNode a : n.path("estimatedAvailabilities")) {
                String st = Json.text(a, "estimatedAvailabilityStatus");
                if ("OUT_OF_STOCK".equals(st)) stock = StockStatus.OUT_OF_STOCK;
                else if (st != null) stock = StockStatus.IN_STOCK;
            }
        }
        String url = Json.text(n, "itemAffiliateWebUrl") != null ? Json.text(n, "itemAffiliateWebUrl")
                : Json.text(n, "itemWebUrl");
        return new MallOffer(id, "DEFAULT", Json.text(n, "title"), null, price, currency, shipping, condition,
                Json.text(n.path("itemLocation"), "country"), url, Json.text(n.path("image"), "imageUrl"), stock,
                null, Json.text(n, "gtin"));
    }

    /** 앱 토큰. 만료 60초 전이면 새로 받는다. 토큰 · 시크릿은 로그에 남기지 않는다 */
    synchronized String accessToken() {
        if (token != null && clock.instant().isBefore(tokenExpiresAt.minusSeconds(60))) return token;
        String basic = Base64.getEncoder().encodeToString(
                (props.clientId() + ":" + props.clientSecret()).getBytes(StandardCharsets.UTF_8));
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("scope", SCOPE);
        String body = SourceHttp.call(SOURCE_NAME + "-token", () -> rest.post()
                .uri(props.resolvedTokenUrl())
                .header("Authorization", "Basic " + basic)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve().body(String.class));
        JsonNode n = read(body);
        String t = Json.text(n, "access_token");
        Integer expires = Json.integer(n.path("expires_in"));
        if (t == null) throw new SourceException.AuthFailed("eBay 토큰 응답에 access_token 없음", null);
        token = t;
        tokenExpiresAt = clock.instant().plusSeconds(expires == null ? 3600 : expires);
        log.info("eBay 앱 토큰 갱신 (유효 {}초)", expires);
        return token;
    }

    private JsonNode read(String body) {
        try {
            return mapper.readTree(body == null ? "" : body);
        } catch (RuntimeException e) {
            throw new SourceException.BadResponse("eBay 응답 JSON 파싱 실패", null);
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
