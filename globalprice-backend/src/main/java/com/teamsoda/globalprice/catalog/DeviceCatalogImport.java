package com.teamsoda.globalprice.catalog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Google Play 지원 기기 목록(공개 CSV: 제조사, 모델 이름, 기기 코드, 모델번호)을 받아
 * device_models · model_codes 의 GPLAY 행을 새로 채운다 (10_migration_007).
 * 서버 시작 직후와 6시간마다 확인해, 마지막으로 받은 지 {@code refresh-after}(기본 7일)가 지났으면 다시 받는다.
 * 실패하면 DB 에 있던 목록을 그대로 쓴다. 사람이 넣은 MANUAL 행은 건드리지 않고, 같은 번호 · 키가 MANUAL 로 있으면 넣지 않는다.
 * <ul>
 *   <li>삼성: 모델 이름을 갤럭시 규칙으로 읽을 수 있으면 SM- 번호 앞 4자리(s938)를 galaxy|… 키로 (같은 4자리가 다른 모델이면 버림)</li>
 *   <li>구글 Pixel: 픽셀 규칙이 있으므로 넣지 않음</li>
 *   <li>그 밖: 모델 이름 → dev|제조사|이름(5G · 4G 표기 포함), 모델번호(영숫자 5자 이상) → 같은 키. 한 번호가 여러 모델이면 버림</li>
 * </ul>
 */
@Component
public class DeviceCatalogImport {

    private static final Logger log = LoggerFactory.getLogger(DeviceCatalogImport.class);
    static final String SOURCE = "GPLAY";
    private static final Pattern SM = Pattern.compile("^SM-([A-Z]\\d{3})", Pattern.CASE_INSENSITIVE);

    /** 읽은 결과 (DB 에 넣을 행들) */
    record Parsed(Map<String, DeviceCatalog.Model> models, Map<String, String> codes,
                  Map<String, String[]> galaxyCodes, Map<String, String> galaxyNames, int rows) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ModelCodes modelCodes;
    private final Clock clock;
    private final boolean enabled;
    private final String url;
    private final Duration refreshAfter;

