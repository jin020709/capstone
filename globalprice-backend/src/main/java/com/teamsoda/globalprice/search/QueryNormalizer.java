package com.teamsoda.globalprice.search;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.teamsoda.globalprice.exception.BusinessException;
import com.teamsoda.globalprice.exception.ErrorCode;

/**
 * 검색어 정규화: 앞뒤 공백 제거 · 연속 공백 1개 · 1~50자 검증 · 한영 별칭 변환(aliases.csv).
 * 사전에 없는 한글 단어는 그대로 보낸다. 붙여 쓴 "아이폰17" 처럼 사전 단어로 시작하면 나눠서 바꾼다.
 */
@Component
public class QueryNormalizer {

    public static final int MAX_LENGTH = 50;

    public record Query(String original, String normalized, String sourceQuery) {
    }

    private final Map<String, String> aliases;

    public QueryNormalizer() {
        this(load());
    }

    QueryNormalizer(Map<String, String> aliases) {
        this.aliases = aliases;
    }

    public Query normalize(String q) {
        String n = q == null ? "" : q.strip().replaceAll("\\s+", " ");
        if (n.isEmpty()) throw new BusinessException(ErrorCode.INVALID_INPUT, "검색어를 입력해 주세요.");
        if (n.length() > MAX_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "검색어는 50자 이내로 입력해 주세요.");
        }
        List<String> out = new ArrayList<>();
        for (String word : n.split(" ")) out.add(translate(word));
        return new Query(q, n, String.join(" ", out));
    }

    String translate(String word) {
        String exact = aliases.get(word.toLowerCase());
        if (exact != null) return exact;
        // 사전 단어 + 나머지 (예: 아이폰17 → iphone 17, 갤럭시s25 → galaxy s25)
        String best = null;
        String lower = word.toLowerCase();
        for (String k : aliases.keySet()) {
            // 나머지가 숫자 · 영문으로 시작할 때만 나눈다 ("프로틴" 을 "pro 틴" 으로 바꾸지 않도록)
            if (lower.startsWith(k) && lower.length() > k.length() && isAsciiAlnum(lower.charAt(k.length()))
                    && (best == null || k.length() > best.length())) {
                best = k;
            }
        }
        if (best != null && best.length() < word.length()) {
            return aliases.get(best) + " " + translate(word.substring(best.length()));
        }
        return word;
    }

    private static boolean isAsciiAlnum(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z');
    }

    private static Map<String, String> load() {
        Map<String, String> map = new HashMap<>();
        ClassPathResource res = new ClassPathResource("aliases.csv");
        if (!res.exists()) return map;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(res.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int comma = line.indexOf(',');
                if (comma <= 0) continue;
                map.put(line.substring(0, comma).strip().toLowerCase(), line.substring(comma + 1).strip());
            }
        } catch (IOException e) {
            throw new IllegalStateException("aliases.csv 를 읽지 못함", e);
        }
        return map;
    }
}
