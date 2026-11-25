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
 * products · mall_products · mall_product_variants · price_history 저장과 조회 (JdbcTemplate). SQL 만 둔다.
 * 같은 상품 묶기 판단은 {@link ProductGrouping} (products 1행 : mall_products N행, 키는 products.match_key).
 */
@Repository
public class CatalogRepository implements ProductGrouping.Store {

    /** 저장된 offer 1건 (상품 + 쇼핑몰 상품 + 옵션) */
    public record StoredOffer(long productId, String productName, String imageUrl, String description,
                              String brandName, String modelNumber, Long categoryId, String categoryName,
                              long mallProductId, long mallId, String externalProductId, String productUrl,
                              String currencyCode, BigDecimal shippingFee, LocalDateTime lastFetchedAt,
                              long mallVariantId, String externalVariantId, String variantName,
                              BigDecimal currentPrice, StockStatus stockStatus, LocalDateTime variantUpdatedAt,
                              String listingTitle, String matchKey) {
        /** 마지막으로 가격을 받아온 시각 (last_fetched_at 이 없으면 옵션 updated_at) */
        public LocalDateTime fetchedAt() {
            return lastFetchedAt != null ? lastFetchedAt : variantUpdatedAt;
        }
    }

    /** productName · matchKey = 묶인 상품의 대표 이름 · 묶기 키 (판매처 줄에는 리스팅 원래 이름을 따로 씀) */
    public record SavedIds(long productId, long mallProductId, long mallVariantId, String productName,
                           String matchKey) {
    }

    public record DailyLowest(LocalDate date, long lowestTotalKrw) {
    }

    private static final String VIEW_SELECT = """
            SELECT p.product_id, p.product_name, p.image_url, p.description, p.model_number, p.category_id,
                   p.match_key, b.brand_name, c.category_name,
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
            rs.getString("listing_title"), rs.getString("match_key"));

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public CatalogRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    // ---------------- 저장 (4-3). 언제 무엇을 저장할지는 CatalogService · ProductGrouping 이 정한다 ----------------

    /** 저장된 리스팅 위치 */
    public record ListingRef(long mallProductId, long productId) {
    }

    /** (mall_id, external_product_id) 로 저장된 리스팅. 없으면 null */
    public ListingRef findListing(long mallId, String externalProductId) {
        List<ListingRef> found = jdbc.query(
                "SELECT mall_product_id, product_id FROM mall_products WHERE mall_id = ? AND external_product_id = ?",
                (rs, i) -> new ListingRef(rs.getLong(1), rs.getLong(2)), mallId, cut(externalProductId, 255));
        return found.isEmpty() ? null : found.getFirst();
    }

    public long insertListing(long productId, long mallId, MallOffer o, LocalDateTime now) {
        return insert("""
                INSERT INTO mall_products (product_id, mall_id, external_product_id, listing_title, product_url,
                                           currency_code, shipping_fee, last_fetched_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                productId, mallId, cut(o.externalProductId(), 255), listingTitle(o), cut(o.productUrl(), 1000),
                o.currencyCode(), o.shippingFee(), Timestamp.valueOf(now));
    }

    public void updateListing(long mallProductId, MallOffer o, LocalDateTime now) {
        jdbc.update("""
                UPDATE mall_products SET listing_title = ?, product_url = COALESCE(?, product_url),
                       currency_code = ?, shipping_fee = ?, last_fetched_at = ?
                WHERE mall_product_id = ?""",
                listingTitle(o), cut(o.productUrl(), 1000), o.currencyCode(), o.shippingFee(), Timestamp.valueOf(now),
                mallProductId);
    }

    /** (mall_product_id, external_variant_id) 기준 upsert → mall_variant_id */
    public long upsertVariant(long mallProductId, MallOffer o, LocalDateTime now) {
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
        Long id = jdbc.queryForObject(
                "SELECT mall_variant_id FROM mall_product_variants WHERE mall_product_id = ? AND external_variant_id = ?",
                Long.class, mallProductId, variantId);
        if (id == null) throw new IllegalStateException("mall_variant_id 없음");
        return id;
    }

    /** [product_name, match_key] */
    public String[] nameAndKey(long productId) {
        return jdbc.queryForObject("SELECT product_name, match_key FROM products WHERE product_id = ?",
                (rs, i) -> new String[] { rs.getString(1), rs.getString(2) }, productId);
    }

    // ---- ProductGrouping.Store ----

    @Override
    public Long productByKey(String key) {
        List<Long> ids = jdbc.queryForList("SELECT product_id FROM products WHERE match_key = ?", Long.class, key);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    @Override
    public long insertProduct(String name, String imageUrl, String matchKey) {
        return insert("INSERT INTO products (product_name, image_url, match_key) VALUES (?, ?, ?)",
                name, imageUrl, matchKey);
    }

    @Override
    public void fillImage(long productId, String imageUrl) {
        jdbc.update("UPDATE products SET image_url = ? WHERE product_id = ? AND image_url IS NULL", imageUrl, productId);
    }

    @Override
    public void setKeyAndName(long productId, String matchKey, String name) {
        jdbc.update("UPDATE products SET match_key = ?, product_name = ? WHERE product_id = ?", matchKey, name,
                productId);
    }

    @Override
    public void rename(long productId, String name) {
        jdbc.update("UPDATE products SET product_name = ? WHERE product_id = ? AND product_name <> ?", name, productId,
                name);
    }

    @Override
    public int listingCount(long productId) {
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM mall_products WHERE product_id = ?", Integer.class,
                productId);
        return c == null ? 0 : c;
    }

    @Override
    public int alertCount(long productId) {
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM price_alerts WHERE product_id = ?", Integer.class,
                productId);
        return c == null ? 0 : c;
    }

    @Override
    public void moveListing(long mallProductId, long productId) {
        jdbc.update("UPDATE mall_products SET product_id = ? WHERE mall_product_id = ?", productId, mallProductId);
    }

    @Override
    public void moveWishlists(long fromProductId, long toProductId) {
        jdbc.update("UPDATE IGNORE wishlists SET product_id = ? WHERE product_id = ?", toProductId, fromProductId);
    }

    @Override
    public void deleteProduct(long productId) {
        jdbc.update("DELETE FROM products WHERE product_id = ?", productId);
    }

    /** 상품의 묶기 키 (없으면 null) */
    @Override
    public String matchKey(long productId) {
        // match_key 가 NULL 인 행이 있으므로 stream().findFirst() 를 쓰면 NPE (Optional.of(null))
        List<String> keys = jdbc.queryForList("SELECT match_key FROM products WHERE product_id = ?", String.class,
                productId);
        return keys.isEmpty() ? null : keys.getFirst();
    }

    /** 묶기 키가 prefix 로 시작하는 상품들 (id → key), 최근 상품부터 최대 limit 개. 다른 구성 · 이상 가격 비교용 */
    public Map<Long, String> productsByKeyPrefix(String prefix, int limit) {
        Map<Long, String> out = new LinkedHashMap<>();
        String like = prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        jdbc.query("SELECT product_id, match_key FROM products WHERE match_key LIKE ? ORDER BY product_id DESC LIMIT ?",
                (rs, i) -> new Object[] { rs.getLong(1), rs.getString(2) }, like, limit)
                .forEach(r -> out.put((Long) r[0], (String) r[1]));
        return out;
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

    /** 쇼핑몰 원래 상품명 (없으면 외부 상품 ID). products.product_name 의 첫 값으로도 쓴다 */
    public static String listingTitle(MallOffer o) {
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
