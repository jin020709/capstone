package com.teamsoda.globalprice.search;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 검색어 한영 변환 사전 (search_aliases, 마이그레이션 002 · 005).
 * <ul>
 *   <li>모델번호 매칭용 행(model_number 있음)과 띄어쓰기가 있는 별칭은 단어 변환에 쓰지 않는다</li>
 *   <li>같은 말이 여러 행이면 사람이 넣은 값(MANUAL)이 자동 번역(AUTO)보다, 먼저 넣은 행이 나중 행보다 우선</li>
 * </ul>
 */
@Repository
public class AliasRepository {

    private final JdbcTemplate jdbc;

    public AliasRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 소문자 별칭 → 쇼핑몰 검색어. alias_source 컬럼(08_migration_005)이 없으면 예외 */
    public Map<String, String> translations() {
        Map<String, String> out = new LinkedHashMap<>();
        jdbc.query("""
                SELECT alias_text, search_term FROM search_aliases
                WHERE is_active = TRUE AND model_number IS NULL
                ORDER BY alias_source = 'AUTO', alias_id""", rs -> {
            String alias = rs.getString(1).strip().toLowerCase(Locale.ROOT);
            if (!alias.isEmpty() && !alias.contains(" ")) out.putIfAbsent(alias, rs.getString(2).strip());
        });
        return out;
    }

    /**
     * 자동 번역 결과를 AUTO 행으로 저장. 같은 alias_text 의 변환 행(브랜드 · 모델번호 없음)이 이미 있으면
     * UNIQUE(uq_search_alias_target) 때문에 넣지 않는다 → 사람이 넣은 MANUAL 행을 덮지 않음
     */
    public void insertAuto(String alias, String searchTerm) {
        jdbc.update("INSERT IGNORE INTO search_aliases (alias_text, search_term, alias_source) VALUES (?, ?, 'AUTO')",
                alias.strip().toLowerCase(Locale.ROOT), searchTerm.strip());
    }
}
