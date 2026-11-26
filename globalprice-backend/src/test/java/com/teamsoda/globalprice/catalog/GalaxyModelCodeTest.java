package com.teamsoda.globalprice.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 갤럭시 모델번호 (DB model_codes, 09_migration_006). DB 없이 마이그레이션 파일의 VALUES 를 읽어 쓴다 */
class GalaxyModelCodeTest {

    private static final Path FILE = Path.of("..", "GlobalPrice_DB", "GlobalPrice_DB", "09_migration_006.sql");
    private static final Pattern ROW = Pattern.compile("\\(\\s*'([^']+)'\\s*,\\s*'([^']*)'\\s*,\\s*'([^']+)'\\s*\\)");

    /** 번호 → (model_key, model_name) */
    static Map<String, String[]> seedRows() throws IOException {
        String sql = Files.readString(FILE, StandardCharsets.UTF_8);
        String values = sql.substring(sql.indexOf("VALUES", sql.indexOf("INSERT IGNORE INTO model_codes")));
        values = values.substring(0, values.indexOf(';')).replaceAll("--[^\n]*", "");
        Map<String, String[]> out = new LinkedHashMap<>();
        Matcher m = ROW.matcher(values);
        while (m.find()) out.put(m.group(1), new String[] { m.group(2), m.group(3) });
        return out;
    }

    @BeforeEach
    void useSeed() throws IOException {
        Map<String, String[]> codes = new LinkedHashMap<>();
        seedRows().forEach((code, row) -> codes.put(code, ModelCodes.parseGalaxy(row[0])));
        ProductMatcher.useGalaxyCodes(codes);
    }

    @AfterEach
    void reset() {
        ProductMatcher.useGalaxyCodes(null);   // 다른 테스트는 내장 목록
    }

    private static String key(String title) {
        ProductMatcher.Match m = ProductMatcher.match(title);
        return m == null ? null : m.key();
    }

    @Test
    void everySeedRowIsValid_andAgreesWithModelName() throws IOException {
        Map<String, String[]> rows = seedRows();
        assertThat(rows).hasSizeGreaterThan(30);
        rows.forEach((code, row) -> {
            assertThat(ModelCodes.parseGalaxy(row[0])).as(code).isNotNull();
            // 번호만 쓴 상품명과 모델명만 쓴 상품명이 같은 상품이어야 함 (번호 · 키를 잘못 넣으면 여기서 걸림)
            String byCode = key("Samsung SM-" + code.toUpperCase() + "B 256GB");
            String byName = key("Samsung " + row[1].replace("(LTE)", "") + " 256GB");
            assertThat(byCode).as(code + " / " + row[1]).isEqualTo(row[0] + "|256").isEqualTo(byName);
        });
    }

    @Test
    void manyCodesForOneModel() {   // 나라별 지역 글자 · LTE / 5G 번호
        String key = "galaxy|z fold|5||512";
        assertThat(key("Samsung Galaxy Z Fold5 512GB Icy Blue")).isEqualTo(key);
        assertThat(key("Samsung SM-F946B/DS 512GB Phantom Black")).isEqualTo(key);
        assertThat(key("SAMSUNG SM-F946U1 512GB Unlocked")).isEqualTo(key);
        assertThat(key("Samsung Galaxy A15 128GB SM-A155F")).isEqualTo(key("Samsung Galaxy A15 5G 128GB SM-A156B"));
    }

    @Test
    void codeWithoutSmPrefix_onlyWithSamsungOrGalaxyInTitle() {
        assertThat(key("Samsung Galaxy S25 Ultra 5G (S938B) 512GB GSM Unlocked International"))
                .isEqualTo("galaxy|s|25|ultra|512");
        assertThat(key("Galaxy 512GB F946B Phantom Black")).isEqualTo("galaxy|z fold|5||512");
        // samsung · galaxy 가 없으면 번호처럼 보여도 모델번호로 보지 않음
        assertThat(key("Logitech S938 Speaker 512GB")).doesNotStartWith("galaxy|");
    }

    @Test
    void nameAndCodeDisagree_orTwoModels_noModelKey() {
        // 이름은 S25, 번호는 S25 Ultra → 갤럭시 규칙으로 묶지 않음
        assertThat(key("Samsung Galaxy S25 256GB (S938B)")).doesNotStartWith("galaxy|");
        // 번호 두 개가 서로 다른 모델 (호환 목록 같은 상품명)
        assertThat(key("Samsung 256GB SM-S938B SM-S928B")).doesNotStartWith("galaxy|");
    }

    @Test
    void storageStillRequired() {   // 용량이 없으면 지금처럼 모델 키 없음
        assertThat(key("Genuine OEM Samsung Galaxy Z Fold 5 Outer OLED Display (F946) Service Pack"))
                .doesNotStartWith("galaxy|");
    }

    @Test
    void emptyOrMissingTable_fallsBackToBuiltIn() {
        ProductMatcher.useGalaxyCodes(Map.of());
        assertThat(key("Samsung SM-S938B 256GB")).isEqualTo("galaxy|s|25|ultra|256");
        assertThat(key("Samsung SM-F946B 512GB")).doesNotStartWith("galaxy|");   // 내장 목록에는 Fold5 없음
    }

    @Test
    void badModelKeyRowsAreRejected() {
        assertThat(ModelCodes.parseGalaxy("galaxy|z fold|5|")).containsExactly("z fold", "5", "");
        assertThat(ModelCodes.parseGalaxy("galaxy|s|25|ultra|256")).isNull();
        assertThat(ModelCodes.parseGalaxy("galaxy|s|xx|")).isNull();
        assertThat(ModelCodes.parseGalaxy("galaxy|s|25|pro")).isNull();
    }
}
