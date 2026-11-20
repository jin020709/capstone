package com.teamsoda.globalprice.pricing;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.teamsoda.globalprice.config.GlobalPriceProperties;
import com.teamsoda.globalprice.pricing.PricingModels.DiscountType;
import com.teamsoda.globalprice.pricing.PricingModels.EventType;
import com.teamsoda.globalprice.pricing.PricingModels.FeeRule;
import com.teamsoda.globalprice.pricing.PricingModels.PricingRules;
import com.teamsoda.globalprice.pricing.PricingModels.ShippingRule;
import com.teamsoda.globalprice.pricing.PricingModels.StoreEvent;
import com.teamsoda.globalprice.pricing.PricingModels.TaxRule;

/** 계산 규칙 조회 (shipping_rules · tax_rules · fee_rules · store_events). 표가 작아 요청마다 읽는다 */
@Repository
public class RuleRepository {

    static final RowMapper<StoreEvent> EVENT_MAPPER = (rs, i) -> new StoreEvent(
            rs.getLong("store_event_id"), rs.getLong("mall_id"), rs.getString("event_name"),
            EventType.valueOf(rs.getString("event_type")), rs.getString("description"),
            rs.getString("discount_type") == null ? null : DiscountType.valueOf(rs.getString("discount_type")),
            rs.getBigDecimal("discount_value"), rs.getBigDecimal("min_purchase_amount"),
            rs.getBigDecimal("max_discount_amount"), rs.getObject("start_at", LocalDateTime.class),
            rs.getObject("end_at", LocalDateTime.class), rs.getBoolean("is_active"), rs.getString("event_url"));

    private final JdbcTemplate jdbc;
    private final GlobalPriceProperties props;

    public RuleRepository(JdbcTemplate jdbc, GlobalPriceProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    public PricingRules activeRules() {
        List<ShippingRule> shipping = jdbc.query("""
                SELECT shipping_rule_id, mall_id, base_fee, free_shipping_threshold, currency_code
                FROM shipping_rules WHERE is_active""",
                (rs, i) -> new ShippingRule(rs.getLong(1), rs.getLong(2), rs.getBigDecimal(3), rs.getBigDecimal(4),
                        rs.getString(5)));
        List<TaxRule> tax = jdbc.query("""
                SELECT tax_rule_id, category_id, origin_country, duty_rate, vat_rate, exemption_amount_usd
                FROM tax_rules WHERE is_active""",
                (rs, i) -> new TaxRule(rs.getLong(1), rs.getObject(2, Long.class), rs.getString(3),
                        rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6)));
        List<FeeRule> fee = jdbc.query("""
                SELECT fee_rule_id, fee_name, fee_rate, fixed_fee_krw FROM fee_rules WHERE is_active""",
                (rs, i) -> new FeeRule(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3), rs.getBigDecimal(4)));
        List<StoreEvent> events = jdbc.query("SELECT * FROM store_events WHERE is_active", EVENT_MAPPER);
        return new PricingRules(shipping, tax, fee, events, props.pricing().feeRuleName());
    }

    /** [from, to] 와 기간이 겹치는 활성 행사 (시작 순) */
    public List<StoreEvent> activeEventsOverlapping(LocalDateTime from, LocalDateTime to) {
        return jdbc.query("""
                SELECT * FROM store_events WHERE is_active AND start_at <= ? AND end_at >= ?
                ORDER BY start_at, store_event_id""", EVENT_MAPPER, to, from);
    }

    public java.util.Optional<StoreEvent> event(long id) {
        return jdbc.query("SELECT * FROM store_events WHERE store_event_id = ?", EVENT_MAPPER, id).stream().findFirst();
    }

    public List<String> categoryNames() {
        return jdbc.queryForList("SELECT category_name FROM categories WHERE parent_category_id IS NULL "
                + "ORDER BY category_id", String.class);
    }
}
