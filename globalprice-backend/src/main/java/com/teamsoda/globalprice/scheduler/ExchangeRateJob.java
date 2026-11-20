package com.teamsoda.globalprice.scheduler;

import java.time.Clock;
import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.teamsoda.globalprice.rate.ExchangeRateService;

/** 환율 수집: 평일 11:30, 빈 결과였으면 15:00 에 한 번 더 (명세서 7-1) */
@Component
public class ExchangeRateJob {

    private static final Logger log = LoggerFactory.getLogger(ExchangeRateJob.class);

    private final ExchangeRateService service;
    private final Clock clock;

    public ExchangeRateJob(ExchangeRateService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @Scheduled(cron = "0 30 11 * * MON-FRI", zone = "Asia/Seoul")
    public void morning() {
        log.info("환율 수집 (11:30): {}", service.collectToday());
    }

    @Scheduled(cron = "0 0 15 * * MON-FRI", zone = "Asia/Seoul")
    public void afternoonRetry() {
        if (service.hasRatesFor(LocalDate.now(clock))) return;
        log.info("환율 재시도 (15:00): {}", service.collectToday());
    }

    /**
     * 서버 시작 시 가장 최근 영업일 환율을 확보한다 (키가 있을 때만).
     * 이미 그날 값이 있으면 호출하지 않고, 주말 · 공휴일이면 직전 영업일 값을 받는다.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            log.info("시작 시 환율 확인: {}", service.collectLatestAvailable());
        } catch (RuntimeException e) {
            log.warn("시작 시 환율 확인 실패: {}", e.getMessage());
        }
    }
}
