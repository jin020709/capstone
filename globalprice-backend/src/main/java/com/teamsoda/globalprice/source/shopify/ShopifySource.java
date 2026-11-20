package com.teamsoda.globalprice.source.shopify;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.teamsoda.globalprice.config.GlobalPriceProperties;
import com.teamsoda.globalprice.source.Json;
import com.teamsoda.globalprice.source.MallSource;
import com.teamsoda.globalprice.source.SourceException;
import com.teamsoda.globalprice.source.SourceHttp;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Shopify Global Catalog (UCP MCP). POST https://catalog.shopify.com/api/ucp/mcp, JSON-RPC tools/call.
 * <ul>
 *   <li>검색: search_catalog — catalog.query, catalog.filters(ships_to · condition), catalog.pagination.limit(최대 50)</li>
 *   <li>갱신: lookup_catalog — catalog.ids (1~50, 상품 또는 ProductVariant GID)</li>
 *   <li>응답: result.structuredContent.products[] (없으면 result.content[].text 의 JSON)</li>
 *   <li>가격: 최소 통화 단위 정수 (USD 8999 = 89.99) → 통화별 소수 자릿수로 환산</li>
 *   <li>응답은 JSON 또는 text/event-stream(SSE). 둘 다 파싱</li>
 * </ul>
 * 1차는 상품 아래 판매자(seller)별로 가장 싼 옵션 1개를 offer 1건으로 펼친다 (묶지 않음).
 * external_product_id 는 그 옵션의 ProductVariant GID (판매자별로 다르다).
 */
@Component
public class ShopifySource implements MallSource {

    public static final String SOURCE_NAME = "SHOPIFY_CATALOG";

    private final GlobalPriceProperties.Shopify props;
    private final RestClient rest;
    private final ObjectMapper mapper;
    private final AtomicInteger rpcId = new AtomicInteger();

    public ShopifySource(GlobalPriceProperties props, RestClient.Builder externalRestClientBuilder,
                         ObjectMapper mapper) {
        this.props = props.sources().shopify();
        this.rest = externalRestClientBuilder.build();
        this.mapper = mapper;
    }

    @Override public String sourceName() { return SOURCE_NAME; }
    @Override public String storeKey() { return "shopify"; }
    @Override public int dailyBudget() { return props.dailyBudget(); }
    @Override public int refreshBatchSize() { return 50; }

    @Override
    public boolean enabled() {
        return props.enabled() && props.agentProfile() != null && !props.agentProfile().isBlank();
    }

    @Override
    public List<MallOffer> search(String query, SearchOptions options) {
        ObjectNode catalog = mapper.createObjectNode();
        catalog.put("query", query);
        ObjectNode filters = catalog.putObject("filters");
        filters.putObject("ships_to").put("country", "KR");
        filters.putArray("condition").add("new");
        // address_country=KR 만 주면 상점이 원화 현지가를 내려준다 → 해외 상점인데 "원화 결제(수수료 0)"로
        // 계산되는 문제가 있어 통화를 USD 로 고정한다 (해외결제 수수료 · 환율 계산을 다른 소스와 같게)
        catalog.putObject("context").put("address_country", "KR").put("currency", "USD");
        catalog.putObject("pagination").put("limit", Math.min(Math.max(options.limit(), 1), 50));
        JsonNode result = call("search_catalog", catalog);
        return toOffers(products(result), null, options.limit());
    }

    @Override
    public List<MallOffer> refresh(List<String> externalProductIds) {
        if (externalProductIds.isEmpty()) return List.of();
        ObjectNode catalog = mapper.createObjectNode();
        ArrayNode ids = catalog.putArray("ids");
        externalProductIds.stream().limit(50).forEach(ids::add);
        catalog.putObject("context").put("address_country", "KR").put("currency", "USD");
        JsonNode result = call("lookup_catalog", catalog);
        return toOffers(products(result), new HashSet<>(externalProductIds), Integer.MAX_VALUE);
    }

