package com.teamsoda.globalprice.catalog;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 제조사 모델번호 목록 (DB model_codes, 09_migration_006) 을 읽어 {@link ProductMatcher} 에 넣는다.
 * 서버 시작 직후와 10분마다 다시 읽는다 (DB 에서 번호를 더하면 서버를 다시 켜지 않아도 반영).
 * 읽기에 실패하면 마지막으로 읽은 목록을 계속 쓴다. 지금은 갤럭시(model_key 가 galaxy| 로 시작)만 쓴다.
 */
@Component
public class ModelCodes {

    private static final Logger log = LoggerFactory.getLogger(ModelCodes.class);

    private final JdbcTemplate jdbc;
    private int lastCount = -1;

    public ModelCodes(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelayString = "PT10M")
    public void reload() {
        try {
            Map<String, String[]> galaxy = galaxyCodes();
            ProductMatcher.useGalaxyCodes(galaxy);
            if (galaxy.size() != lastCount) log.info("갤럭시 모델번호 {}개를 읽었어요", galaxy.size());
            lastCount = galaxy.size();
        } catch (RuntimeException e) {
            log.warn("모델번호를 읽지 못해 이전 목록을 씀. 09 마이그레이션 적용 여부 확인: {}", e.getMessage());
        }
    }

    /** 번호(소문자) → (시리즈, 번호, 접미사). model_key 형식이 틀린 행은 건너뛴다 */
    Map<String, String[]> galaxyCodes() {
        Map<String, String[]> out = new LinkedHashMap<>();
        jdbc.query("""
                SELECT model_code, model_key FROM model_codes
                WHERE is_active = TRUE AND model_key LIKE 'galaxy|%'
                ORDER BY model_code_id""", rs -> {
            String[] model = parseGalaxy(rs.getString(2));
            if (model == null) {
                log.warn("model_codes 행 무시 (model_key 형식: galaxy|시리즈|번호|접미사): {} {}", rs.getString(1),
                        rs.getString(2));
                return;
            }
            out.putIfAbsent(rs.getString(1).strip().toLowerCase(Locale.ROOT), model);
        });
        return out;
    }

    /** galaxy|z fold|5| → {z fold, 5, ""} */
    static String[] parseGalaxy(String key) {
        String[] p = key.strip().split("\\|", -1);
        if (p.length != 4 || !p[0].equals("galaxy") || p[1].isBlank() || !p[2].matches("\\d{1,2}")) return null;
        if (!p[3].matches("|\\+|ultra|fe|edge")) return null;
        return new String[] { p[1], p[2], p[3] };
    }
}
