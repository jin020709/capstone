package com.teamsoda.globalprice.catalog;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import com.teamsoda.globalprice.source.MallSource.MallOffer;
import com.teamsoda.globalprice.source.MallSource.StockStatus;

/**
 * products · mall_products · mall_product_variants · price_history 저장과 조회 (JdbcTemplate).
 * 같은 상품 묶기: {@link ProductMatcher} 키(products.match_key)가 같은 리스팅은 같은 products 행에 붙는다
 * (products 1행 : mall_products N행). 키를 못 만든 리스팅은 지금처럼 상품 1행을 따로 만든다.
 */
@Repository
public class CatalogRepository {

    /** 저장된 offer 1건 (상품 + 쇼핑몰 상품 + 옵션) */
    public record StoredOffer(long productId, String productName, String imageUrl, String description,
                              String brandName, String modelNumber, Long categoryId, String categoryName,
                              long mallProductId, long mallId, String externalProductId, String productUrl,
                              String currencyCode, BigDecimal shippingFee, LocalDateTime lastFetchedAt,
                              long mallVariantId, String externalVariantId, String variantName,
                              BigDecimal currentPrice, StockStatus stockStatus, LocalDateTime variantUpdatedAt,
                              String listingTitle) {
        /** 마지막으로 가격을 받아온 시각 (last_fetched_at 이 없으면 옵션 updated_at) */
        public LocalDateTime fetchedAt() {
            return lastFetchedAt != null ? lastFetchedAt : variantUpdatedAt;
        }
    }

    /** productName = 묶인 상품의 대표 이름 (판매처 줄에는 리스팅 원래 이름을 따로 씀) */
    public record SavedIds(long productId, long mallProductId, long mallVariantId, String productName) {
    }

    public record DailyLowest(LocalDate date, long lowestTotalKrw) {
    }

    private static final String VIEW_SELECT = """
            SELECT p.product_id, p.product_name, p.image_url, p.description, p.model_number, p.category_id,
                   b.brand_name, c.category_name,
                   mp.mall_product_id, mp.mall_id, mp.external_product_id, mp.listing_title, mp.product_url,
                   mp.currency_code,
                   mp.shipping_fee, mp.last_fetched_at,
                   v.mall_variant_id, v.external_variant_id, v.variant_name, v.current_price, v.stock_status,
                   v.updated_at
            FROM products p
            JOIN mall_products mp ON mp.product_id = p.product_id
            JOIN mall_product_variants v ON v.mall_product_id = mp.mall_product_id
            LEFT JOIN brands b ON b.brand_id = p.brand_id
            LEFT JOIN categories c ON c.category_id = p.category_id
            """;

    private static final RowMapper<StoredOffer> VIEW_MAPPER = (rs, i) -> new StoredOffer(
            rs.getLong("product_id"), rs.getString("product_name"), rs.getString("image_url"),
            rs.getString("description"), rs.getString("brand_name"), rs.getString("model_number"),
            rs.getObject("category_id", Long.class), rs.getString("category_name"),
            rs.getLong("mall_product_id"), rs.getLong("mall_id"), rs.getString("external_product_id"),
            rs.getString("product_url"), rs.getString("currency_code"), rs.getBigDecimal("shipping_fee"),
            rs.getObject("last_fetched_at", LocalDateTime.class), rs.getLong("mall_variant_id"),
            rs.getString("external_variant_id"), rs.getString("variant_name"), rs.getBigDecimal("current_price"),
            stock(rs.getString("stock_status")), rs.getObject("updated_at", LocalDateTime.class),
            rs.getString("listing_title"));

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public CatalogRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    // ---------------- 저장 (4-3) ----------------

