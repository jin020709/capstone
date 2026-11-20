package com.teamsoda.globalprice.rate;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.teamsoda.globalprice.pricing.PricingModels.FxRate;

@Repository
public class ExchangeRateRepository {

    private static final RowMapper<FxRate> MAPPER = (rs, i) -> new FxRate(
            rs.getLong("exchange_rate_id"), rs.getString("currency_code"), rs.getBigDecimal("rate_to_krw"),
            rs.getBigDecimal("tts_rate"), rs.getObject("base_date", LocalDate.class),
            rs.getObject("recorded_at", LocalDateTime.class));

    private final JdbcTemplate jdbc;

    public ExchangeRateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** (currency_code, base_date) 기준 upsert */
    public int upsert(List<KoreaEximClient.RawRate> rates, LocalDate baseDate, LocalDateTime recordedAt) {
        int n = 0;
        for (KoreaEximClient.RawRate r : rates) {
            n += jdbc.update("""
                    INSERT INTO exchange_rates (currency_code, rate_to_krw, tts_rate, base_date, recorded_at)
                    VALUES (?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE rate_to_krw = VALUES(rate_to_krw), tts_rate = VALUES(tts_rate),
                                            recorded_at = VALUES(recorded_at)""",
                    r.currencyCode(), r.rateToKrw(), r.ttsRate(), baseDate, Timestamp.valueOf(recordedAt)) > 0 ? 1 : 0;
        }
        return n;
    }

    /** 통화별 최신 base_date 1건 */
    public List<FxRate> latestPerCurrency() {
        return jdbc.query("""
                SELECT e.exchange_rate_id, e.currency_code, e.rate_to_krw, e.tts_rate, e.base_date, e.recorded_at
                FROM exchange_rates e
                JOIN (SELECT currency_code, MAX(base_date) AS bd FROM exchange_rates GROUP BY currency_code) m
                  ON m.currency_code = e.currency_code AND m.bd = e.base_date
                ORDER BY e.currency_code""", MAPPER);
    }

    public boolean existsForDate(LocalDate baseDate) {
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM exchange_rates WHERE base_date = ?", Integer.class,
                baseDate);
        return c != null && c > 0;
    }
}
