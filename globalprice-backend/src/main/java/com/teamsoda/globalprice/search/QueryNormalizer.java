package com.teamsoda.globalprice.search;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.teamsoda.globalprice.exception.BusinessException;
import com.teamsoda.globalprice.exception.ErrorCode;

/**
 * 검색어 정규화: 앞뒤 공백 제거 · 연속 공백 1개 · 1~50자 검증 · 한영 별칭 변환.
 * 사전은 DB search_aliases ({@link AliasRepository}) 이고 {@link #RELOAD_EVERY} 마다 다시 읽는다
 * (DB 에서 별칭을 고치면 서버를 다시 켜지 않아도 반영). 읽기에 실패하면 마지막으로 읽은 사전을 계속 쓴다.
 * 붙여 쓴 "아이폰17" 처럼 사전 단어로 시작하면 나눠서 바꾼다.
 * 사전에 없는 한글 단어는 번역기({@link QueryTranslator}, DeepL)로 바꿔 search_aliases 에 AUTO 로 저장한다
 * (다음부터는 사전처럼 씀. 같은 말에 MANUAL 이 있으면 MANUAL). 번역은 {@code timeout} 까지만 기다리고,
 * 늦거나 실패하면 그 단어는 한글 그대로 보낸다 (늦은 번역은 뒤에서 끝나면 저장). 실패한 단어는 1시간 동안 다시 묻지 않는다.
 */
@Component
public class QueryNormalizer {

    private static final Logger log = LoggerFactory.getLogger(QueryNormalizer.class);

    public static final int MAX_LENGTH = 50;
    static final Duration RELOAD_EVERY = Duration.ofMinutes(10);
    static final Duration RETRY_FAILED_AFTER = Duration.ofHours(1);
    private static final Pattern HANGUL = Pattern.compile("[\uAC00-\uD7A3\u3131-\u318E]");
    private static final ExecutorService TRANSLATE = Executors.newVirtualThreadPerTaskExecutor();

    public record Query(String original, String normalized, String sourceQuery) {
    }

    private final Supplier<Map<String, String>> loader;
    private final Clock clock;
    private final QueryTranslator translator;
    private final BiConsumer<String, String> saveAuto;
    private final Duration timeout;
    private final Map<String, Instant> failedUntil = new ConcurrentHashMap<>();
    private volatile Map<String, String> aliases = Map.of();
    private volatile Instant loadedAt;

    @Autowired
    public QueryNormalizer(AliasRepository repository, Clock clock, QueryTranslator translator,
                           @Value("${globalprice.translate.timeout:1500ms}") Duration timeout) {
        this(repository::translations, clock, translator, repository::insertAuto, timeout);
    }

    QueryNormalizer(Supplier<Map<String, String>> loader, Clock clock) {
        this(loader, clock, QueryTranslator.NONE, (a, t) -> { }, Duration.ZERO);
    }

    QueryNormalizer(Supplier<Map<String, String>> loader, Clock clock, QueryTranslator translator,
                    BiConsumer<String, String> saveAuto, Duration timeout) {
        this.loader = loader;
        this.clock = clock;
        this.translator = translator;
        this.saveAuto = saveAuto;
        this.timeout = timeout;
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
        return new Query(q, n, autoTranslate(String.join(" ", out)));
    }

    /** 사전으로 바꾼 뒤에도 한글이 남은 단어를 번역기로 바꾼다. 번역기가 없거나 늦으면 그대로 */
    String autoTranslate(String query) {
        if (!translator.enabled() || !HANGUL.matcher(query).find()) return query;
        Instant now = clock.instant();
        List<String> unknown = new ArrayList<>();
        for (String w : query.split(" ")) {
            String k = w.toLowerCase();
            Instant until = failedUntil.get(k);
            if (until != null && now.isAfter(until)) failedUntil.remove(k, until);
            else if (until != null) continue;
            if (HANGUL.matcher(w).find() && !unknown.contains(k)) unknown.add(k);
        }
        if (unknown.isEmpty()) return query;
        Future<Map<String, String>> f = TRANSLATE.submit(() -> learn(unknown));
        Map<String, String> got;
        try {
            got = f.get(Math.max(timeout.toMillis(), 1), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.info("검색어 번역이 {}ms 안에 안 와서 원래 단어로 검색 (끝나면 저장): {}", timeout.toMillis(), unknown);
            return query;
        } catch (ExecutionException e) {
            return query;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return query;
        }
        List<String> out = new ArrayList<>();
        for (String w : query.split(" ")) out.add(got.getOrDefault(w.toLowerCase(), w));
        return String.join(" ", out);
    }

    /** 번역기 호출 → 쓸 만한 결과만 사전에 넣고 DB 에 AUTO 로 저장. 못 바꾼 단어는 1시간 동안 다시 묻지 않음 */
    private Map<String, String> learn(List<String> words) {
        List<String> result;
        try {
            result = translator.translate(words);
        } catch (RuntimeException e) {
            log.warn("검색어 번역 실패, 원래 단어로 검색: {}", e.getMessage());
            Instant until = clock.instant().plus(RETRY_FAILED_AFTER);
            words.forEach(w -> failedUntil.put(w, until));
            return Map.of();
        }
        Map<String, String> good = new LinkedHashMap<>();
        for (int i = 0; i < words.size(); i++) {
            String word = words.get(i);
            String term = clean(i < result.size() ? result.get(i) : null, word);
            if (term == null) {
                failedUntil.put(word, clock.instant().plus(RETRY_FAILED_AFTER));
                continue;
            }
            good.put(word, term);
        }
        if (good.isEmpty()) return good;
        synchronized (this) {   // 메모리 사전에 먼저 (다음 검색부터 바로), DB 는 그다음
            Map<String, String> next = new HashMap<>(aliases);
            good.forEach(next::putIfAbsent);
            aliases = Map.copyOf(next);
        }
        good.forEach((word, term) -> {
            try {
                saveAuto.accept(word, term);
            } catch (RuntimeException e) {
                log.warn("자동 번역 별칭 저장 실패 ({} → {}): {}", word, term, e.getMessage());
            }
        });
        log.info("검색어 자동 번역 저장: {}", good);
        return good;
    }

    /** 번역 결과 다듬기. 빈 값 · 한글이 남음 · 원문과 같음 · 너무 김 → null (저장 안 함) */
    static String clean(String term, String word) {
        if (term == null) return null;
        String s = term.strip().replaceAll("^[\\s\"'“”‘’.,!?。]+|[\\s\"'“”‘’.,!?。]+$", "")
                .replaceAll("\\s+", " ").toLowerCase();
        if (s.isEmpty() || s.length() > 255 || HANGUL.matcher(s).find() || s.equals(word.toLowerCase())) return null;
        return s;
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
