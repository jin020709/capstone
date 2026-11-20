package com.teamsoda.globalprice.search;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import com.teamsoda.globalprice.catalog.CatalogService;
import com.teamsoda.globalprice.catalog.MallDirectory.Mall;
import com.teamsoda.globalprice.catalog.OfferPricer;
import com.teamsoda.globalprice.catalog.Offers.OfferData;
import com.teamsoda.globalprice.common.SyncLogRepository;
import com.teamsoda.globalprice.common.SyncLogRepository.Status;
import com.teamsoda.globalprice.config.AppConfig;
import com.teamsoda.globalprice.config.GlobalPriceProperties;
import com.teamsoda.globalprice.source.MallSource;
import com.teamsoda.globalprice.source.MallSource.MallOffer;
import com.teamsoda.globalprice.source.SourceException;
import com.teamsoda.globalprice.source.SourceGuard;

/**
 * 소스 1곳 호출: 캐시(검색어 기준 10분) → 호출 예산 · 429 쿨다운 → 외부 호출 → 걸러내기 → DB 저장.
 * 검색 중 실패는 api_sync_logs 에 남긴다. 스케줄러 갱신은 5xx 일 때 최대 3회(1 · 2 · 4초) 재시도.
 */
@Component
public class SourceCaller {

    private static final Logger log = LoggerFactory.getLogger(SourceCaller.class);

    public enum CallStatus { ok, timeout, error, skipped }

    public record SourceResult(MallSource source, Mall mall, CallStatus status, List<OfferData> offers,
                               boolean fromCache, SourceException error) {
    }

    /** 캐시에 넣는 값: 저장까지 끝난 offer 목록 */
    record CachedSearch(List<OfferData> offers) {
    }

    private final SourceGuard guard;
    private final CatalogService catalog;
    private final ResultFilter filter;
    private final SyncLogRepository syncLog;
    private final Cache cache;
    private final Clock clock;
    private final int perSourceLimit;
    private Sleeper sleeper = Thread::sleep;

    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    public SourceCaller(SourceGuard guard, CatalogService catalog, ResultFilter filter, SyncLogRepository syncLog,
                        CacheManager cacheManager, Clock clock, GlobalPriceProperties props) {
        this.guard = guard;
        this.catalog = catalog;
        this.filter = filter;
        this.syncLog = syncLog;
        this.cache = cacheManager.getCache(AppConfig.SEARCH_CACHE);
        this.clock = clock;
        this.perSourceLimit = props.search().perSourceLimit();
    }

    void setSleeper(Sleeper sleeper) {
        this.sleeper = sleeper;
    }

    public int perSourceLimit() {
        return perSourceLimit;
    }

    /** 검색 (사용자 요청 중): 재시도하지 않는다 */
    public SourceResult search(MallSource source, Mall mall, String query, OfferPricer.Context ctx) {
        String key = source.sourceName() + "|" + query.toLowerCase() + "|" + perSourceLimit;
        CachedSearch hit = cache == null ? null : cache.get(key, CachedSearch.class);
        if (hit != null) return new SourceResult(source, mall, CallStatus.ok, hit.offers(), true, null);

        SourceGuard.Decision d = guard.acquire(source);
        if (d != SourceGuard.Decision.ALLOW) {
            return new SourceResult(source, mall, d == SourceGuard.Decision.COOLING_DOWN ? CallStatus.error
                    : CallStatus.skipped, List.of(), false, null);
        }
        LocalDateTime started = LocalDateTime.now(clock);
        try {
            List<MallOffer> raw = source.search(query, new MallSource.SearchOptions(perSourceLimit));
            List<MallOffer> accepted = raw.stream().filter(o -> filter.accept(o, query)).toList();
            List<OfferData> saved = catalog.save(mall.mallId(), accepted, ctx);
            if (cache != null) cache.put(key, new CachedSearch(saved));
            return new SourceResult(source, mall, CallStatus.ok, saved, false, null);
        } catch (SourceException e) {
            onFailure(source, e, "SEARCH", started);
            return new SourceResult(source, mall, e instanceof SourceException.Timeout ? CallStatus.timeout
                    : CallStatus.error, List.of(), false, e);
        } catch (RuntimeException e) {
            log.error("[{}] 검색 처리 중 예외", source.sourceName(), e);
            SourceException wrapped = new SourceException.BadResponse("처리 오류: " + e.getClass().getSimpleName(), null);
            onFailure(source, wrapped, "SEARCH", started);
            return new SourceResult(source, mall, CallStatus.error, List.of(), false, wrapped);
        }
    }

    /** 단건 · 배치 갱신. retry=true 면 5xx · 타임아웃에 1 · 2 · 4초 간격으로 최대 3회 재시도 */
    public List<MallOffer> refresh(MallSource source, List<String> externalIds, boolean retry) {
        return withRetry(source, () -> source.refresh(externalIds), retry);
    }

    <T> T withRetry(MallSource source, Supplier<T> call, boolean retry) {
        int attempts = retry ? 4 : 1;
        long backoff = 1000;
        SourceException last = null;
        for (int i = 0; i < attempts; i++) {
            SourceGuard.Decision d = guard.acquire(source);
            if (d != SourceGuard.Decision.ALLOW) {
                throw new SourceException.BadResponse(source.sourceName() + " 호출 불가: " + d, null);
            }
            try {
                return call.get();
            } catch (SourceException.RateLimited e) {
                guard.onRateLimited(source);
                throw e;
            } catch (SourceException.AuthFailed e) {
                throw e;
            } catch (SourceException.BadResponse e) {
                if (!e.retryable() || e.httpStatus() == null) throw e;
                last = e;
            } catch (SourceException.Timeout e) {
                last = e;
            }
            if (i < attempts - 1) {
                try {
                    sleeper.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw last;
                }
                backoff *= 2;
            }
        }
        throw last;
    }

    private void onFailure(MallSource source, SourceException e, String type, LocalDateTime started) {
        if (e instanceof SourceException.RateLimited) guard.onRateLimited(source);
        if (e instanceof SourceException.AuthFailed) {
            log.error("[{}] 인증 실패 — 키 설정을 확인하세요 (HTTP {})", source.sourceName(), e.httpStatus());
        } else {
            log.warn("[{}] {}", source.sourceName(), e.getMessage());
        }
        syncLog.record(source.sourceName(), "PRODUCT_" + type, Status.FAILED, e.httpStatus(), 0, 0, e.getMessage(),
                started);
    }
}
