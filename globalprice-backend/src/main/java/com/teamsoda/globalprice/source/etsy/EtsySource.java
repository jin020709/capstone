package com.teamsoda.globalprice.source.etsy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.teamsoda.globalprice.config.GlobalPriceProperties;
import com.teamsoda.globalprice.source.Json;
import com.teamsoda.globalprice.source.MallSource;
import com.teamsoda.globalprice.source.SourceException;
import com.teamsoda.globalprice.source.SourceHttp;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Etsy Open API v3 (기본 enabled=false). 2026-11-14 실호출로 확인한 내용:
 * <ul>
 *   <li>인증: x-api-key: {keystring}:{shared_secret}. 한도 초당 5 · 하루 5,000 (응답 헤더 x-limit-*)</li>
 *   <li>검색: GET /v3/application/listings/active?keywords=&limit=&sort_on=score
 *       (sort_on=price 는 최저가 상위가 전부 SVG 도안 · 공예 재료라 쓰지 않음)</li>
 *   <li>가격: price = {amount, divisor, currency_code} → amount ÷ divisor. 통화가 판매자마다 다름(USD · GBP · PHP …)</li>
 *   <li>검색 응답에는 이미지가 없음(images=null) → GET /v3/application/listings/batch?listing_ids=&includes=Images
 *       한 번 더 호출해 url_570xN 을 붙인다 (실패해도 검색 결과는 그대로)</li>
 *   <li>갱신: 같은 batch (최대 100)</li>
 * </ul>
 * 새 공산품 비교에 맞지 않는 것은 condition 으로 표시해 ResultFilter 가 거른다:
 * 디지털 다운로드(listing_type=download) · 공예 재료(is_supply) · 빈티지(when_made 가 2007년 이전).
 * 한국 배송비가 응답에 없으므로 shipping_rules 를 쓴다.
 */
@Component
public class EtsySource implements MallSource {

    public static final String SOURCE_NAME = "ETSY_OPEN_API";
    private static final Logger log = LoggerFactory.getLogger(EtsySource.class);
    private static final Pattern YEAR = Pattern.compile("(\\d{4})");

    private final GlobalPriceProperties.Etsy props;
    private final RestClient rest;
    private final ObjectMapper mapper;

    public EtsySource(GlobalPriceProperties props, RestClient.Builder externalRestClientBuilder, ObjectMapper mapper) {
        this.props = props.sources().etsy();
        this.rest = externalRestClientBuilder.build();
        this.mapper = mapper;
    }

    @Override public String sourceName() { return SOURCE_NAME; }
    @Override public String storeKey() { return "etsy"; }
    @Override public int dailyBudget() { return props.dailyBudget(); }
    @Override public int refreshBatchSize() { return 100; }

    @Override
    public boolean enabled() {
        return props.enabled() && props.apiKey() != null && !props.apiKey().isBlank();
    }

    @Override
    public List<MallOffer> search(String query, SearchOptions options) {
        String body = SourceHttp.call(SOURCE_NAME, () -> rest.get()
                .uri(props.baseUrl() + "/v3/application/listings/active?keywords={q}&limit={l}&sort_on=score",
                        query, Math.min(options.limit(), 100))
                .header("x-api-key", props.apiKey())
                .retrieve().body(String.class));
        List<MallOffer> offers = map(results(body));
        return withImages(offers);
    }

    @Override
    public List<MallOffer> refresh(List<String> externalProductIds) {
        if (externalProductIds.isEmpty()) return List.of();
        return map(results(batch(externalProductIds)));
    }

    private String batch(List<String> ids) {
        return SourceHttp.call(SOURCE_NAME, () -> rest.get()
                .uri(props.baseUrl() + "/v3/application/listings/batch?listing_ids={ids}&includes=Images",
                        String.join(",", ids))
                .header("x-api-key", props.apiKey())
                .retrieve().body(String.class));
    }

