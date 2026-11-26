package com.teamsoda.globalprice.search;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.teamsoda.globalprice.catalog.MallDirectory;
import com.teamsoda.globalprice.catalog.MallDirectory.Mall;
import com.teamsoda.globalprice.catalog.ModelCode;
import com.teamsoda.globalprice.catalog.OfferPricer;
import com.teamsoda.globalprice.catalog.Offers.OfferData;
import com.teamsoda.globalprice.catalog.Offers.PriceStatus;
import com.teamsoda.globalprice.catalog.Offers.PricedOffer;
import com.teamsoda.globalprice.catalog.Offers.ProductGroup;
import com.teamsoda.globalprice.catalog.OutlierService;
import com.teamsoda.globalprice.catalog.ViewFactory;
import com.teamsoda.globalprice.catalog.Views.ProductSummary;
import com.teamsoda.globalprice.catalog.Views.RatesView;
import com.teamsoda.globalprice.config.GlobalPriceProperties;
import com.teamsoda.globalprice.exception.BusinessException;
import com.teamsoda.globalprice.exception.ErrorCode;
import com.teamsoda.globalprice.search.FacetBuilder.FacetFilter;
import com.teamsoda.globalprice.search.FacetBuilder.Facets;
import com.teamsoda.globalprice.search.SourceCaller.CallStatus;
import com.teamsoda.globalprice.search.SourceCaller.SourceResult;
import com.teamsoda.globalprice.source.MallSource;
import com.teamsoda.globalprice.source.MallSource.StockStatus;
import com.teamsoda.globalprice.source.SourceException;

import jakarta.annotation.PreDestroy;

/**
 * 검색 (명세서 2-1 · 6-2) 흐름만 둔다: 검색어 정규화 → 소스 동시 호출(가상 스레드) → 걸러내기 · 저장 → 모델코드 일치
 * → 계산 → 묶기 → 이상 가격({@link OutlierService}) → 필터 → 정렬({@link SearchRanker}) → 체크표({@link FacetBuilder})
 * → 페이지. 일부 소스 실패는 200 + sources 상태로 응답한다.
 */
