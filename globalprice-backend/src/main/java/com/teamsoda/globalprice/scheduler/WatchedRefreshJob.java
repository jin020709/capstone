package com.teamsoda.globalprice.scheduler;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.teamsoda.globalprice.alert.AlertEvaluator;
import com.teamsoda.globalprice.alert.AlertEvaluator.ProductPrice;
import com.teamsoda.globalprice.catalog.CatalogRepository;
import com.teamsoda.globalprice.catalog.CatalogRepository.StoredOffer;
import com.teamsoda.globalprice.catalog.CatalogService;
import com.teamsoda.globalprice.catalog.MallDirectory;
import com.teamsoda.globalprice.catalog.MallDirectory.Mall;
import com.teamsoda.globalprice.catalog.OfferPricer;
import com.teamsoda.globalprice.catalog.Offers.OfferData;
import com.teamsoda.globalprice.catalog.Offers.PricedOffer;
import com.teamsoda.globalprice.common.SyncLogRepository;
import com.teamsoda.globalprice.common.SyncLogRepository.Status;
import com.teamsoda.globalprice.search.SourceCaller;
import com.teamsoda.globalprice.source.MallSource;
import com.teamsoda.globalprice.source.MallSource.MallOffer;
import com.teamsoda.globalprice.source.SourceException;

/**
 * 관심 상품 가격 갱신 (명세서 7-2, 6시간마다): 찜 · 활성 알림이 걸린 상품만 쇼핑몰 API 로 다시 조회 → 저장 →
 * 가격 이력 → 알림 판정. 한 건 실패는 건너뛰고 계속하며 소스 단위로 api_sync_logs 1행을 남긴다.
 */
@Component
public class WatchedRefreshJob {

    private static final Logger log = LoggerFactory.getLogger(WatchedRefreshJob.class);

    public record RunResult(int refreshed, int failed, int notifications) {
    }

    private final CatalogRepository repository;
    private final CatalogService catalog;
    private final MallDirectory malls;
    private final SourceCaller caller;
    private final OfferPricer pricer;
    private final AlertEvaluator evaluator;
    private final SyncLogRepository syncLog;

    public WatchedRefreshJob(CatalogRepository repository, CatalogService catalog, MallDirectory malls,
                             SourceCaller caller, OfferPricer pricer, AlertEvaluator evaluator,
                             SyncLogRepository syncLog) {
        this.repository = repository;
        this.catalog = catalog;
        this.malls = malls;
        this.caller = caller;
        this.pricer = pricer;
        this.evaluator = evaluator;
        this.syncLog = syncLog;
    }

    @Scheduled(cron = "0 0 */6 * * *", zone = "Asia/Seoul")
    public void scheduled() {
        try {
            RunResult r = run();
            log.info("관심 상품 갱신 완료: 갱신 {} · 실패 {} · 알림 {}", r.refreshed(), r.failed(), r.notifications());
        } catch (RuntimeException e) {
            log.error("관심 상품 갱신 작업 실패", e);
        }
    }

    /** 행사 알림(EVENT_START · COUPON_EXPIRY)은 매일 아침에도 한 번 판정 */
    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Seoul")
    public void dailyEventAlerts() {
        try {
            log.info("행사 알림 {}건", evaluator.evaluateEvents());
        } catch (RuntimeException e) {
            log.error("행사 알림 판정 실패", e);
        }
    }

    public RunResult run() {
        OfferPricer.Context ctx = pricer.context();
        LocalDateTime runStart = ctx.now();
        List<StoredOffer> watched = repository.findWatched();
        List<Long> productIds = watched.stream().map(StoredOffer::productId).distinct().toList();

        // 0. 갱신 전 최근 30일 최저 (PERIOD_LOWEST 기준). 갱신이 쓰는 이력이 섞이지 않도록 먼저 구한다
        //    (DATETIME 은 초 단위로 반올림돼 "갱신 시작 이전" 시각 비교만으로는 같은 초의 기록을 못 가른다)
        Map<Long, Long> previousLowest = new HashMap<>();
        for (Long id : productIds) {
            previousLowest.put(id, repository.lowestBetween(id, runStart.minusDays(30), runStart.plusSeconds(1)));
        }

        // 1. 소스별로 모아 갱신 (last_fetched_at 오래된 순)
        Map<Long, List<StoredOffer>> byMall = watched.stream()
                .collect(Collectors.groupingBy(StoredOffer::mallId, LinkedHashMap::new, Collectors.toList()));
        int refreshed = 0;
        int failed = 0;
        for (Map.Entry<Long, List<StoredOffer>> e : byMall.entrySet()) {
            Mall mall = ctx.malls().get(e.getKey());
            MallSource source = malls.sourceFor(mall).filter(MallSource::enabled).orElse(null);
            if (source == null) continue;   // 시드 · 비활성 소스: 저장값 그대로
            List<String> ids = e.getValue().stream().map(StoredOffer::externalProductId).distinct().toList();
            int ok = 0;
            int bad = 0;
            Integer lastHttp = null;
            String lastError = null;
            for (int i = 0; i < ids.size(); i += source.refreshBatchSize()) {
                List<String> batch = ids.subList(i, Math.min(i + source.refreshBatchSize(), ids.size()));
                try {
                    List<MallOffer> fresh = caller.refresh(source, batch, true);
                    List<OfferData> saved = catalog.save(mall.mallId(), fresh, ctx);
                    ok += saved.size();
                    bad += batch.size() - saved.size();
                } catch (SourceException ex) {
                    bad += batch.size();
                    lastHttp = ex.httpStatus();
                    lastError = ex.getMessage();
                    if (ex instanceof SourceException.AuthFailed || ex instanceof SourceException.RateLimited) break;
                }
            }
            refreshed += ok;
            failed += bad;
            Status status = bad == 0 ? Status.SUCCESS : ok == 0 ? Status.FAILED : Status.PARTIAL;
            syncLog.record(source.sourceName(), "PRODUCT_REFRESH", status, lastHttp, ok, bad, lastError, runStart);
        }

        // 2. 알림 판정 (갱신 후 저장값 기준, 쿠폰 제외)
        OfferPricer.Context after = pricer.context();
        Map<Long, List<StoredOffer>> current = repository.findByProductIds(productIds).stream()
                .collect(Collectors.groupingBy(StoredOffer::productId));
        Map<Long, ProductPrice> prices = new HashMap<>();
        for (Long id : productIds) {
            List<PricedOffer> priced = new ArrayList<>();
            for (StoredOffer s : current.getOrDefault(id, List.of())) {
                priced.add(pricer.price(OfferData.of(s), after, false, false));
            }
            Long best = priced.stream().filter(PricedOffer::comparable).map(PricedOffer::total)
                    .min(Long::compare).orElse(null);
            prices.put(id, new ProductPrice(id, best, previousLowest.get(id)));
        }
        int notes = evaluator.evaluatePrices(prices) + evaluator.evaluateEvents();
        return new RunResult(refreshed, failed, notes);
    }
}
