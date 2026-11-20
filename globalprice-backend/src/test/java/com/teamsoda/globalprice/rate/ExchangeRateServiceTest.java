package com.teamsoda.globalprice.rate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.teamsoda.globalprice.common.SyncLogRepository;
import com.teamsoda.globalprice.rate.ExchangeRateService.CollectResult;

/** 서버 시작 시 "가장 최근 영업일" 환율 확보 */
class ExchangeRateServiceTest {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final LocalDate SUNDAY = LocalDate.of(2026, 11, 15);
    static final LocalDate FRIDAY = LocalDate.of(2026, 11, 13);

    KoreaEximClient client = mock(KoreaEximClient.class);
    ExchangeRateRepository repository = mock(ExchangeRateRepository.class);
    ExchangeRateService service;

    @BeforeEach
    void setUp() {
        Clock sunday = Clock.fixed(SUNDAY.atTime(10, 0).atZone(KST).toInstant(), KST);
        service = new ExchangeRateService(client, repository, mock(SyncLogRepository.class), sunday);
        when(client.configured()).thenReturn(true);
    }

    @Test
    void sunday_fallsBackToFriday() {
        when(client.fetch(SUNDAY)).thenReturn(List.of());
        when(client.fetch(SUNDAY.minusDays(1))).thenReturn(List.of());
        when(client.fetch(FRIDAY)).thenReturn(List.of(
                new KoreaEximClient.RawRate("EUR", new BigDecimal("1472.97"), new BigDecimal("1487.69"))));
        when(repository.upsert(anyList(), eq(FRIDAY), any())).thenReturn(1);

        assertThat(service.collectLatestAvailable()).isEqualTo(CollectResult.SAVED);
        verify(repository).upsert(anyList(), eq(FRIDAY), any());
    }

    @Test
    void alreadyHaveLatest_makesNoCall() {
        when(repository.existsForDate(SUNDAY)).thenReturn(false);
        when(client.fetch(SUNDAY)).thenReturn(List.of());
        when(repository.existsForDate(SUNDAY.minusDays(1))).thenReturn(true);   // 토요일 값이 이미 있음

        assertThat(service.collectLatestAvailable()).isEqualTo(CollectResult.SAVED);
        verify(client, never()).fetch(FRIDAY);
    }

    @Test
    void noKey_stopsImmediately() {
        when(client.configured()).thenReturn(false);
        assertThat(service.collectLatestAvailable()).isEqualTo(CollectResult.NOT_CONFIGURED);
        verify(client, never()).fetch(any());
    }

    @Test
    void authError_stopsWithoutWalkingBack() {
        when(client.fetch(SUNDAY)).thenThrow(new KoreaEximClient.EximException("환율 API result=3 (인증키 오류)", null));
        assertThat(service.collectLatestAvailable()).isEqualTo(CollectResult.FAILED);
        verify(client, never()).fetch(SUNDAY.minusDays(1));
    }
}
