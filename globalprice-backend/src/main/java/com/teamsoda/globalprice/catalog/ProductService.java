package com.teamsoda.globalprice.catalog;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.teamsoda.globalprice.catalog.CatalogRepository.DailyLowest;
import com.teamsoda.globalprice.catalog.CatalogRepository.StoredOffer;
import com.teamsoda.globalprice.catalog.MallDirectory.Mall;
import com.teamsoda.globalprice.catalog.Offers.OfferData;
import com.teamsoda.globalprice.catalog.Offers.PriceStatus;
import com.teamsoda.globalprice.catalog.Offers.PricedOffer;
import com.teamsoda.globalprice.catalog.Offers.ProductGroup;
import com.teamsoda.globalprice.catalog.Views.EventView;
import com.teamsoda.globalprice.catalog.Views.OfferView;
import com.teamsoda.globalprice.catalog.Views.ProductSummary;
import com.teamsoda.globalprice.catalog.Views.RatesView;
import com.teamsoda.globalprice.config.GlobalPriceProperties;
import com.teamsoda.globalprice.exception.BusinessException;
import com.teamsoda.globalprice.exception.ErrorCode;
import com.teamsoda.globalprice.pricing.RuleRepository;
import com.teamsoda.globalprice.search.SourceCaller;
import com.teamsoda.globalprice.source.MallSource;
import com.teamsoda.globalprice.source.MallSource.MallOffer;

import jakarta.annotation.PreDestroy;

