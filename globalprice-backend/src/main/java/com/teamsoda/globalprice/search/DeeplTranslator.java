package com.teamsoda.globalprice.search;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.teamsoda.globalprice.source.Json;
import com.teamsoda.globalprice.source.SourceException;
import com.teamsoda.globalprice.source.SourceHttp;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * DeepL API 번역 (ko → en-US). 무료 키(끝이 ":fx")는 api-free 주소, 유료 키는 api 주소를 쓴다.
 * 무료 등급은 월 글자 한도를 넘으면 과금 없이 456 을 돌려주고, 그때는 원래 한글로 검색한다.
 * 단어 여러 개를 한 번에 보낸다 (요청 1회). 하루 호출 수는 daily-budget 까지.
 */
@Component
public class DeeplTranslator implements QueryTranslator {

    private static final Logger log = LoggerFactory.getLogger(DeeplTranslator.class);
    static final String FREE_URL = "https://api-free.deepl.com/v2/translate";
    static final String PRO_URL = "https://api.deepl.com/v2/translate";

    private final RestClient rest;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final boolean enabled;
    private final String apiKey;
    private final String url;
    private final int dailyBudget;
    private LocalDate budgetDay;
    private int usedToday;

    public DeeplTranslator(RestClient.Builder externalRestClientBuilder, ObjectMapper mapper, Clock clock,
                           @Value("${globalprice.translate.deepl.enabled:true}") boolean enabled,
                           @Value("${globalprice.translate.deepl.api-key:}") String apiKey,
                           @Value("${globalprice.translate.deepl.url:}") String url,
                           @Value("${globalprice.translate.deepl.daily-budget:1000}") int dailyBudget) {
        this.rest = externalRestClientBuilder.build();
        this.mapper = mapper;
        this.clock = clock;
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.enabled = enabled && !this.apiKey.isEmpty();
        this.url = url == null || url.isBlank() ? urlFor(this.apiKey) : url.strip();
        this.dailyBudget = dailyBudget;
        if (enabled && this.apiKey.isEmpty()) log.info("DEEPL_API_KEY 가 없어 검색어 자동 번역을 끔 (사전만 사용)");
    }

    static String urlFor(String apiKey) {
        return apiKey.endsWith(":fx") ? FREE_URL : PRO_URL;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public List<String> translate(List<String> words) {
        if (!enabled) throw new IllegalStateException("DeepL 번역이 꺼져 있음");
        if (words.isEmpty()) return List.of();
        takeBudget();
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("text", words);
        req.put("source_lang", "KO");
        req.put("target_lang", "EN-US");
        String body = SourceHttp.call("DEEPL", () -> rest.post().uri(url)
                .header("Authorization", "DeepL-Auth-Key " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapper.writeValueAsString(req))
                .retrieve()
                .body(String.class));
        return parse(mapper, body, words.size());
    }

    private synchronized void takeBudget() {
        LocalDate today = LocalDate.now(clock);
        if (!today.equals(budgetDay)) {
            budgetDay = today;
            usedToday = 0;
        }
        if (usedToday >= dailyBudget) throw new SourceException.BadResponse("DeepL 하루 호출 예산 초과", null);
        usedToday++;
    }

    /** {"translations":[{"detected_source_language":"KO","text":"..."}]} → 번역문 목록 (개수가 다르면 예외) */
    static List<String> parse(ObjectMapper mapper, String body, int expected) {
        JsonNode list;
        try {
            list = mapper.readTree(body == null ? "" : body).path("translations");
        } catch (RuntimeException e) {
            throw new SourceException.BadResponse("DeepL 응답 형식 오류", null);
        }
        if (!list.isArray() || list.size() != expected) {
            throw new SourceException.BadResponse("DeepL 응답 개수가 다름", null);
        }
        List<String> out = new ArrayList<>();
        for (JsonNode n : list) out.add(Json.text(n, "text"));
        return out;
    }
}