    /**
     * 같은 트랜잭션 안에서 호출할 것. (mall_id, external_product_id) 기준 upsert.
     * 처음 보는 리스팅은 묶기 키가 같은 상품에 붙이고(없으면 새로 만듦), 이미 있는 리스팅이 아직 혼자인
     * 예전 상품(키 없음)에 있으면 같은 키의 상품으로 옮긴다 → 예전 데이터도 다시 검색되면 합쳐진다.
     */
    public SavedIds upsert(long mallId, MallOffer o, LocalDateTime now) {
        String externalId = cut(o.externalProductId(), 255);
        List<long[]> found = jdbc.query(
                "SELECT mall_product_id, product_id FROM mall_products WHERE mall_id = ? AND external_product_id = ?",
                (rs, i) -> new long[] { rs.getLong(1), rs.getLong(2) }, mallId, externalId);
        long mallProductId;
        long productId;
        String url = cut(o.productUrl(), 1000);
        String image = cut(o.imageUrl(), 1000);
        String listingTitle = nameOf(o);
        ProductMatcher.Match match = ProductMatcher.match(matchText(o));
        if (found.isEmpty()) {
            productId = productFor(match, listingTitle, image);
            mallProductId = insert("""
                    INSERT INTO mall_products (product_id, mall_id, external_product_id, listing_title, product_url,
                                               currency_code, shipping_fee, last_fetched_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                    productId, mallId, externalId, listingTitle, url, o.currencyCode(), o.shippingFee(),
                    Timestamp.valueOf(now));
        } else {
            mallProductId = found.getFirst()[0];
            productId = regroup(mallProductId, found.getFirst()[1], match, image, listingTitle);
            jdbc.update("""
                    UPDATE mall_products SET listing_title = ?, product_url = COALESCE(?, product_url),
                           currency_code = ?, shipping_fee = ?, last_fetched_at = ?
                    WHERE mall_product_id = ?""",
                    listingTitle, url, o.currencyCode(), o.shippingFee(), Timestamp.valueOf(now), mallProductId);
            if (image != null) {
                jdbc.update("UPDATE products SET image_url = ? WHERE product_id = ? AND image_url IS NULL",
                        image, productId);
            }
        }
        String variantId = o.externalVariantId() == null ? "DEFAULT" : cut(o.externalVariantId(), 255);
        StockStatus stock = o.stock() == null ? StockStatus.UNKNOWN : o.stock();
        jdbc.update("""
                INSERT INTO mall_product_variants (mall_product_id, external_variant_id, variant_name, current_price,
                                                   stock_status, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE variant_name = COALESCE(VALUES(variant_name), variant_name),
                    current_price = VALUES(current_price), stock_status = VALUES(stock_status),
                    updated_at = VALUES(updated_at)""",
                mallProductId, variantId, cut(o.variantName(), 255), o.price(), stock.name(), Timestamp.valueOf(now));
        Long mallVariantId = jdbc.queryForObject(
                "SELECT mall_variant_id FROM mall_product_variants WHERE mall_product_id = ? AND external_variant_id = ?",
                Long.class, mallProductId, variantId);
        String productName = jdbc.queryForObject("SELECT product_name FROM products WHERE product_id = ?",
                String.class, productId);
        return new SavedIds(productId, mallProductId, mallVariantId, productName);
    }

    /** 처음 보는 리스팅의 상품: 같은 묶기 키의 상품이 있으면 그것, 없으면 새로 만든다 */
    private long productFor(ProductMatcher.Match match, String listingTitle, String image) {
        if (match == null) {
            return insert("INSERT INTO products (product_name, image_url) VALUES (?, ?)", listingTitle, image);
        }
        Long existing = productByKey(match.key());
        if (existing != null) {
            if (image != null) {
                jdbc.update("UPDATE products SET image_url = ? WHERE product_id = ? AND image_url IS NULL",
                        image, existing);
            }
            return existing;
        }
        // 동시에 같은 키를 처음 넣으면 UNIQUE 위반 → CatalogService.save 가 한 번 더 시도해서 위 경로로 감
        return insert("INSERT INTO products (product_name, image_url, match_key) VALUES (?, ?, ?)",
                productName(match, listingTitle), image, match.key());
    }

    /**
     * 이미 저장된 리스팅의 상품 정리. 지금 상품명으로 만든 묶기 키가 상품의 키와 다르면 맞는 상품으로 옮긴다.
     * 예전 방식(키 없는 상품 1 : 리스팅 1)이나, 판단 규칙이 나중에 고쳐진 경우("iPhone 17 Air" → iPhone Air)도
     * 다음 검색 때 저절로 바로잡힌다.
     * <ul>
     *   <li>리스팅이 혼자인 상품이고 맞는 키의 상품이 아직 없으면 → 이 상품에 키를 달고 대표 이름으로 바꿈</li>
     *   <li>그 밖에는 → 리스팅을 맞는 키의 상품(없으면 새로 만듦)으로 옮김. 빈 상품은 찜을 옮긴 뒤 지움.
     *       혼자인 상품에 가격 알림이 걸려 있으면 알림이 끊기지 않게 그대로 둠</li>
     * </ul>
     */
    private long regroup(long mallProductId, long productId, ProductMatcher.Match match, String image,
                         String listingTitle) {
        if (match == null) return productId;
        String currentKey = jdbc.query("SELECT match_key FROM products WHERE product_id = ?",
                (rs, i) -> rs.getString(1), productId).stream().findFirst().orElse(null);
        if (match.key().equals(currentKey)) {
            // 예전에 상품명이 두 번 붙어 저장된 것 정리 (모델코드 · 상품명 키 상품은 리스팅 이름 그대로)
            if (match.key().startsWith("t:") || match.key().startsWith("code:")) {
                jdbc.update("UPDATE products SET product_name = ? WHERE product_id = ? AND product_name <> ?",
                        listingTitle, productId, listingTitle);
            }
            return productId;
        }
        Integer listings = jdbc.queryForObject("SELECT COUNT(*) FROM mall_products WHERE product_id = ?",
                Integer.class, productId);
        boolean alone = listings != null && listings == 1;
        Long target = productByKey(match.key());
        if (target == null && alone) {
            jdbc.update("UPDATE products SET match_key = ?, product_name = ? WHERE product_id = ?",
                    match.key(), productName(match, listingTitle), productId);
            return productId;
        }
        if (alone) {
            Integer alerts = jdbc.queryForObject("SELECT COUNT(*) FROM price_alerts WHERE product_id = ?",
                    Integer.class, productId);
            if (alerts != null && alerts > 0) return productId;
        }
        if (target == null) {
            target = insert("INSERT INTO products (product_name, image_url, match_key) VALUES (?, ?, ?)",
                    productName(match, listingTitle), image, match.key());
        }
        jdbc.update("UPDATE mall_products SET product_id = ? WHERE mall_product_id = ?", target, mallProductId);
        if (alone) {
            jdbc.update("UPDATE IGNORE wishlists SET product_id = ? WHERE product_id = ?", target, productId);
            jdbc.update("DELETE FROM products WHERE product_id = ?", productId);   // 남은 중복 찜은 CASCADE 로 정리
        }
        return target;
    }

    /** 상품의 묶기 키 (없으면 null) */
    public String matchKey(long productId) {
        return jdbc.query("SELECT match_key FROM products WHERE product_id = ?", (rs, i) -> rs.getString(1), productId)
                .stream().findFirst().orElse(null);
    }

    /** 묶기 키가 prefix 로 시작하는 상품들 (id → key). 다른 구성 찾기용 */
    public Map<Long, String> productsByKeyPrefix(String prefix) {
        Map<Long, String> out = new LinkedHashMap<>();
        String like = prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        jdbc.query("SELECT product_id, match_key FROM products WHERE match_key LIKE ? ORDER BY product_id",
                (rs, i) -> new Object[] { rs.getLong(1), rs.getString(2) }, like)
                .forEach(r -> out.put((Long) r[0], (String) r[1]));
        return out;
    }

    private Long productByKey(String key) {
        return jdbc.query("SELECT product_id FROM products WHERE match_key = ?", (rs, i) -> rs.getLong(1), key)
                .stream().findFirst().orElse(null);
    }

    /** 묶기 판단에 쓰는 글자: 상품명 + 옵션 이름 (Shopify 는 용량이 옵션에만 있음) */
    static String matchText(MallOffer o) {
        String title = o.title() == null ? "" : o.title();
        String v = o.variantName() == null ? "" : o.variantName().strip();
        // 옵션 이름이 상품명을 그대로 담고 있으면 (Shopify 일부 상점) 두 번 붙이지 않음
        if (v.isEmpty() || title.toLowerCase(java.util.Locale.ROOT).contains(v.toLowerCase(java.util.Locale.ROOT))) return title;
        return title + " " + v;
    }

    /** 상품 대표 이름: 모델 규칙으로 묶이면 정리된 이름, 모델코드 · 상품명 키면 리스팅 원래 이름 */
    private static String productName(ProductMatcher.Match match, String listingTitle) {
        return match.key().startsWith("t:") || match.key().startsWith("code:") ? listingTitle : cut(match.name(), 255);
    }

    /** 마지막 기록과 가격이 같고 6시간이 안 지났으면 기록하지 않는다. 기록했으면 true */
    public boolean recordHistory(long mallVariantId, BigDecimal price, String currency, Long totalKrw,
                                 LocalDateTime now, java.time.Duration minGap) {
        List<Object[]> last = jdbc.query("""
                SELECT product_price, recorded_at FROM price_history
                WHERE mall_variant_id = ? ORDER BY recorded_at DESC, history_id DESC LIMIT 1""",
                (rs, i) -> new Object[] { rs.getBigDecimal(1), rs.getObject(2, LocalDateTime.class) }, mallVariantId);
        if (!last.isEmpty()) {
            BigDecimal lastPrice = (BigDecimal) last.getFirst()[0];
            LocalDateTime lastAt = (LocalDateTime) last.getFirst()[1];
            if (lastPrice.compareTo(price) == 0 && lastAt.isAfter(now.minus(minGap))) return false;
        }
        jdbc.update("""
                INSERT INTO price_history (mall_variant_id, product_price, currency_code, total_amount_krw, recorded_at)
                VALUES (?, ?, ?, ?, ?)""",
                mallVariantId, price, currency, totalKrw, Timestamp.valueOf(now));
        return true;
    }

    // ---------------- 조회 ----------------

    public List<StoredOffer> findByProductIds(Collection<Long> productIds) {
        if (productIds.isEmpty()) return List.of();
        return named.query(VIEW_SELECT + " WHERE p.product_id IN (:ids) ORDER BY p.product_id, v.mall_variant_id",
                new MapSqlParameterSource("ids", productIds), VIEW_MAPPER);
    }

    public List<StoredOffer> findByProductId(long productId) {
        return findByProductIds(List.of(productId));
    }

    public Optional<String> productName(long productId) {
        return jdbc.query("SELECT product_name FROM products WHERE product_id = ?",
                (rs, i) -> rs.getString(1), productId).stream().findFirst();
    }

    public boolean productExists(long productId) {
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE product_id = ?", Integer.class, productId);
        return c != null && c > 0;
    }

    /** 찜 또는 활성 알림이 걸린 상품의 쇼핑몰 상품 (오래된 순) */
    public List<StoredOffer> findWatched() {
        return jdbc.query(VIEW_SELECT + """
                 WHERE p.product_id IN (SELECT product_id FROM wishlists
                                        UNION SELECT product_id FROM price_alerts
                                              WHERE is_active AND product_id IS NOT NULL)
                 ORDER BY mp.last_fetched_at IS NOT NULL, mp.last_fetched_at, mp.mall_product_id""", VIEW_MAPPER);
    }

    /** 날짜별 최저 total_amount_krw (상품의 모든 옵션 기준, null 제외) */
    public List<DailyLowest> dailyLowest(long productId, LocalDateTime from) {
        return jdbc.query("""
                SELECT DATE(h.recorded_at) AS d, MIN(h.total_amount_krw) AS lowest
                FROM price_history h
                JOIN mall_product_variants v ON v.mall_variant_id = h.mall_variant_id
                JOIN mall_products mp ON mp.mall_product_id = v.mall_product_id
                WHERE mp.product_id = ? AND h.recorded_at >= ? AND h.total_amount_krw IS NOT NULL
                GROUP BY DATE(h.recorded_at) ORDER BY d""",
                (rs, i) -> new DailyLowest(rs.getObject("d", LocalDate.class), rs.getBigDecimal("lowest").longValue()),
                productId, Timestamp.valueOf(from));
    }

    /** 기간 [from, to) 의 최저 total_amount_krw. 없으면 null */
    public Long lowestBetween(long productId, LocalDateTime from, LocalDateTime to) {
        BigDecimal v = jdbc.queryForObject("""
                SELECT MIN(h.total_amount_krw) FROM price_history h
                JOIN mall_product_variants v ON v.mall_variant_id = h.mall_variant_id
                JOIN mall_products mp ON mp.mall_product_id = v.mall_product_id
                WHERE mp.product_id = ? AND h.recorded_at >= ? AND h.recorded_at < ?""",
                BigDecimal.class, productId, Timestamp.valueOf(from), Timestamp.valueOf(to));
        return v == null ? null : v.longValue();
    }

    // ---------------- 내부 ----------------

    private long insert(String sql, Object... args) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps;
        }, keys);
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("생성된 키 없음");
        return key.longValue();
    }

    private static String nameOf(MallOffer o) {
        String name = o.title() == null || o.title().isBlank() ? o.externalProductId() : o.title();
        return cut(name, 255);
    }

    static String cut(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    static StockStatus stock(String s) {
        try {
            return s == null ? StockStatus.UNKNOWN : StockStatus.valueOf(s);
        } catch (IllegalArgumentException e) {
            return StockStatus.UNKNOWN;
        }
    }

    /** 테스트 · 디버그용: 상품 수 */
    public List<Long> allProductIds() {
        return new ArrayList<>(jdbc.queryForList("SELECT product_id FROM products ORDER BY product_id", Long.class));
    }
}
