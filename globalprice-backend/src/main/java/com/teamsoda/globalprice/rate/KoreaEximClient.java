package com.teamsoda.globalprice.rate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.teamsoda.globalprice.config.GlobalPriceProperties;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 한국수출입은행 현재환율 API 클라이언트.
 * GET {url}?authkey=..&searchdate=YYYYMMDD&data=AP01 → [{result, cur_unit, ttb, tts, deal_bas_r, ...}]
 *
 * <ul>
 *   <li>숫자에 쉼표가 들어 있음("1,380.5") → 제거 후 파싱</li>
 *   <li>JPY(100) · IDR(100) 처럼 100 단위 통화는 100 으로 나눠 1 단위로 (소수 6자리)</li>
 *   <li>주말 · 공휴일 · 11시 이전에는 빈 배열 → 빈 목록 반환</li>
 *   <li>result 1 = 성공, 2 = DATA 코드 오류, 3 = 인증 코드 오류, 4 = 일일 제한 초과</li>
 * </ul>
 * 인증키는 URL 쿼리에 들어가므로 예외 메시지 · 로그에 URL 을 남기지 않는다.
 */
@Component
public class KoreaEximClient {

    private static final Logger log = LoggerFactory.getLogger(KoreaEximClient.class);
    private static final Pattern UNIT = Pattern.compile("^([A-Z]{3})(?:\\((\\d+)\\))?$");
    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    public record RawRate(String currencyCode, BigDecimal rateToKrw, BigDecimal ttsRate) {
    }

    public static class EximException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final Integer httpStatus;

        public EximException(String message, Integer httpStatus) {
            super(message);
            this.httpStatus = httpStatus;
        }

        public Integer httpStatus() {
            return httpStatus;
        }
    }

    private final RestClient restClient;
    private final GlobalPriceProperties.KoreaExim props;
    private final ObjectMapper mapper;

    public KoreaEximClient(RestClient.Builder externalRestClientBuilder, GlobalPriceProperties props,
                           ObjectMapper mapper) {
        this.restClient = externalRestClientBuilder.build();
        this.props = props.sources().koreaexim();
        this.mapper = mapper;
    }

    public boolean configured() {
        return props.authKey() != null && !props.authKey().isBlank();
    }

    public List<RawRate> fetch(LocalDate date) {
        if (!configured()) throw new EximException("KOREAEXIM_KEY 가 설정되지 않음", null);
        String body;
        try {
            body = restClient.get()
                    .uri(props.url() + "?authkey={k}&searchdate={d}&data=AP01", props.authKey(), date.format(YMD))
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            throw new EximException("환율 API HTTP " + e.getStatusCode().value(), e.getStatusCode().value());
        } catch (RestClientException e) {
            throw new EximException("환율 API 호출 실패: " + e.getClass().getSimpleName(), null);
        }
        return parse(body);
    }

    List<RawRate> parse(String body) {
        if (body == null || body.isBlank()) return List.of();
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (RuntimeException e) {
            throw new EximException("환율 API 응답 형식 오류", null);
        }
        if (!root.isArray()) throw new EximException("환율 API 응답이 배열이 아님", null);
        List<RawRate> out = new ArrayList<>();
        for (JsonNode n : root) {
            int result = n.path("result").asInt(1);
            if (result != 1) {
                throw new EximException("환율 API result=" + result + (result == 3 ? " (인증키 오류)" : result == 4
                        ? " (일일 제한 초과)" : ""), null);
            }
            String unit = n.path("cur_unit").asString("").trim();
            Matcher m = UNIT.matcher(unit);
            if (!m.matches()) {
                log.debug("알 수 없는 통화 단위 건너뜀: {}", unit);
                continue;
            }
            String code = m.group(1);
            if ("KRW".equals(code)) continue;
            BigDecimal divisor = m.group(2) == null ? BigDecimal.ONE : new BigDecimal(m.group(2));
            BigDecimal deal = number(n.path("deal_bas_r").asString(null));
            BigDecimal tts = number(n.path("tts").asString(null));
            if (deal == null || deal.signum() <= 0) continue;
            out.add(new RawRate(code, deal.divide(divisor, 6, RoundingMode.HALF_UP),
                    tts == null || tts.signum() <= 0 ? null : tts.divide(divisor, 6, RoundingMode.HALF_UP)));
        }
        return out;
    }

    static BigDecimal number(String s) {
        if (s == null) return null;
        String v = s.replace(",", "").trim();
        if (v.isEmpty()) return null;
        try {
            return new BigDecimal(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