    /** 검색 결과에 이미지 붙이기 (batch 1회). 실패하면 이미지 없이 그대로 */
    private List<MallOffer> withImages(List<MallOffer> offers) {
        List<String> ids = offers.stream().filter(o -> o.imageUrl() == null).map(MallOffer::externalProductId)
                .limit(100).toList();
        if (ids.isEmpty()) return offers;
        Map<String, String> images = new HashMap<>();
        try {
            for (JsonNode n : results(batch(ids))) {
                String img = firstImage(n);
                if (img != null) images.put(Json.text(n, "listing_id"), img);
            }
        } catch (SourceException e) {
            log.info("[{}] 이미지 조회 실패 → 이미지 없이 사용: {}", SOURCE_NAME, e.getMessage());
            return offers;
        }
        return offers.stream().map(o -> o.imageUrl() != null || !images.containsKey(o.externalProductId()) ? o
                : new MallOffer(o.externalProductId(), o.externalVariantId(), o.title(), o.variantName(), o.price(),
                        o.currencyCode(), o.shippingFee(), o.condition(), o.originCountry(), o.productUrl(),
                        images.get(o.externalProductId()), o.stock(), o.shipDays(), o.gtin()))
                .toList();
    }

    JsonNode results(String body) {
        JsonNode root;
        try {
            root = mapper.readTree(body == null ? "" : body);
        } catch (RuntimeException e) {
            throw new SourceException.BadResponse("Etsy 응답 JSON 파싱 실패", null);
        }
        JsonNode results = root.path("results");
        if (results.isMissingNode()) return mapper.createArrayNode();
        if (!results.isArray()) throw new SourceException.BadResponse("Etsy results 형식 오류", null);
        return results;
    }

    List<MallOffer> map(JsonNode results) {
        List<MallOffer> out = new ArrayList<>();
        for (JsonNode n : results) {
            String id = Json.text(n, "listing_id");
            JsonNode price = n.path("price");
            BigDecimal amount = Json.decimal(price.path("amount"));
            BigDecimal divisor = Json.decimal(price.path("divisor"));
            String currency = Json.text(price, "currency_code");
            if (id == null || amount == null || currency == null) continue;
            if (divisor == null || divisor.signum() == 0) divisor = BigDecimal.ONE;
            BigDecimal value = amount.divide(divisor, 4, RoundingMode.HALF_UP).stripTrailingZeros();
            if (value.scale() < 0) value = value.setScale(0);
            Integer qty = Json.integer(n.path("quantity"));
            String state = Json.text(n, "state");
            StockStatus stock = state != null && !"active".equals(state) ? StockStatus.OUT_OF_STOCK
                    : qty == null ? StockStatus.UNKNOWN : qty > 0 ? StockStatus.IN_STOCK : StockStatus.OUT_OF_STOCK;
            out.add(new MallOffer(id, "DEFAULT", Json.text(n, "title"), null, value, currency, null, condition(n), null,
                    Json.text(n, "url"), firstImage(n), stock, null, null));
        }
        return out;
    }

    /** NEW 가 아니면 ResultFilter 가 제외한다 */
    static String condition(JsonNode n) {
        if ("download".equals(Json.text(n, "listing_type"))) return "DIGITAL";
        if (n.path("is_supply").asBoolean(false)) return "SUPPLY";
        String whenMade = Json.text(n, "when_made");
        if (whenMade != null && !"made_to_order".equals(whenMade)) {
            Matcher m = YEAR.matcher(whenMade);
            // before_2007 · 2000_2006 · 1990s 등 → 빈티지
            if (whenMade.startsWith("before") || !m.find() || Integer.parseInt(m.group(1)) < 2007) return "VINTAGE";
        }
        return "NEW";
    }

    private static String firstImage(JsonNode n) {
        for (JsonNode img : n.path("images")) {
            String url = Json.text(img, "url_570xN");
            if (url != null) return url;
        }
        return null;
    }
}
