package com.teamsoda.globalprice.source.aliexpress;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

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
 * AliExpress Affiliate API (https://api-sg.aliexpress.com/sync + method 파라미터).
 * <ul>
 *   <li>검색: aliexpress.affiliate.product.query (keywords, page_no, page_size, [tracking_id], target_currency,
 *       target_language, ship_to_country=KR). sort 는 주지 않는다 (관련도순): SALE_PRICE_ASC 는 상위가 전부
 *       $0.03~0.14 이어폰 줄 · 캡 · 어댑터라 걸러내면 남는 게 없음 (2026-11-17 실호출)</li>
 *   <li>갱신: aliexpress.affiliate.productdetail.get (product_ids 콤마 구분)</li>
 *   <li>서명: 파라미터를 key 오름차순으로 key+value 이어 붙여 HMAC-SHA256(app_secret) → 대문자 16진수</li>
 * </ul>
 * 배송비 정보가 없으므로 shipping_rules 를 쓴다. 옵션 없음 → DEFAULT.
 * [확인 2026-11-17] 제휴 앱 키로 실호출: 응답 경로 resp_result.result.products.product[], target_sale_price(문자열)
 *   · target_sale_price_currency, product_detail_url, promotion_link. ship_to_days 는 오지 않음.
 * tracking_id 는 선택: 없어도 검색 · 상세가 되지만 promotion_link 가 상품과 무관한 같은 링크로 와서,
 *   tracking_id 가 없으면 구매 링크로 product_detail_url 을 쓴다.
 */
@Component
public class AliExpressSource implements MallSource {

    public static final String SOURCE_NAME = "ALIEXPRESS_AFFILIATE";
    static final String METHOD_QUERY = "aliexpress.affiliate.product.query";
    static final String METHOD_DETAIL = "aliexpress.affiliate.productdetail.get";
    private static final Pattern DAYS = Pattern.compile("(\\d+)");
    /** AliExpress 출발국 (응답에 발송지가 없어 고정) */
    static final String ORIGIN = "CN";

    private final GlobalPriceProperties.AliExpress props;
    private final RestClient rest;
    private final ObjectMapper mapper;
    private final Clock clock;

    public AliExpressSource(GlobalPriceProperties props, RestClient.Builder externalRestClientBuilder,
                            ObjectMapper mapper, Clock clock) {
        this.props = props.sources().aliexpress();
        this.rest = externalRestClientBuilder.build();
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override public String sourceName() { return SOURCE_NAME; }
    @Override public String storeKey() { return "aliexpress"; }
    @Override public int dailyBudget() { return props.dailyBudget(); }
    @Override public int refreshBatchSize() { return 20; }

    @Override
    public boolean enabled() {
        return props.enabled() && notBlank(props.appKey()) && notBlank(props.appSecret());
    }

    @Override
    public List<MallOffer> search(String query, SearchOptions options) {
        Map<String, String> p = new TreeMap<>();
        p.put("keywords", query);
        p.put("page_no", "1");
        p.put("page_size", String.valueOf(Math.min(options.limit(), 50)));
        if (hasTrackingId()) p.put("tracking_id", props.trackingId());
        p.put("target_currency", "USD");
        p.put("target_language", "EN");
        p.put("ship_to_country", "KR");
        JsonNode resp = call(METHOD_QUERY, p);
        return map(products(resp, "aliexpress_affiliate_product_query_response"));
    }

    @Override
    public List<MallOffer> refresh(List<String> externalProductIds) {
        if (externalProductIds.isEmpty()) return List.of();
        Map<String, String> p = new TreeMap<>();
        p.put("product_ids", String.join(",", externalProductIds));
        if (hasTrackingId()) p.put("tracking_id", props.trackingId());
        p.put("target_currency", "USD");
        p.put("target_language", "EN");
        p.put("country", "KR");
        JsonNode resp = call(METHOD_DETAIL, p);
        return map(products(resp, "aliexpress_affiliate_productdetail_get_response"));
    }

    JsonNode call(String method, Map<String, String> business) {
        Map<String, String> all = new TreeMap<>(business);
        all.put("app_key", props.appKey());
        all.put("method", method);
        all.put("timestamp", String.valueOf(clock.millis()));
        all.put("sign_method", "sha256");
        all.put("format", "json");
        all.put("v", "2.0");
        all.put("sign", sign(all, props.appSecret()));
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        all.forEach(form::add);
        String body = SourceHttp.call(SOURCE_NAME, () -> rest.post()
                .uri(props.endpoint())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve().body(String.class));
        try {
            return mapper.readTree(body == null ? "" : body);
        } catch (RuntimeException e) {
            throw new SourceException.BadResponse("AliExpress 응답 JSON 파싱 실패", null);
        }
    }

    /** key 오름차순으로 key+value 를 이어 붙여 HMAC-SHA256(secret), 대문자 16진수. sign 자신은 제외 */
    public static String sign(Map<String, String> params, String secret) {
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(params).forEach((k, v) -> {
            if (!"sign".equals(k) && v != null && !v.isEmpty()) sb.append(k).append(v);
        });
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().withUpperCase().formatHex(mac.doFinal(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("서명 계산 실패", e);
        }
    }

    JsonNode products(JsonNode root, String responseKey) {
        JsonNode err = root.path("error_response");
        if (!err.isMissingNode()) {
            String code = String.valueOf(Json.text(err, "code"));
            String lower = code.toLowerCase(Locale.ROOT);
            if (lower.contains("sign") || lower.contains("appkey") || lower.contains("app_key")
                    || lower.contains("permission") || lower.contains("token")) {
                throw new SourceException.AuthFailed("AliExpress 인증 오류 (" + code + ")", null);
            }
            if (lower.contains("limit") || lower.contains("frequency")) {
                throw new SourceException.RateLimited("AliExpress 호출 제한 (" + code + ")");
            }
            throw new SourceException.BadResponse("AliExpress 오류 (" + code + ")", null);
        }
        JsonNode rr = root.path(responseKey).path("resp_result");
        if (rr.isMissingNode()) throw new SourceException.BadResponse("AliExpress resp_result 없음", null);
        Integer code = Json.integer(rr.path("resp_code"));
        if (code == null || code != 200) {
            // 405 등: 결과 없음으로 보고 빈 목록 (검색어에 맞는 상품이 없을 때도 200 이 아닐 수 있음)
            if (code != null && code == 405) return mapper.createArrayNode();
            throw new SourceException.BadResponse("AliExpress resp_code=" + code, null);
        }
        JsonNode products = rr.path("result").path("products");
        if (products.has("product")) products = products.path("product");
        if (products.isMissingNode()) return mapper.createArrayNode();
        if (!products.isArray()) throw new SourceException.BadResponse("AliExpress products 형식 오류", null);
        return products;
    }

    List<MallOffer> map(JsonNode products) {
        List<MallOffer> out = new ArrayList<>();
        for (JsonNode n : products) {
            String id = Json.text(n, "product_id");
            BigDecimal price = Json.decimal(n.path("target_sale_price"));
            String currency = Json.text(n, "target_sale_price_currency");
            if (currency == null) currency = "USD";
            if (id == null || price == null) continue;
            // 제휴 링크는 tracking_id 가 있을 때만 (없으면 상품과 무관한 같은 링크가 옴)
            String link = hasTrackingId() && Json.text(n, "promotion_link") != null ? Json.text(n, "promotion_link")
                    : Json.text(n, "product_detail_url");
            Integer days = null;
            String shipTo = Json.text(n, "ship_to_days");
            if (shipTo != null) {
                Matcher m = DAYS.matcher(shipTo);
                if (m.find()) days = Integer.valueOf(m.group(1));
            }
            // 출발국: 응답에 발송지 필드가 없어 중국으로 고정 (해외 창고 상품도 구분 불가. 면세 한도는 더 엄격한 $150 쪽)
            out.add(new MallOffer(id, "DEFAULT", Json.text(n, "product_title"), null, price, currency, null, "NEW",
                    ORIGIN, link, Json.text(n, "product_main_image_url"), StockStatus.IN_STOCK, days, null));
        }
        return out;
    }

    private boolean hasTrackingId() {
        return notBlank(props.trackingId());
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
