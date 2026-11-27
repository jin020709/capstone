package com.teamsoda.globalprice.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.teamsoda.globalprice.source.SourceException;
import com.teamsoda.globalprice.support.TestProps;

/** 사전에 없는 한글 단어 자동 번역 (DeepL → search_aliases AUTO) */
class QueryTranslateTest {

    /** 부른 단어를 기록하는 가짜 번역기 */
    static final class FakeTranslator implements QueryTranslator {
        final List<List<String>> calls = new ArrayList<>();
        volatile Function<String, String> fn = w -> Map.of("가습기", "Humidifier.", "필터", "filter").get(w);
        volatile long delayMs;
        volatile boolean fail;

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public synchronized List<String> translate(List<String> words) {
            calls.add(List.copyOf(words));
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (fail) throw new SourceException.BadResponse("DeepL HTTP 456", 456);
            return words.stream().map(fn).toList();
        }
    }

    final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-27T00:00:00Z"));
    final Clock clock = new Clock() {
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId z) { return this; }
        public Instant instant() { return now.get(); }
    };
    final FakeTranslator fake = new FakeTranslator();
    final Map<String, String> saved = new ConcurrentHashMap<>();

    QueryNormalizer normalizer(Duration timeout) {
        return new QueryNormalizer(() -> Map.of("아이폰", "iphone", "케이스", "case"), clock, fake, saved::put, timeout);
    }

    @Test
    void dictionaryWordsAreNotSent_unknownWordsTranslatedAndSaved() {
        QueryNormalizer n = normalizer(Duration.ofSeconds(2));
        assertThat(n.normalize("아이폰17 케이스").sourceQuery()).isEqualTo("iphone 17 case");
        assertThat(fake.calls).isEmpty();

        assertThat(n.normalize("가습기 필터").sourceQuery()).isEqualTo("humidifier filter");   // 한 번에 같이 보냄
        assertThat(fake.calls).containsExactly(List.of("가습기", "필터"));
        assertThat(saved).containsEntry("가습기", "humidifier").containsEntry("필터", "filter");

        // 두 번째부터는 사전처럼 (부르지 않음), 다른 단어와 섞여도
        assertThat(n.normalize("아이폰 가습기").sourceQuery()).isEqualTo("iphone humidifier");
        assertThat(fake.calls).hasSize(1);
    }

    @Test
    void englishAndModelNamesAreLeftAlone() {
        QueryNormalizer n = normalizer(Duration.ofSeconds(2));
        assertThat(n.normalize("dyson v15 SM-S938B").sourceQuery()).isEqualTo("dyson v15 SM-S938B");
        assertThat(fake.calls).isEmpty();
    }

    @Test
    void slowTranslation_searchesWithOriginalWord_thenSavesInBackground() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        fake.delayMs = 300;
        QueryNormalizer n = new QueryNormalizer(Map::of, clock, fake, (a, t) -> {
            saved.put(a, t);
            done.countDown();
        }, Duration.ofMillis(50));
        assertThat(n.normalize("가습기").sourceQuery()).isEqualTo("가습기");   // 기다리지 않음
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(saved).containsEntry("가습기", "humidifier");
        fake.delayMs = 0;
        assertThat(n.normalize("가습기").sourceQuery()).isEqualTo("humidifier");
        assertThat(fake.calls).hasSize(1);
    }

    @Test
    void badTranslationsAreNotSaved_andNotAskedAgainForAnHour() {
        fake.fn = w -> switch (w) {
            case "가습기" -> "가습기";      // 한글 그대로
            case "필터" -> "  ";           // 빈 값
            default -> w;                 // 원문과 같음
        };
        QueryNormalizer n = normalizer(Duration.ofSeconds(2));
        assertThat(n.normalize("가습기 필터").sourceQuery()).isEqualTo("가습기 필터");
        assertThat(saved).isEmpty();
        n.normalize("가습기 필터");
        assertThat(fake.calls).hasSize(1);   // 1시간 동안 다시 묻지 않음

        now.set(now.get().plus(QueryNormalizer.RETRY_FAILED_AFTER).plusSeconds(1));
        fake.fn = w -> Map.of("가습기", "humidifier", "필터", "filter").get(w);
        assertThat(n.normalize("가습기 필터").sourceQuery()).isEqualTo("humidifier filter");
    }

    @Test
    void translatorError_searchesWithOriginalWord() {
        fake.fail = true;   // 무료 한도 초과(456) · 키 오류 등
        QueryNormalizer n = normalizer(Duration.ofSeconds(2));
        assertThat(n.normalize("아이폰 가습기").sourceQuery()).isEqualTo("iphone 가습기");
        assertThat(saved).isEmpty();
    }

    @Test
    void disabledTranslator_behavesAsBefore() {
        QueryNormalizer n = new QueryNormalizer(() -> Map.of("아이폰", "iphone"), clock);
        assertThat(n.normalize("아이폰 가습기").sourceQuery()).isEqualTo("iphone 가습기");
    }

    @Test
    void clean_trimsPunctuationAndLowercases() {
        assertThat(QueryNormalizer.clean("\"Screen Protector.\"", "필름")).isEqualTo("screen protector");
        assertThat(QueryNormalizer.clean("Humidifier  Filter", "가습기필터")).isEqualTo("humidifier filter");
        assertThat(QueryNormalizer.clean(null, "필름")).isNull();
        assertThat(QueryNormalizer.clean("필름", "필름")).isNull();
        assertThat(QueryNormalizer.clean("x".repeat(256), "필름")).isNull();
    }

    @Test
    void deepl_freeKeyUsesFreeUrl_andParsesResponse() {
        assertThat(DeeplTranslator.urlFor("abc:fx")).isEqualTo(DeeplTranslator.FREE_URL);
        assertThat(DeeplTranslator.urlFor("abc")).isEqualTo(DeeplTranslator.PRO_URL);
        String body = """
                {"translations":[{"detected_source_language":"KO","text":"humidifier"},
                                 {"detected_source_language":"KO","text":"filter"}]}""";
        assertThat(DeeplTranslator.parse(TestProps.MAPPER, body, 2)).containsExactly("humidifier", "filter");
        assertThatThrownBy(() -> DeeplTranslator.parse(TestProps.MAPPER, body, 3)).isInstanceOf(SourceException.class);
        assertThatThrownBy(() -> DeeplTranslator.parse(TestProps.MAPPER, "<html>", 1)).isInstanceOf(SourceException.class);
    }

    @Test
    void deepl_withoutKey_isDisabled() {
        var props = TestProps.props("http://localhost:1", Duration.ofSeconds(1));
        DeeplTranslator t = new DeeplTranslator(TestProps.rest(props), TestProps.MAPPER, clock, true, " ", "", 1000);
        assertThat(t.enabled()).isFalse();
    }
}
