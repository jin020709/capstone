package com.teamsoda.globalprice.search;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.teamsoda.globalprice.exception.BusinessException;
import com.teamsoda.globalprice.exception.ErrorCode;

/**
 * 검색어 정규화: 앞뒤 공백 제거 · 연속 공백 1개 · 1~50자 검증 · 한영 별칭 변환.
 * 사전은 DB search_aliases ({@link AliasRepository}) 이고 {@link #RELOAD_EVERY} 마다 다시 읽는다
 * (DB 에서 별칭을 고치면 서버를 다시 켜지 않아도 반영). 읽기에 실패하면 마지막으로 읽은 사전을 계속 쓴다.
 * 사전에 없는 한글 단어는 그대로 보낸다. 붙여 쓴 "아이폰17" 처럼 사전 단어로 시작하면 나눠서 바꾼다.
 */
@Component
public class QueryNormalizer {

    private static final Logger log = LoggerFactory.getLogger(QueryNormalizer.class);

    public static final int MAX_LENGTH = 50;
    static final Duration RELOAD_EVERY = Duration.ofMinutes(10);

    public record Query(String original, String normalized, String sourceQuery) {
    }

    private final Supplier<Map<String, String>> loader;
    private final Clock clock;
    private volatile Map<String, String> aliases = Map.of();
    private volatile Instant loadedAt;

    @Autowired
    public QueryNormalizer(AliasRepository repository, Clock clock) {
        this(repository::translations, clock);
    }

    QueryNormalizer(Supplier<Map<String, String>> loader, Clock clock) {
        this.loader = loader;
        this.clock = clock;
    }

    /** 고정 사전 (테스트용) */
    QueryNormalizer(Map<String, String> aliases) {
        this(() -> aliases, Clock.systemUTC());
    }

    /** 지금 쓰는 사전. 처음이거나 {@link #RELOAD_EVERY} 가 지났으면 다시 읽는다 */
    Map<String, String> aliases() {
        Instant now = clock.instant();
        Instant last = loadedAt;
        if (last == null || !now.isBefore(last.plus(RELOAD_EVERY))) {
            loadedAt = now;   // 실패해도 바로 다시 부르지 않도록 시각은 먼저 갱신
            try {
                Map<String, String> fresh = Map.copyOf(loader.get());
                if (last == null || fresh.size() != aliases.size()) log.info("검색어 별칭 {}개를 읽었어요", fresh.size());
                aliases = fresh;
            } catch (RuntimeException e) {
                log.warn("검색어 별칭을 읽지 못해 이전 사전({}개)을 씀. 05 · 08 마이그레이션 적용 여부 확인: {}",
                        aliases.size(), e.getMessage());
            }
        }
        return aliases;
    }

    public Query normalize(String q) {
        String n = q == null ? "" : q.strip().replaceAll("\\s+", " ");
        if (n.isEmpty()) throw new BusinessException(ErrorCode.INVALID_INPUT, "검색어를 입력해 주세요.");
        if (n.length() > MAX_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "검색어는 50자 이내로 입력해 주세요.");
        }
        Map<String, String> aliases = aliases();
        List<String> out = new ArrayList<>();
        for (String word : n.split(" ")) out.add(translate(word, aliases));
        return new Query(q, n, String.join(" ", out));
    }

    static String translate(String word, Map<String, String> aliases) {
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
            return aliases.get(best) + " " + translate(word.substring(best.length()), aliases);
        }
        return word;
    }

    private static boolean isAsciiAlnum(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z');
    }
}