/** 상품 상세 (6-3) · 가격 이력 (6-4) · 저장값 기준 요약 (찜 목록용) */
@Service
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    public record ProductDetail(long id, String name, String brand, String model, String spec, String description,
                                String imageUrl, String categoryName, Double rating, Integer reviewCount,
                                List<String> badges, Long bestTotal, Long bestNormalTotal, String bestStore,
                                int offerCount, List<OfferView> offers, List<OfferView> variants,
                                List<EventView> events, RatesView rates, String priceStatus,
                                LocalDateTime fetchedAt, Boolean wished, List<ConfigView> configs) {
    }

    /**
     * 같은 모델의 다른 구성 (다나와 "다른 구성"). current = 지금 보고 있는 상품.
     * bestTotal 은 저장된 가격 기준 최저 예상 구매비용, storageGb 는 GB당 가격 계산용
     */
    public record ConfigView(long productId, String label, Long bestTotal, int offerCount, Integer storageGb,
                             boolean current) {
    }

    public record HistoryPoint(LocalDate date, long lowest, long lowestWithCoupon, long lowestTotalKrw) {
    }

    public record HistorySummary(Long minKrw, Long avgKrw, Long maxKrw) {
    }

    public record PriceHistory(long productId, String period, List<HistoryPoint> points, HistorySummary summary) {
    }

    private final CatalogRepository repository;
    private final CatalogService catalog;
    private final OfferPricer pricer;
    private final ViewFactory views;
    private final MallDirectory malls;
    private final SourceCaller caller;
    private final RuleRepository rules;
    private final Duration refreshTimeout;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ProductService(CatalogRepository repository, CatalogService catalog, OfferPricer pricer, ViewFactory views,
                          MallDirectory malls, SourceCaller caller, RuleRepository rules,
                          GlobalPriceProperties props) {
        this.repository = repository;
        this.catalog = catalog;
        this.pricer = pricer;
        this.views = views;
        this.malls = malls;
        this.caller = caller;
        this.rules = rules;
        this.refreshTimeout = props.search().detailRefreshTimeout();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    public ProductDetail detail(long productId, boolean coupon, Boolean wished) {
        List<StoredOffer> stored = repository.findByProductId(productId);
        if (stored.isEmpty()) throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        OfferPricer.Context ctx = pricer.context();

        // 쇼핑몰 API 로 단건 갱신 시도 (3초). 성공한 mall_product 는 방금 받은 값으로 계산
        Set<Long> refreshed = refresh(stored, ctx);
        if (!refreshed.isEmpty()) stored = repository.findByProductId(productId);

        // 다른 구성(용량별) 최저가로 기준값을 잡아, 판매처가 1~2곳뿐이어도 터무니없이 싼 가격을 거른다
        List<ConfigView> cfg = configs(productId, null, 0);
        Long familyRef = PriceOutliers.familyReference(cfg.stream().filter(c -> !c.current())
                .map(ConfigView::bestTotal).toList());
        List<PricedOffer> priced = PriceOutliers.markBelow(PriceOutliers.mark(stored.stream()
                .map(s -> pricer.price(OfferData.of(s, ctx.countryOf(s.mallId())), ctx, coupon,
                        refreshed.contains(s.mallProductId())))
                .toList()), familyRef).stream().sorted(ViewFactory.OFFER_ORDER).toList();
        StoredOffer meta = stored.getFirst();
        List<String> extra = new ArrayList<>();
        Long currentBest = priced.stream().filter(PricedOffer::comparable).map(p -> p.normal().totalAmountKrw())
                .min(Long::compare).orElse(null);
        Long low30 = repository.lowestBetween(productId, ctx.now().minusDays(30), ctx.now().plusSeconds(1));
        if (currentBest != null && low30 != null && currentBest <= low30) extra.add(ViewFactory.BADGE_LOW_30D);
        ProductSummary s = views.summary(
                new ProductGroup(productId, meta.productName(), meta.imageUrl(), meta.matchKey(), priced),
                ctx, extra, meta, Integer.MAX_VALUE);

        Set<Long> mallIds = stored.stream().map(StoredOffer::mallId).collect(Collectors.toSet());
        List<EventView> events = rules.activeEventsOverlapping(ctx.now(), ctx.now().plusYears(1)).stream()
                .filter(e -> mallIds.contains(e.mallId()))
                .map(e -> views.event(e, ctx.malls())).toList();

        String priceStatus = priced.stream().allMatch(p -> p.status() == PriceStatus.unavailable) ? "unavailable"
                : priced.stream().anyMatch(p -> p.status() == PriceStatus.stale) ? "stale" : "ok";
        if (priced.stream().noneMatch(PricedOffer::comparable)) priceStatus = "unavailable";
        LocalDateTime fetchedAt = stored.stream().map(StoredOffer::fetchedAt).filter(Objects::nonNull)
                .max(LocalDateTime::compareTo).map(t -> t.truncatedTo(java.time.temporal.ChronoUnit.SECONDS))
                .orElse(null);
        return new ProductDetail(productId, meta.productName(), meta.brandName(), meta.modelNumber(), null,
                meta.description(), meta.imageUrl(), s.categoryName(), null, null, s.badges(), s.bestTotal(),
                s.bestNormalTotal(), s.bestStore(), s.offerCount(), s.offers(), s.offers(), events,
                views.rates(ctx.fx()), priceStatus, fetchedAt, wished, cfg.stream()
                        .map(c -> c.current() ? new ConfigView(c.productId(), c.label(), s.bestTotal(), s.offerCount(),
                                c.storageGb(), true) : c)
                        .toList());
    }

    /** 같은 모델(용량 등만 다름)의 다른 상품들. 구성이 하나뿐이거나 구성을 모르는 상품이면 빈 목록 */
    List<ConfigView> configs(long productId, Long currentBest, int currentCount) {
        ProductMatcher.Config me = ProductMatcher.config(repository.matchKey(productId));
        if (me == null) return List.of();
        Map<Long, ProductMatcher.Config> family = new LinkedHashMap<>();
        repository.productsByKeyPrefix(me.family() + "|").forEach((id, key) -> {
            ProductMatcher.Config c = ProductMatcher.config(key);
            if (c != null && c.family().equals(me.family())) family.put(id, c);
        });
        if (family.size() < 2) return List.of();
        List<Long> others = family.keySet().stream().filter(id -> id != productId).toList();
        Map<Long, ProductSummary> sums = summaries(others).stream()
                .collect(Collectors.toMap(ProductSummary::id, x -> x, (a, b) -> a));
        List<ConfigView> out = new ArrayList<>();
        family.forEach((id, c) -> {
            if (id == productId) {
                out.add(new ConfigView(id, c.label(), currentBest, currentCount, c.storageGb(), true));
            } else if (sums.containsKey(id)) {
                ProductSummary x = sums.get(id);
                out.add(new ConfigView(id, c.label(), x.bestTotal(), x.offerCount(), c.storageGb(), false));
            }
        });
        out.sort(java.util.Comparator.comparingLong(v -> family.get(v.productId()).order()));
        return out.size() < 2 ? List.of() : out;
    }

    /** 상세 화면 한 번에 쇼핑몰로 다시 물어보는 리스팅 수 (묶인 상품은 판매처가 수십 곳일 수 있음) */
    static final int MAX_DETAIL_REFRESH = 6;

    /**
     * 소스가 있는 mall_product 를 단건 갱신. 성공한 mall_product_id 집합.
     * 가장 오래전에 받은 것부터 최대 {@link #MAX_DETAIL_REFRESH}곳만 (나머지는 저장값 + "N시간 전 가격" 표시)
     */
    Set<Long> refresh(List<StoredOffer> stored, OfferPricer.Context ctx) {
        Map<Long, StoredOffer> byMallProduct = new LinkedHashMap<>();
        stored.stream()
                .sorted(java.util.Comparator.comparing(StoredOffer::fetchedAt,
                        java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())))
                .forEach(s -> byMallProduct.putIfAbsent(s.mallProductId(), s));
        record Job(StoredOffer s, MallSource source, Future<List<MallOffer>> future) {
        }
        List<Job> jobs = new ArrayList<>();
        for (StoredOffer s : byMallProduct.values()) {
            if (jobs.size() >= MAX_DETAIL_REFRESH) break;
            Mall mall = ctx.malls().get(s.mallId());
            MallSource source = malls.sourceFor(mall).filter(MallSource::enabled).orElse(null);
            if (source == null) continue;
            jobs.add(new Job(s, source, executor.submit(
                    () -> caller.refresh(source, List.of(s.externalProductId()), false))));
        }
        Set<Long> ok = new HashSet<>();
        long deadline = System.nanoTime() + refreshTimeout.toNanos();
        for (Job j : jobs) {
            try {
                long wait = Math.max(deadline - System.nanoTime(), 1);
                List<MallOffer> fresh = j.future().get(wait, TimeUnit.NANOSECONDS);
                if (!fresh.isEmpty()) {
                    catalog.save(j.s().mallId(), fresh, ctx);
                    ok.add(j.s().mallProductId());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                j.future().cancel(true);
                log.info("[{}] 상세 단건 갱신 실패 → 저장값 사용: {}", j.source().sourceName(),
                        e.getCause() == null ? e.getClass().getSimpleName() : e.getCause().getMessage());
            }
        }
        return ok;
    }

    public PriceHistory history(long productId, String period) {
        Duration span = switch (period == null ? "1m" : period) {
            case "1m" -> Duration.ofDays(30);
            case "3m" -> Duration.ofDays(91);
            case "6m" -> Duration.ofDays(182);
            case "1y" -> Duration.ofDays(365);
            default -> throw new BusinessException(ErrorCode.INVALID_INPUT, "period 는 1m · 3m · 6m · 1y 중 하나예요.");
        };
        if (!repository.productExists(productId)) throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        LocalDateTime now = pricer.context().now();
        List<DailyLowest> rows = repository.dailyLowest(productId, now.minus(span));
        // price_history 는 쿠폰을 뺀 기본 계산값만 저장하므로 lowestWithCoupon 도 같은 값을 쓴다
        List<HistoryPoint> points = rows.stream()
                .map(r -> new HistoryPoint(r.date(), r.lowestTotalKrw(), r.lowestTotalKrw(), r.lowestTotalKrw()))
                .toList();
        HistorySummary summary = points.isEmpty() ? new HistorySummary(null, null, null)
                : new HistorySummary(points.stream().mapToLong(HistoryPoint::lowest).min().getAsLong(),
                        Math.round(points.stream().mapToLong(HistoryPoint::lowest).average().orElse(0)),
                        points.stream().mapToLong(HistoryPoint::lowest).max().getAsLong());
        return new PriceHistory(productId, period == null ? "1m" : period, points, summary);
    }

    /** 저장된 최신값 기준 요약 (외부 호출 없음). 순서는 productIds 순서 */
    public List<ProductSummary> summaries(List<Long> productIds) {
        if (productIds.isEmpty()) return List.of();
        OfferPricer.Context ctx = pricer.context();
        Map<Long, List<StoredOffer>> byProduct = repository.findByProductIds(productIds).stream()
                .collect(Collectors.groupingBy(StoredOffer::productId, LinkedHashMap::new, Collectors.toList()));
        List<ProductSummary> out = new ArrayList<>();
        for (Long id : productIds) {
            List<StoredOffer> list = byProduct.get(id);
            if (list == null || list.isEmpty()) continue;
            List<PricedOffer> priced = PriceOutliers.mark(
                    list.stream().map(s -> pricer.price(OfferData.of(s, ctx.countryOf(s.mallId())), ctx, false, false))
                            .toList());
            StoredOffer meta = list.getFirst();
            out.add(views.summary(new ProductGroup(id, meta.productName(), meta.imageUrl(), meta.matchKey(), priced),
                    ctx, null, meta, 3));
        }
        return out;
    }
}
