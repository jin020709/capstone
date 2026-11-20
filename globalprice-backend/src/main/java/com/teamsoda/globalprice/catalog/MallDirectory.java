package com.teamsoda.globalprice.catalog;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.teamsoda.globalprice.source.MallSource;

/**
 * 쇼핑몰 · 소스 연결 정보 (shopping_malls + api_sources). 표가 작아서 매번 조회한다.
 */
@Component
public class MallDirectory {

    /** sourceName 은 이 쇼핑몰에 연결된 API 소스 (없으면 null: 시드 · 국내 직구관) */
    public record Mall(long mallId, String mallName, String countryCode, boolean active, String sourceName) {
    }

    private final JdbcTemplate jdbc;
    private final List<MallSource> sources;

    public MallDirectory(JdbcTemplate jdbc, List<MallSource> sources) {
        this.jdbc = jdbc;
        this.sources = sources;
    }

    public Map<Long, Mall> all() {
        Map<Long, Mall> map = new HashMap<>();
        jdbc.query("""
                SELECT m.mall_id, m.mall_name, m.country_code, m.is_active,
                       (SELECT s.source_name FROM api_sources s
                        WHERE s.mall_id = m.mall_id AND s.is_active AND s.source_name <> 'SEED'
                        ORDER BY s.api_source_id LIMIT 1) AS source_name
                FROM shopping_malls m""",
                rs -> {
                    map.put(rs.getLong("mall_id"), new Mall(rs.getLong("mall_id"), rs.getString("mall_name"),
                            rs.getString("country_code"), rs.getBoolean("is_active"), rs.getString("source_name")));
                });
        return map;
    }

    /** 소스에 연결된 활성 쇼핑몰 */
    public Optional<Mall> mallFor(String sourceName) {
        return all().values().stream()
                .filter(m -> sourceName.equals(m.sourceName()) && m.active())
                .findFirst();
    }

    public Optional<MallSource> sourceFor(Mall mall) {
        if (mall == null || mall.sourceName() == null) return Optional.empty();
        return sources.stream().filter(s -> s.sourceName().equals(mall.sourceName())).findFirst();
    }

    public List<MallSource> sources() {
        return sources;
    }

    /** 프론트 storeId: 소스가 있으면 소스 키(ebay 등), 없으면 mall_id 문자열 */
    public String storeKey(Mall mall) {
        return sourceFor(mall).map(MallSource::storeKey).orElse(String.valueOf(mall.mallId()));
    }
}
