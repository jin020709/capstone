package com.teamsoda.globalprice.rate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.teamsoda.globalprice.common.SyncLogRepository;
import com.teamsoda.globalprice.common.SyncLogRepository.Status;
import com.teamsoda.globalprice.pricing.PricingModels.FxRate;
import com.teamsoda.globalprice.pricing.PricingModels.FxTable;

@Service
public class ExchangeRateService {

    public static final String SOURCE_NAME = "KOREAEXIM_RATE";
    private static final Logger log = LoggerFactory.getLogger(ExchangeRateService.class);

    public enum CollectResult { SAVED, EMPTY, FAILED, NOT_CONFIGURED }

    private final KoreaEximClient client;
    private final ExchangeRateRepository repository;
    private final SyncLogRepository syncLog;
    private final Clock clock;

    public ExchangeRateService(KoreaEximClient client, ExchangeRateRepository repository, SyncLogRepository syncLog,
                               Clock clock) {
        this.client = client;
        this.repository = repository;
        this.syncLog = syncLog;
        this.clock = clock;
    }

    /** 오늘(KST) 환율 수집. 빈 결과(주말 · 공휴일 · 11시 이전)면 저장하지 않고 최근 값을 계속 쓴다 */
    public CollectResult collectToday() {
        return collect(LocalDate.now(clock));
    }

    public CollectResult collect(LocalDate date) {
        if (!client.configured()) {
            log.warn("KOREAEXIM_KEY 가 없어 환율 수집을 건너뜀");
            return CollectResult.NOT_CONFIGURED;
        }
        LocalDateTime started = LocalDateTime.now(clock);
        try {
            List<KoreaEximClient.RawRate> rates = client.fetch(date);
            if (rates.isEmpty()) {
                syncLog.record(SOURCE_NAME, "EXCHANGE_RATE", Status.SUCCESS, 200, 0, 0, "빈 결과 (비영업일 또는 고시 전)",
                        started);
                return CollectResult.EMPTY;
            }
            int saved = repository.upsert(rates, date, LocalDateTime.now(clock));
            syncLog.record(SOURCE_NAME, "EXCHANGE_RATE", Status.SUCCESS, 200, saved, 0, null, started);
            log.info("환율 {}건 저장 (기준일 {})", saved, date);
            return CollectResult.SAVED;
        } catch (KoreaEximClient.EximException e) {
            syncLog.record(SOURCE_NAME, "EXCHANGE_RATE", Status.FAILED, e.httpStatus(), 0, 0, e.getMessage(), started);
            log.warn("환율 수집 실패: {}", e.getMessage());
            return CollectResult.FAILED;
        }
    }

    /**
     * 가장 최근 영업일 환율을 확보한다 (서버 시작용). 오늘부터 하루씩 거슬러 올라가며 (최대 7일)
     * 이미 저장된 날이 나오면 멈추고, 빈 결과(주말 · 공휴일 · 고시 전)면 전날을 시도한다.
     * 주말에 서버를 켜도 금요일 환율이 들어가게 하려는 것.
     */
    public CollectResult collectLatestAvailable() {
        LocalDate today = LocalDate.now(clock);
        for (int i = 0; i < 7; i++) {
            LocalDate d = today.minusDays(i);
            if (repository.existsForDate(d)) return CollectResult.SAVED;   // 이미 최신
            CollectResult r = collect(d);
            if (r != CollectResult.EMPTY) return r;   // 저장 성공 · 실패 · 키 없음이면 멈춤
        }
        return CollectResult.EMPTY;
    }

    public boolean hasRatesFor(LocalDate date) {
        return repository.existsForDate(date);
    }

    public FxTable currentTable() {
        Map<String, FxRate> map = new LinkedHashMap<>();
        for (FxRate r : repository.latestPerCurrency()) map.put(r.currencyCode(), r);
        return new FxTable(map);
    }
}
