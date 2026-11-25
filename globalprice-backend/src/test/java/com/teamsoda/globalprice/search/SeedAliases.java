package com.teamsoda.globalprice.search;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 단위 테스트용: DB 없이 실제 별칭 데이터(05_migration_002 · 08_migration_005 의 임시 테이블 VALUES)를 읽는다.
 * DB 와 같은 규칙: 모델번호 행 · 띄어쓰기 있는 별칭은 빼고, 같은 말은 먼저 나온 값 (002 → 005 순서).
 */
final class SeedAliases {

    private static final Path DB = Path.of("..", "GlobalPrice_DB", "GlobalPrice_DB");
    /** ('별칭', '검색어', 브랜드|NULL[, 모델번호|NULL]) */
    private static final Pattern ROW = Pattern.compile(
            "\\(\\s*'([^']+)'\\s*,\\s*'([^']+)'\\s*,\\s*(?:NULL|'[^']*')\\s*(?:,\\s*(NULL|'[^']*')\\s*)?\\)");

    private SeedAliases() {
    }

    static Map<String, String> load() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String file : new String[] { "05_migration_002.sql", "08_migration_005.sql" }) {
            String sql;
            try {
                sql = Files.readString(DB.resolve(file), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException(file + " 를 읽지 못함", e);
            }
            // 임시 테이블에 넣는 VALUES 부분만
            String values = sql.substring(sql.indexOf("VALUES", sql.indexOf("INSERT INTO tmp_alias_seed")));
            values = values.substring(0, values.indexOf(';'));
            Matcher m = ROW.matcher(values.replaceAll("--[^\n]*", ""));
            while (m.find()) {
                if (m.group(3) != null && !m.group(3).equals("NULL")) continue;   // 모델번호 행
                String alias = m.group(1).strip().toLowerCase(Locale.ROOT);
                if (!alias.contains(" ")) out.putIfAbsent(alias, m.group(2).strip());
            }
        }
        return out;
    }
}
