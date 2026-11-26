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
 * 읽기에 실패하면 마지막으로 읽은 목록을 계속 쓴다.
 * <ul>
 *   <li>갤럭시 번호 (model_key 가 galaxy| 로 시작) → 갤럭시 규칙</li>
 *   <li>그 밖의 휴대폰 · 태블릿 (device_models + dev| 번호, 10_migration_007) → {@link DeviceCatalog}</li>
 * </ul>
 */
@Component
public class ModelCodes {

    private static final Logger log = LoggerFactory.getLogger(ModelCodes.class);

    private final JdbcTemplate jdbc;
    private int lastCount = -1;
    private int lastDevices = -1;

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
        try {
            DeviceCatalog devices = deviceCatalog();
            ProductMatcher.useDeviceCatalog(devices);
            if (devices.size() != lastDevices) log.info("휴대폰 · 태블릿 모델 {}개를 읽었어요", devices.size());
            lastDevices = devices.size();
        } catch (RuntimeException e) {
            log.warn("기기 모델 목록을 읽지 못해 이전 목록을 씀. 10 마이그레이션 적용 여부 확인: {}", e.getMessage());
        }
    }

    /** device_models + dev| 모델번호. 같은 key 는 MANUAL 이 GPLAY 보다 우선 */
    DeviceCatalog deviceCatalog() {
        java.util.List<DeviceCatalog.Model> models = new java.util.ArrayList<>();
        jdbc.query("""
                SELECT model_key, brand, model_name FROM device_models
                WHERE is_active = TRUE ORDER BY source = 'GPLAY', device_model_id""",
                rs -> { models.add(new DeviceCatalog.Model(rs.getString(1), rs.getString(2), rs.getString(3))); });
        Map<String, String> codes = new LinkedHashMap<>();
        jdbc.query("""
                SELECT model_code, model_key FROM model_codes
                WHERE is_active = TRUE AND model_key LIKE 'dev|%' ORDER BY source = 'GPLAY', model_code_id""",
                rs -> { codes.putIfAbsent(rs.getString(1).strip().toLowerCase(Locale.ROOT), rs.getString(2)); });
        return DeviceCatalog.of(models, codes);
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