@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    public enum Sort {
        price_asc, discount_desc;

        /** 프론트 값(total · discount · popular · rating)도 받는다. 인기 · 평점순은 1차 미지원 → 가격순 */
        public static Sort parse(String s) {
            if (s == null || s.isBlank()) return price_asc;
            return switch (s.toLowerCase(Locale.ROOT)) {
                case "price_asc", "total", "popular", "rating" -> price_asc;
                case "discount_desc", "discount" -> discount_desc;
                default -> throw new BusinessException(ErrorCode.INVALID_INPUT, "지원하지 않는 정렬이에요: " + s);
            };
        }
    }

    public record SearchRequest(String q, Set<String> stores, Set<String> countries, Long minPrice, Long maxPrice,
                                boolean freeShipping, boolean onSale, boolean inStock, boolean taxIncluded,
                                boolean coupon, Sort sort, int page, int size) {
    }

    public record SourceStatusView(long mallId, String mallName, String storeId, String status, int count) {
    }

    public record SearchResponse(String query, String sourceQuery, long total, LocalDateTime ratesUpdatedAt,
                                 List<ProductSummary> items, List<SourceStatusView> sources, int page, int size,
                                 long totalElements, int totalPages, boolean fromCache, int limitedToTopN,
                                 List<String> suggestions, List<String> modelCodes, int modelCodeExcluded,
                                 Facets facets) {
    }

    private final QueryNormalizer normalizer;
    private final MallDirectory malls;
    private final SourceCaller caller;
    private final OfferPricer pricer;
    private final ProductGrouper grouper;
    private final OutlierService outliers;
    private final ViewFactory views;
    private final Duration sourceTimeout;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public SearchService(QueryNormalizer normalizer, MallDirectory malls, SourceCaller caller, OfferPricer pricer,
                         ProductGrouper grouper, OutlierService outliers, ViewFactory views,
                         GlobalPriceProperties props) {
        this.normalizer = normalizer;
        this.malls = malls;
        this.caller = caller;
        this.pricer = pricer;
        this.grouper = grouper;
        this.outliers = outliers;
        this.views = views;
        // 연결 + 읽기 시간 + 저장 여유. 넘으면 그 소스는 timeout 으로 표시 (작업은 뒤에서 계속돼 캐시에 남음)
        this.sourceTimeout = props.search().connectTimeout().plus(props.search().readTimeout()).plusSeconds(1);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    public SearchResponse search(SearchRequest req) {
        return search(req, FacetFilter.NONE);
    }

    public SearchResponse search(SearchRequest req, FacetFilter facetFilter) {
        QueryNormalizer.Query query = normalizer.normalize(req.q());
        OfferPricer.Context ctx = pricer.context();

        // 1 · 2. 소스 고르기 → 동시 호출
        List<SourceStatusView> statuses = new ArrayList<>();
        List<SourceResult> results = callSources(req, query, ctx, statuses);

        // 3. 계산 (환율 없는 통화는 제외). 검색어에 모델코드가 있으면 그 코드가 상품명에 있는 리스팅만
        List<String> codes = ModelCode.inQuery(req.q());
        int codeExcluded = 0;
        List<PricedOffer> priced = new ArrayList<>();
        for (SourceResult r : results) {
            statuses.add(new SourceStatusView(r.mall().mallId(), r.mall().mallName(), r.source().storeKey(),
                    r.status().name(), r.offers().size()));
            for (OfferData d : r.offers()) {
                if (!ModelCode.matches(d.listingTitle() + " " + (d.variantName() == null ? "" : d.variantName()), codes)) {
                    codeExcluded++;
                    continue;
                }
                PricedOffer p = pricer.price(d, ctx, req.coupon(), true);
                if (p.status() != PriceStatus.unavailable) priced.add(p);
            }
        }

        // 4. 묶기 → 이상 가격 → 필터 → 정렬
        List<ProductGroup> unsorted = outliers.judge(grouper.group(priced), ctx).stream()
                .map(g -> filterOffers(g, req))
                .filter(g -> !g.offers().isEmpty())
                .filter(g -> priceInRange(g, req))
                .toList();
        List<ProductGroup> all = unsorted.stream()
                .sorted(req.sort() == Sort.price_asc
                        ? SearchRanker.relevance(query.sourceQuery(), req.q(), unsorted)
                                .thenComparing(SearchRanker.order(req.sort(), ctx))
                        : SearchRanker.order(req.sort(), ctx))
                .toList();

        // 4-1. 상단 체크표: 체크 전 결과로 칸을 만들고, 체크한 값으로 거른다
        FacetBuilder.Result faceted = FacetBuilder.apply(all, facetFilter);
        List<ProductGroup> groups = faceted.groups();

        boolean anyOk = results.stream().anyMatch(r -> r.status() == CallStatus.ok);
        // 호출한 소스가 하나도 없으면(필터로 고른 판매처에 연결된 API 가 없음) 오류가 아니라 빈 결과
        if (groups.isEmpty() && !anyOk && !results.isEmpty()) {
            boolean allBadFormat = results.stream()
                    .allMatch(r -> r.error() instanceof SourceException.BadResponse b && b.httpStatus() == null);
            throw new BusinessException(allBadFormat ? ErrorCode.EXTERNAL_INVALID_RESPONSE
                    : ErrorCode.EXTERNAL_API_UNAVAILABLE);
        }

        // 5. 페이지
        int size = Math.min(Math.max(req.size(), 1), 50);
        int page = Math.max(req.page(), 0);
        long total = groups.size();
        int totalPages = (int) ((total + size - 1) / size);
        List<ProductSummary> items = groups.stream().skip((long) page * size).limit(size)
                .map(g -> views.summary(g, ctx, null, null, 3)).toList();

        RatesView rates = views.rates(ctx.fx());
        boolean fromCache = !results.isEmpty() && results.stream()
                .filter(r -> r.status() == CallStatus.ok).allMatch(SourceResult::fromCache) && anyOk;
        return new SearchResponse(query.normalized(), query.sourceQuery(), total, rates.updatedAt(), items, statuses,
                page, size, total, totalPages, fromCache, caller.perSourceLimit(),
                total == 0 ? suggestions(query) : List.of(), codes, codeExcluded, faceted.facets());
    }

    /** 호출할 소스를 골라 동시에 부른다. 꺼진 소스는 statuses 에 skipped 로 남긴다 */
    private List<SourceResult> callSources(SearchRequest req, QueryNormalizer.Query query, OfferPricer.Context ctx,
                                           List<SourceStatusView> statuses) {
        record Target(MallSource source, Mall mall) {
        }
        List<Target> targets = new ArrayList<>();
        for (MallSource s : malls.sources()) {
            Mall mall = ctx.malls().values().stream()
                    .filter(m -> s.sourceName().equals(m.sourceName()) && m.active()).findFirst().orElse(null);
            if (mall == null) continue;   // api_sources 에 연결된 쇼핑몰이 없음
            if (!req.stores().isEmpty() && !req.stores().contains(s.storeKey())
                    && !req.stores().contains(String.valueOf(mall.mallId()))) {
                continue;
            }
            if (!s.enabled()) {
                statuses.add(new SourceStatusView(mall.mallId(), mall.mallName(), s.storeKey(), "skipped", 0));
                continue;
            }
            targets.add(new Target(s, mall));
        }
        List<Future<SourceResult>> futures = targets.stream()
                .map(t -> executor.submit(() -> caller.search(t.source(), t.mall(), query.sourceQuery(), ctx)))
                .toList();
        List<SourceResult> results = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            Target t = targets.get(i);
            try {
                results.add(futures.get(i).get(sourceTimeout.toMillis(), TimeUnit.MILLISECONDS));
            } catch (TimeoutException e) {
                log.warn("[{}] 검색 시간 초과 ({}ms)", t.source().sourceName(), sourceTimeout.toMillis());
                results.add(new SourceResult(t.source(), t.mall(), CallStatus.timeout, List.of(), false,
                        new SourceException.Timeout("timeout")));
            } catch (ExecutionException e) {
                log.error("[{}] 검색 실패", t.source().sourceName(), e.getCause());
                results.add(new SourceResult(t.source(), t.mall(), CallStatus.error, List.of(), false, null));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException(ErrorCode.INTERNAL_ERROR);
            }
        }
        return results;
    }

    private static ProductGroup filterOffers(ProductGroup g, SearchRequest req) {
        List<PricedOffer> kept = g.offers().stream().filter(o -> {
            var b = o.applied();
            if (req.freeShipping() && !(b.shippingKnown() && b.shippingFeeKrw() == 0)) return false;
            if (req.onSale() && b.appliedEvent() == null) return false;
            if (req.inStock() && o.data().stock() != StockStatus.IN_STOCK) return false;
            if (req.taxIncluded()) return false;   // 1차: 관부가세 포함가 판매처 없음
            if (!req.countries().isEmpty()) {
                // "KRW" = 국내 직구관(원화 결제 · KR 쇼핑몰). 국내 직구관의 출발국은 KR
                boolean krw = "KRW".equals(o.data().currency()) && "KR".equalsIgnoreCase(o.data().origin());
                String country = o.data().origin();
                if (!(krw && req.countries().contains("KRW"))
                        && (country == null || !req.countries().contains(country.toUpperCase(Locale.ROOT)))) {
                    return false;
                }
            }
            return true;
        }).toList();
        return g.withOffers(kept);
    }

    private static boolean priceInRange(ProductGroup g, SearchRequest req) {
        if (req.minPrice() == null && req.maxPrice() == null) return true;
        PricedOffer best = g.best();
        if (best == null || best.total() == null) return false;
        if (req.minPrice() != null && best.total() < req.minPrice()) return false;
        return req.maxPrice() == null || best.total() <= req.maxPrice();
    }

    /** 결과 0건일 때 안내: 마지막 단어를 뺀 검색어, 영어 변환 검색어 */
    static List<String> suggestions(QueryNormalizer.Query q) {
        List<String> out = new ArrayList<>();
        String[] words = q.normalized().split(" ");
        if (words.length > 1) out.add(String.join(" ", Arrays.copyOf(words, words.length - 1)));
        if (!q.sourceQuery().equalsIgnoreCase(q.normalized())) out.add(q.sourceQuery());
        return out.stream().distinct().toList();
    }
}