    JsonNode call(String tool, ObjectNode catalog) {
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("method", "tools/call");
        req.put("id", rpcId.incrementAndGet());
        ObjectNode params = req.putObject("params");
        params.put("name", tool);
        ObjectNode args = params.putObject("arguments");
        args.putObject("meta").putObject("ucp-agent").put("profile", props.agentProfile());
        args.set("catalog", catalog);
        String body = SourceHttp.call(SOURCE_NAME, () -> rest.post()
                .uri(props.endpoint())
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapper.writeValueAsString(req))
                .retrieve().body(String.class));
        return parseRpc(body);
    }

    /** JSON 본문 또는 SSE(data: 줄) 본문에서 JSON-RPC 응답의 result 를 꺼낸다 */
    JsonNode parseRpc(String body) {
        if (body == null || body.isBlank()) throw new SourceException.BadResponse("Shopify 빈 응답", null);
        String trimmed = body.trim();
        JsonNode msg = null;
        if (trimmed.startsWith("{")) {
            msg = readJson(trimmed);
        } else {
            StringBuilder data = new StringBuilder();
            for (String line : trimmed.split("\r?\n")) {
                if (line.startsWith("data:")) {
                    data.append(line.substring(5).trim());
                } else if (line.isBlank() && !data.isEmpty()) {
                    JsonNode n = readJson(data.toString());
                    if (n.has("result") || n.has("error")) msg = n;
                    data.setLength(0);
                }
            }
            if (!data.isEmpty()) {
                JsonNode n = readJson(data.toString());
                if (n.has("result") || n.has("error")) msg = n;
            }
        }
        if (msg == null) throw new SourceException.BadResponse("Shopify 응답에 JSON-RPC 메시지 없음", null);
        if (msg.has("error")) {
            String m = Json.text(msg.path("error"), "message");
            throw new SourceException.BadResponse("Shopify JSON-RPC 오류: " + (m == null ? "?" : m), null);
        }
        JsonNode result = msg.path("result");
        if (result.path("isError").asBoolean(false)) {
            throw new SourceException.BadResponse("Shopify 도구 오류", null);
        }
        return result;
    }

    JsonNode products(JsonNode result) {
        JsonNode sc = result.path("structuredContent");
        if (sc.isMissingNode() || sc.isNull()) {
            for (JsonNode c : result.path("content")) {
                String text = Json.text(c, "text");
                if (text != null && text.startsWith("{")) {
                    sc = readJson(text);
                    break;
                }
            }
        }
        JsonNode products = sc.path("products");
        if (products.isMissingNode()) return mapper.createArrayNode();
        if (!products.isArray()) throw new SourceException.BadResponse("Shopify products 형식 오류", null);
        return products;
    }

    /** 상품 → 판매자별 최저가 옵션 1건씩. onlyVariantIds 가 있으면 그 옵션만 (갱신용) */
    List<MallOffer> toOffers(JsonNode products, Set<String> onlyVariantIds, int limit) {
        List<MallOffer> out = new ArrayList<>();
        for (JsonNode p : products) {
            String title = Json.text(p, "title");
            String productUrl = Json.text(p, "url");
            String image = null;
            for (JsonNode m : p.path("media")) {
                if (image == null && (Json.text(m, "type") == null || "image".equalsIgnoreCase(Json.text(m, "type")))) {
                    image = Json.text(m, "url");
                }
            }
            Map<String, MallOffer> bySeller = new LinkedHashMap<>();
            for (JsonNode v : p.path("variants")) {
                String vid = Json.text(v, "id");
                if (vid == null) continue;
                if (onlyVariantIds != null && !onlyVariantIds.contains(vid) && !matchesInput(v, onlyVariantIds)) continue;
                BigDecimal price = minorToMajor(Json.decimal(v.path("price").path("amount")),
                        Json.text(v.path("price"), "currency"));
                String currency = Json.text(v.path("price"), "currency");
                if (price == null || currency == null) continue;
                String condition = null;
                for (JsonNode c : v.path("condition")) condition = Json.text(c);
                JsonNode avail = v.path("availability");
                StockStatus stock = avail.isMissingNode() ? StockStatus.UNKNOWN
                        : avail.path("available").asBoolean(false) ? StockStatus.IN_STOCK : StockStatus.OUT_OF_STOCK;
                String variantTitle = Json.text(v, "title");
                if ("Default Title".equalsIgnoreCase(variantTitle)) variantTitle = null;
                // 구매 링크: 옵션 상품 페이지 > 상품 페이지 > 체크아웃 > 판매자 (attribution 파라미터는 그대로 둔다)
                String url = firstNonNull(Json.text(v, "url"), productUrl, Json.text(v, "checkout_url"),
                        Json.text(v.path("seller"), "url"));
                String seller = Json.text(v.path("seller"), "id");
                MallOffer o = new MallOffer(onlyVariantIds != null ? requestedId(v, onlyVariantIds) : vid, "DEFAULT",
                        title, variantTitle, price, currency, null,
                        condition == null ? null : condition.toUpperCase(Locale.ROOT), null, url, image, stock, null,
                        null);
                String key = seller == null ? vid : seller;
                MallOffer prev = bySeller.get(key);
                if (prev == null || o.price().compareTo(prev.price()) < 0) bySeller.put(key, o);
            }
            out.addAll(bySeller.values());
            if (out.size() >= limit) break;
        }
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    private static String firstNonNull(String... values) {
        for (String v : values) {
            if (v != null) return v;
        }
        return null;
    }

    private static boolean matchesInput(JsonNode v, Set<String> ids) {
        for (JsonNode in : v.path("inputs")) {
            String id = in.isValueNode() ? Json.text(in) : Json.text(in, "id");
            if (id != null && ids.contains(id)) return true;
        }
        return false;
    }

    private static String requestedId(JsonNode v, Set<String> ids) {
        String vid = Json.text(v, "id");
        if (ids.contains(vid)) return vid;
        for (JsonNode in : v.path("inputs")) {
            String id = in.isValueNode() ? Json.text(in) : Json.text(in, "id");
            if (id != null && ids.contains(id)) return id;
        }
        return vid;
    }

    /** 최소 통화 단위 → 소수. JPY 는 0자리, USD 는 2자리 */
    public static BigDecimal minorToMajor(BigDecimal minor, String currency) {
        if (minor == null || currency == null) return null;
        int digits;
        try {
            digits = Math.max(Currency.getInstance(currency).getDefaultFractionDigits(), 0);
        } catch (IllegalArgumentException e) {
            digits = 2;
        }
        return minor.movePointLeft(digits);
    }

    private JsonNode readJson(String s) {
        try {
            return mapper.readTree(s);
        } catch (RuntimeException e) {
            throw new SourceException.BadResponse("Shopify 응답 JSON 파싱 실패", null);
        }
    }
}