    public DeviceCatalogImport(JdbcTemplate jdbc, PlatformTransactionManager txManager, ModelCodes modelCodes, Clock clock,
                               @Value("${globalprice.device-catalog.enabled:true}") boolean enabled,
                               @Value("${globalprice.device-catalog.url:https://storage.googleapis.com/play_public/supported_devices.csv}") String url,
                               @Value("${globalprice.device-catalog.refresh-after:7d}") Duration refreshAfter) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.modelCodes = modelCodes;
        this.clock = clock;
        this.enabled = enabled;
        this.url = url;
        this.refreshAfter = refreshAfter;
    }

    @Scheduled(initialDelayString = "PT15S", fixedDelayString = "PT6H")
    public void refreshIfStale() {
        if (!enabled) return;
        try {
            LocalDateTime last = jdbc.queryForObject(
                    "SELECT MAX(created_at) FROM device_models WHERE source = ?", LocalDateTime.class, SOURCE);
            if (last != null && last.isAfter(LocalDateTime.now(clock).minus(refreshAfter))) return;
            importNow();
        } catch (RuntimeException e) {
            log.warn("기기 모델 목록 갱신 실패, 기존 목록을 씀. 10 마이그레이션 적용 여부 확인: {}", e.getMessage());
        }
    }

    /** 내려받아 바로 넣는다 */
    public void importNow() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpResponse<InputStream> res;
        try {
            res = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new IllegalStateException("기기 목록을 받지 못함: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("기기 목록 받기 중단", e);
        }
        if (res.statusCode() != 200) throw new IllegalStateException("기기 목록 HTTP " + res.statusCode());
        Parsed p;
        try (InputStream in = res.body()) {
            p = parse(in);
        } catch (IOException e) {
            throw new IllegalStateException("기기 목록을 읽지 못함: " + e.getMessage(), e);
        }
        if (p.models().isEmpty()) throw new IllegalStateException("기기 목록이 비어 있음 (형식이 바뀌었을 수 있음)");
        save(p);
        modelCodes.reload();
    }

    /** CSV (UTF-16, 첫 줄 머리글: Retail Branding, Marketing Name, Device, Model) */
    static Parsed parse(InputStream in) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_16));
        Map<String, DeviceCatalog.Model> models = new LinkedHashMap<>();
        Map<String, Set<String>> codeKeys = new HashMap<>();
        Map<String, Set<String>> galaxyBase = new HashMap<>();
        Map<String, String> galaxyName = new HashMap<>();
        String line = r.readLine();   // 머리글
        int rows = 0;
        while ((line = r.readLine()) != null) {
            List<String> f = csv(line);
            if (f.size() < 4) continue;
            rows++;
            String brand = cleanBrand(f.get(0));
            String name = f.get(1).strip();
            String model = f.get(3).strip();
            if (brand.isEmpty() || name.isEmpty()) continue;
            String brandLower = brand.toLowerCase(Locale.ROOT);
            if (brandLower.equals("samsung")) {
                String family = galaxyFamily(name);
                if (family != null) {
                    Matcher m = SM.matcher(model);
                    if (m.find()) {
                        String base = m.group(1).toLowerCase(Locale.ROOT);
                        galaxyBase.computeIfAbsent(base, k -> new LinkedHashSet<>()).add(family);
                        galaxyName.putIfAbsent(base, name);
                    }
                    continue;   // 갤럭시 규칙으로 묶음
                }
            }
            if (brandLower.equals("google") && name.toLowerCase(Locale.ROOT).startsWith("pixel")) continue;
            String normalized = DeviceCatalog.normalizeName(name);
            if (!DeviceCatalog.eligibleName(normalized)) continue;
            String key = "dev|" + DeviceCatalog.brandSlug(brand) + "|" + DeviceCatalog.keyName(name);
            if (key.length() > ProductMatcher.KEY_MAX - 8) continue;
            models.putIfAbsent(key, new DeviceCatalog.Model(key, brand, name));
            String code = model.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            if (DeviceCatalog.eligibleCode(code) && !code.equals(key.substring(key.lastIndexOf('|') + 1).replace(" ", ""))
                    && !code.equals(normalized.replace(" ", "")) && code.length() <= 50) {
                codeKeys.computeIfAbsent(code, k -> new LinkedHashSet<>()).add(key);
            }
        }
        Map<String, String> codes = new LinkedHashMap<>();
        codeKeys.forEach((code, keys) -> {
            if (keys.size() == 1) codes.put(code, keys.iterator().next());
        });
        Map<String, String[]> galaxyCodes = new LinkedHashMap<>();
        galaxyBase.forEach((base, families) -> {
            if (families.size() == 1) galaxyCodes.put(base, families.iterator().next().split("\\|", -1));
        });
        return new Parsed(models, codes, galaxyCodes, galaxyName, rows);
    }

    /** 갤럭시 규칙으로 읽은 모델 키에서 용량을 뺀 값 (galaxy|s|25|ultra). 못 읽으면 null */
    static String galaxyFamily(String name) {
        ProductMatcher.Match m = ProductMatcher.galaxy(ProductMatcher.normalize("samsung " + name + " 256gb"));
        if (m == null) return null;
        ProductMatcher.Config c = ProductMatcher.config(m.key());
        return c == null ? null : c.family();
    }

    /** "TCT (Alcatel)" → Alcatel, "LGE" → LG */
    static String cleanBrand(String raw) {
        String b = raw.strip();
        Matcher m = Pattern.compile("\\(([^)]+)\\)").matcher(b);
        if (m.find()) b = m.group(1).strip();
        if (b.equalsIgnoreCase("lge")) b = "LG";
        return b;
    }

    /** 따옴표 · 쉼표를 지원하는 CSV 한 줄 */
    static List<String> csv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    /** GPLAY 행만 지우고 다시 넣음 (한 트랜잭션). MANUAL 과 겹치는 번호 · 키는 INSERT IGNORE 로 건너뜀 */
    void save(Parsed p) {
        LocalDateTime now = LocalDateTime.now(clock);
        tx.executeWithoutResult(s -> {
            jdbc.update("DELETE FROM model_codes WHERE source = ?", SOURCE);
            jdbc.update("DELETE FROM device_models WHERE source = ?", SOURCE);
            List<Object[]> models = new ArrayList<>();
            p.models().values().forEach(m -> models.add(new Object[] { m.key(), cut(m.brand(), 100), cut(m.name(), 200), now }));
            batch("INSERT IGNORE INTO device_models (model_key, brand, model_name, source, created_at) VALUES (?, ?, ?, 'GPLAY', ?)",
                    models);
            List<Object[]> codes = new ArrayList<>();
            p.codes().forEach((code, key) -> {
                DeviceCatalog.Model m = p.models().get(key);
                codes.add(new Object[] { code, key, cut(m.name(), 200), cut(m.brand(), 100), now });
            });
            p.galaxyCodes().forEach((base, f) -> codes.add(new Object[] { base, String.join("|", f),
                    cut(p.galaxyNames().get(base), 200), "Samsung", now }));
            batch("INSERT IGNORE INTO model_codes (model_code, model_key, model_name, brand, source, created_at) "
                    + "VALUES (?, ?, ?, ?, 'GPLAY', ?)", codes);
        });
        log.info("기기 모델 목록을 새로 받았어요: CSV {}행 → 모델 {}개, 모델번호 {}개, 갤럭시 번호 {}개", p.rows(),
                p.models().size(), p.codes().size(), p.galaxyCodes().size());
    }

    private void batch(String sql, List<Object[]> rows) {
        for (int i = 0; i < rows.size(); i += 1000) jdbc.batchUpdate(sql, rows.subList(i, Math.min(rows.size(), i + 1000)));
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
