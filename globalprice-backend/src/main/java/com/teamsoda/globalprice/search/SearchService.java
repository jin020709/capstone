package com.teamsoda.globalprice.search;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.teamsoda.globalprice.catalog.MallDirectory;
import com.teamsoda.globalprice.catalog.ModelCode;
import com.teamsoda.globalprice.catalog.PriceOutliers;
import com.teamsoda.globalprice.catalog.ProductMatcher;
import com.teamsoda.globalprice.catalog.MallDirectory.Mall;
import com.teamsoda.globalprice.catalog.OfferPricer;
import com.teamsoda.globalprice.catalog.Offers.OfferData;
import com.teamsoda.globalprice.catalog.Offers.PriceStatus;
import com.teamsoda.globalprice.catalog.Offers.PricedOffer;
import com.teamsoda.globalprice.catalog.Offers.ProductGroup;
import com.teamsoda.globalprice.catalog.ViewFactory;
import com.teamsoda.globalprice.catalog.Views.ProductSummary;
import com.teamsoda.globalprice.catalog.Views.RatesView;
import com.teamsoda.globalprice.config.GlobalPriceProperties;
import com.teamsoda.globalprice.exception.BusinessException;
import com.teamsoda.globalprice.exception.ErrorCode;
import com.teamsoda.globalprice.search.SourceCaller.CallStatus;
import com.teamsoda.globalprice.search.SourceCaller.SourceResult;
import com.teamsoda.globalprice.source.MallSource;
import com.teamsoda.globalprice.source.MallSource.StockStatus;
import com.teamsoda.globalprice.source.SourceException;

import jakarta.annotation.PreDestroy;

/**
 * 검색 (명세서 2-1 · 6-2): 검색어 정규화 → 소스 동시 호출(가상 스레드) → 걸러내기 · 저장 → 모델코드 일치 → 계산 → 묶기
 * → 필터 → 정렬 → 페이지. 일부 소스 실패는 200 + sources 상태로 응답한다.
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

    /** 다나와식 상단 체크표에서 고른 값 (비어 있으면 거르지 않음). 값은 소문자 */
    public record FacetFilter(Set<String> brands, Set<String> models, Set<String> storages, Set<String> colors) {
        public static final FacetFilter NONE = new FacetFilter(Set.of(), Set.of(), Set.of(), Set.of());
    }

    /** 체크표 한 칸: value 는 다시 보낼 값, count 는 그 값을 가진 상품 수 */
    public record FacetOption(String value, String label, int count) {
    }

    /** 이번 검색 결과에 실제로 있는 제조사 · 모델 · 용량 · 색상 (상품명에서 자동으로 뽑음, 체크하기 전 기준) */
    public record Facets(List<FacetOption> brands, List<FacetOption> models, List<FacetOption> storages,
                         List<FacetOption> colors) {
    }

    public record SearchResponse(String query, String sourceQuery, long total, java.time.LocalDateTime ratesUpdatedAt,
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
    private final ViewFactory views;
    private final Duration sourceTimeout;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public SearchService(QueryNormalizer normalizer, MallDirectory malls, SourceCaller caller, OfferPricer pricer,
                         ProductGrouper grouper, ViewFactory views, GlobalPriceProperties props) {
        this.normalizer = normalizer;
        this.malls = malls;
        this.caller = caller;
        this.pricer = pricer;
        this.grouper = grouper;
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

        // 1. 호출할 소스 고르기
        record Target(MallSource source, Mall mall) {
        }
        List<Target> targets = new ArrayList<>();
        List<SourceStatusView> statuses = new ArrayList<>();
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

        // 2. 동시 호출
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

        // 4. 묶기 → 필터 → 정렬
        List<ProductGroup> unsorted = familyOutliers(grouper.group(priced).stream()
                .map(g -> g.withOffers(PriceOutliers.mark(g.offers())))
                .toList()).stream()
                .map(g -> filterOffers(g, req, ctx))
                .filter(g -> !g.offers().isEmpty())
                .filter(g -> priceInRange(g, req))
                .toList();
        List<ProductGroup> all = unsorted.stream()
                .sorted(req.sort() == Sort.price_asc
                        ? relevance(query.sourceQuery(), req.q(), unsorted).thenComparing(order(req.sort(), ctx))
                        : order(req.sort(), ctx))
                .toList();

        // 4-1. 상단 체크표: 체크 전 결과로 칸을 만들고, 체크한 값으로 거른다
        Map<Long, FacetInfo> infos = new HashMap<>();
        for (ProductGroup g : all) infos.put(g.productId(), FacetInfo.of(g));
        Facets facets = facets(all, infos);
        List<ProductGroup> groups = applyFacets(all, infos, facetFilter);

        boolean anyOk = results.stream().anyMatch(r -> r.status() == CallStatus.ok);
        // 호출한 소스가 하나도 없으면(필터로 고른 판매처에 연결된 API 가 없음) 오류가 아니라 빈 결과
        if (groups.isEmpty() && !anyOk && !results.isEmpty()) {
            boolean allBadFormat = !results.isEmpty() && results.stream()
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
                total == 0 ? suggestions(query) : List.of(), codes, codeExcluded, facets);
    }

    /** 같은 모델의 다른 구성(용량만 다름)들보다 터무니없이 싼 상품 → 이상 가격 (판매처가 1곳뿐이어도 잡힘) */
    static List<ProductGroup> familyOutliers(List<ProductGroup> groups) {
        Map<Long, String> model = new HashMap<>();
        Map<Long, String> seriesOf = new HashMap<>();
        Map<Long, Long> best = new HashMap<>();
        for (ProductGroup g : groups) {
            String m0 = FacetInfo.of(g).model();
            model.put(g.productId(), m0);
            // 제목에 용량이 없어 모델로 안 묶인 리스팅도 시리즈는 알아낸다 ("Galaxy Z Fold 5 5G F946 ...")
            ProductMatcher.Config qc = m0 != null ? null : ProductMatcher.queryConfig(g.productName());
            String sr = m0 != null ? series(m0) : qc == null ? null : series(qc.family());
            if (sr != null) seriesOf.put(g.productId(), sr);
            g.offers().stream().filter(PricedOffer::comparable).mapToLong(PricedOffer::total).min()
                    .ifPresent(v -> best.put(g.productId(), v));
        }
        List<ProductGroup> out = new ArrayList<>(groups.size());
        for (ProductGroup g : groups) {
            String m = model.get(g.productId());
            String s = seriesOf.get(g.productId());
            if (s == null) { out.add(g); continue; }
            List<Long> others = m == null ? List.of() : groups.stream()
                    .filter(x -> x.productId() != g.productId() && m.equals(model.get(x.productId())))
                    .map(x -> best.get(x.productId())).toList();
            Long ref = PriceOutliers.familyReference(others);
            if (ref != null) {
                out.add(g.withOffers(PriceOutliers.markBelow(g.offers(), ref)));
                continue;
            }
            // 다른 구성이 없으면 같은 시리즈(세대만 다름: Z Fold2 ~ Fold7)와 비교, 기준은 1/5 로 느슨하게
            // 기준은 모델이 확실한(용량까지 잡힌) 상품만
            List<Long> sameSeries = groups.stream()
                    .filter(x -> x.productId() != g.productId() && model.get(x.productId()) != null
                            && s.equals(seriesOf.get(x.productId())))
                    .map(x -> best.get(x.productId())).filter(java.util.Objects::nonNull).toList();
            Long seriesRef = sameSeries.size() >= PriceOutliers.MIN_SERIES ? PriceOutliers.familyReference(sameSeries) : null;
            out.add(seriesRef == null ? g
                    : g.withOffers(PriceOutliers.markBelow(g.offers(), seriesRef, PriceOutliers.SERIES_RATIO)));
        }
        return out;
    }

    /** 모델 키에서 세대를 뺀 시리즈 (galaxy|z fold|5| → galaxy|z fold). 세대 칸이 없는 키는 첫 칸 */
    static String series(String model) {
        String[] p = model.split("\\|");
        return p.length >= 3 ? p[0] + "|" + p[1] : p[0];
    }

    /** 상품 하나의 체크표 값 (묶기 키에서). 모델 규칙이 없는 상품은 brand · model · storage 가 null */
    record FacetInfo(String brand, String model, String modelLabel, Integer storageGb) {
        private static final java.util.regex.Pattern TAIL = java.util.regex.Pattern.compile(
                "(\\s\\d+\\s?(GB|TB))+(\\sWi-Fi.*)?$");

        /** 저장할 때 정한 묶기 키(products.match_key)로 판단 (상품명을 다시 해석하지 않음) */
        static FacetInfo of(ProductGroup g) {
            ProductMatcher.Config c = g.config();
            String productName = g.productName();
            if (c == null) return new FacetInfo(null, null, null, null);
            String brand = switch (c.family().split("\\|")[0]) {
                case "iphone", "ipad", "macbook" -> "Apple";
                case "galaxy" -> "Samsung";
                case "pixel" -> "Google";
                default -> null;
            };
            return new FacetInfo(brand, c.family(), TAIL.matcher(productName).replaceFirst("").strip(), c.storageGb());
        }
    }

    static Facets facets(List<ProductGroup> groups, Map<Long, FacetInfo> infos) {
        Map<String, int[]> brands = new java.util.LinkedHashMap<>();
        Map<String, String> modelLabels = new java.util.LinkedHashMap<>();
        Map<String, int[]> models = new java.util.LinkedHashMap<>();
        java.util.TreeMap<Integer, int[]> storages = new java.util.TreeMap<>();
        Map<String, int[]> colors = new java.util.LinkedHashMap<>();
        for (ProductGroup g : groups) {
            FacetInfo f = infos.get(g.productId());
            if (f.brand() != null) brands.computeIfAbsent(f.brand(), k -> new int[1])[0]++;
            if (f.model() != null) {
                models.computeIfAbsent(f.model(), k -> new int[1])[0]++;
                modelLabels.putIfAbsent(f.model(), f.modelLabel());
            }
            if (f.storageGb() != null) storages.computeIfAbsent(f.storageGb(), k -> new int[1])[0]++;
            g.offers().stream().map(o -> o.data().color()).filter(java.util.Objects::nonNull).distinct()
                    .forEach(c -> colors.computeIfAbsent(c, k -> new int[1])[0]++);
        }
        Comparator<FacetOption> byCount = Comparator.comparingInt(FacetOption::count).reversed();
        return new Facets(
                brands.entrySet().stream().map(e -> new FacetOption(e.getKey(), e.getKey(), e.getValue()[0]))
                        .sorted(byCount).toList(),
                models.entrySet().stream()
                        .map(e -> new FacetOption(e.getKey(), modelLabels.get(e.getKey()), e.getValue()[0]))
                        .sorted(byCount).toList(),
                storages.entrySet().stream().map(e -> new FacetOption(String.valueOf(e.getKey()),
                        e.getKey() >= 1024 && e.getKey() % 1024 == 0 ? e.getKey() / 1024 + "TB" : e.getKey() + "GB",
                        e.getValue()[0])).toList(),
                colors.entrySet().stream().map(e -> new FacetOption(e.getKey(), e.getKey(), e.getValue()[0]))
                        .sorted(byCount).toList());
    }

    /** 칸끼리는 AND, 한 칸 안에서 여러 개를 고르면 OR. 색상을 고르면 그 색상 판매처만 남김 (가격도 그 색상 기준) */
    static List<ProductGroup> applyFacets(List<ProductGroup> groups, Map<Long, FacetInfo> infos, FacetFilter f) {
        if (f == null) return groups;
        List<ProductGroup> out = new ArrayList<>();
        for (ProductGroup g : groups) {
            FacetInfo i = infos.get(g.productId());
            if (!f.brands().isEmpty() && (i.brand() == null || !f.brands().contains(i.brand().toLowerCase(Locale.ROOT)))) continue;
            if (!f.models().isEmpty() && (i.model() == null || !f.models().contains(i.model()))) continue;
            if (!f.storages().isEmpty() && (i.storageGb() == null || !f.storages().contains(String.valueOf(i.storageGb())))) continue;
            if (!f.colors().isEmpty()) {
                List<PricedOffer> kept = g.offers().stream().filter(o -> {
                    String c = o.data().color();
                    return c != null && f.colors().contains(c.toLowerCase(Locale.ROOT));
                }).toList();
                if (kept.isEmpty()) continue;
                g = g.withOffers(kept);
            }
            out.add(g);
        }
        return out;
    }

    private ProductGroup filterOffers(ProductGroup g, SearchRequest req, OfferPricer.Context ctx) {
        List<PricedOffer> kept = g.offers().stream().filter(o -> {
            var b = o.applied();
            if (req.freeShipping() && !(b.shippingKnown() && b.shippingFeeKrw() == 0)) return false;
            if (req.onSale() && b.appliedEvent() == null) return false;
            if (req.inStock() && o.data().stock() != StockStatus.IN_STOCK) return false;
            if (req.taxIncluded()) return false;   // 1차: 관부가세 포함가 판매처 없음
            if (!req.countries().isEmpty()) {
                Mall mall = ctx.malls().get(o.data().mallId());
                String country = o.data().origin();
                boolean krw = "KRW".equals(o.data().currency()) && mall != null
                        && "KR".equalsIgnoreCase(mall.countryCode());   // 국내 직구관만
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

    /** 예상 구매비용 오름차순 → shipDays → 쇼핑몰 이름. 품절은 맨 아래. discount_desc 는 할인율 높은 순 */
    /**
     * 다나와식 검색 순서: 검색한 모델과 정확히 같은 상품 → 같은 모델 다른 용량 → 비슷한 모델(Pro Max · 울트라 등) → 나머지.
     * 같은 단계 안에서는 모델 · 용량 순으로 모아 둔다 (256GB 다음에 512GB). 그다음 기준은 가격순.
     * 검색어에서 모델을 못 알아내면 순서를 바꾸지 않는다.
     */
    /**
     * 검색어와 얼마나 맞는지 (가격순 정렬 앞에 적용). 다나와처럼 "이름이 정확히 일치하는 상품이 맨 위, 덜 맞을수록 아래".
     * <ol>
     *   <li>묶기 규칙이 있는 모델(아이폰 · 갤럭시 · 픽셀 · 맥북 · 아이패드)이 검색어와 같은 모델 → 맨 위 (용량까지 같으면 더 위)</li>
     *   <li>나머지는 {@link #textScore} 점수 순 (0.1 단위로 끊어서, 비슷한 점수끼리는 가격순)</li>
     * </ol>
     * 같은 단계 안에서는 모델 · 용량 순으로 모은다 (256 → 512 → 1TB).
     */
    static Comparator<ProductGroup> relevance(String sourceQuery, String rawQuery) {
        return relevance(sourceQuery, rawQuery, List.of());
    }

    /**
     * groups 를 주면 같은 단계 안에서 모델 묶음끼리는 "그 모델의 최저가" 순으로 놓는다
     * (가나다순이면 Fold2 → Fold4 → Fold8 처럼 구형이 위로 옴). 가격이 없는 상품(이상 가격 · 조회 불가)은 맨 아래.
     */
    static Comparator<ProductGroup> relevance(String sourceQuery, String rawQuery, List<ProductGroup> groups) {
        ProductMatcher.Config q = ProductMatcher.queryConfig(sourceQuery);
        if (q == null) q = ProductMatcher.queryConfig(rawQuery);
        final ProductMatcher.Config want = q;
        List<String> qTokens = tokens(sourceQuery == null || sourceQuery.isBlank() ? rawQuery : sourceQuery);
        java.util.Map<Long, ProductMatcher.Config> cache = new java.util.HashMap<>();
        java.util.function.Function<ProductGroup, ProductMatcher.Config> conf = g -> cache.computeIfAbsent(g.productId(),
                id -> g.config());
        java.util.Map<Long, Integer> rankCache = new java.util.HashMap<>();
        java.util.function.ToIntFunction<ProductGroup> rank = g -> rankCache.computeIfAbsent(g.productId(), id -> {
            ProductMatcher.Config c = conf.apply(g);
            if (want != null && c != null && c.family().equals(want.family())) {
                return want.storageGb() == null || want.storageGb().equals(c.storageGb()) ? 0 : 1;
            }
            // 2(완전 일치) ~ 12(전혀 안 맞음)
            return 2 + (int) Math.round((1.0 - textScore(qTokens, g.productName())) * 10);
        });
        java.util.function.ToLongFunction<ProductGroup> best = g -> g.offers().stream()
                .filter(PricedOffer::comparable).mapToLong(PricedOffer::total).min().orElse(Long.MAX_VALUE);
        java.util.Map<String, Long> familyMin = new java.util.HashMap<>();
        for (ProductGroup g : groups) {
            ProductMatcher.Config c = conf.apply(g);
            if (c != null) familyMin.merge(c.family() + "#" + rank.applyAsInt(g), best.applyAsLong(g), Math::min);
        }
        return Comparator.comparingInt((ProductGroup g) -> best.applyAsLong(g) == Long.MAX_VALUE ? 1 : 0)
                .thenComparingInt(rank)
                .thenComparingLong(g -> {
                    ProductMatcher.Config c = conf.apply(g);
                    return c == null ? best.applyAsLong(g)
                            : familyMin.getOrDefault(c.family() + "#" + rank.applyAsInt(g), best.applyAsLong(g));
                })
                .thenComparing(g -> {
                    ProductMatcher.Config c = conf.apply(g);
                    return c == null ? "~" : c.family();
                })
                .thenComparingLong(g -> {
                    ProductMatcher.Config c = conf.apply(g);
                    return c == null ? 0L : c.order();
                });
    }

    /** 모델을 가르는 말: 검색어에 없는데 상품명에 있으면 다른 모델일 가능성이 큼 (fold 검색 → Flip, iphone 17 → 17e) */
    private static final java.util.Set<String> VARIANT = java.util.Set.of(
            "pro", "max", "plus", "ultra", "mini", "air", "flip", "fold", "fe", "lite", "se", "xl", "edge", "slim", "e");
    /** 상품명에 흔히 붙지만 어떤 상품인지와 무관한 말 (정밀도 계산에서 뺌) */
    private static final java.util.Set<String> NOISE = java.util.Set.of(
            "apple", "samsung", "google", "sony", "new", "unlocked", "dual", "sim", "esim", "physical", "5g", "4g", "lte",
            "wifi", "wi", "fi", "cellular", "the", "and", "with", "in", "edition", "version", "global", "gb", "tb", "inch");

    /**
     * 검색어 단어가 상품명에 얼마나 들어 있는지 0~1.
     * <ul>
     *   <li>포함률: 검색어 단어 중 상품명에 있는 비율 (가장 중요)</li>
     *   <li>정밀도: 상품명이 검색어 말고 다른 말을 얼마나 덧붙였는지 (짧고 딱 맞을수록 높음)</li>
     *   <li>다른 모델 감점: 검색어에 없는 모델 구분 말(Flip · Pro · Max · e …)이 붙으면 하나당 0.3</li>
     *   <li>다른 세대 감점: 검색어의 모델 번호가 상품명에 없으면 하나당 0.2</li>
     * </ul>
     */
    static double textScore(List<String> qTokens, String name) {
        if (qTokens.isEmpty()) return 1.0;
        List<String> n = tokens(name);
        java.util.Set<String> nSet = new java.util.HashSet<>(n);
        java.util.Set<String> qSet = new java.util.HashSet<>(qTokens);
        long matched = qSet.stream().filter(nSet::contains).count();
        double coverage = (double) matched / qSet.size();
        long extra = nSet.stream().filter(t -> !qSet.contains(t) && !NOISE.contains(t) && !t.matches("\\d+(gb|tb)?")).count();
        double precision = matched == 0 ? 0 : (double) matched / (matched + Math.min(extra, 10));
        long otherModel = nSet.stream().filter(t -> VARIANT.contains(t) && !qSet.contains(t)).count();
        // 검색어의 모델 번호(17, 7, 25 …)가 상품명에 없으면 다른 세대 → 한 번 더 감점 (Flip 7 검색 → Flip6)
        long missingNumber = qSet.stream().filter(t -> t.matches("\\d{1,4}") && !nSet.contains(t)).count();
        double s = coverage * (0.6 + 0.4 * precision) - 0.3 * otherModel - 0.2 * missingNumber;
        return Math.max(0, Math.min(1, s));
    }

    /** 소문자 · 글자와 숫자 사이를 띄움 (fold7 → fold 7, 17e → 17 e, s25 → s 25) · 기호로 나눔 */
    static List<String> tokens(String s) {
        if (s == null) return List.of();
        String t = s.toLowerCase(Locale.ROOT)
                .replaceAll("(\\d+)\\s*(gb|tb)\\b", "$1$2")              // 256 GB → 256gb (용량은 한 덩어리)
                .replaceAll("([a-z])(\\d)", "$1 $2")
                .replaceAll("(\\d)([a-z])", "$1 $2")
                .replaceAll("(\\d+) (gb|tb)\\b", "$1$2");
        List<String> out = new ArrayList<>();
        for (String w : t.split("[^a-z0-9가-힣]+")) if (!w.isEmpty()) out.add(w);
        return out;
    }

    static Comparator<ProductGroup> order(Sort sort, OfferPricer.Context ctx) {
        Comparator<ProductGroup> soldOutLast = Comparator.comparingInt(g -> g.best().comparable() ? 0 : 1);
        Comparator<ProductGroup> byPrice = Comparator
                .comparingLong((ProductGroup g) -> g.best().total())
                .thenComparingInt(g -> g.best().data().shipDays() == null ? Integer.MAX_VALUE
                        : g.best().data().shipDays())
                .thenComparing(g -> {
                    Mall m = ctx.malls().get(g.best().data().mallId());
                    return m == null ? "" : m.mallName();
                });
        if (sort == Sort.discount_desc) {
            Comparator<ProductGroup> byDiscount = Comparator.comparingDouble((ProductGroup g) -> {
                PricedOffer b = g.best();
                long normal = b.normal().totalAmountKrw();
                return normal == 0 ? 0 : -(double) (normal - b.total()) / normal;
            });
            return soldOutLast.thenComparing(byDiscount).thenComparing(byPrice);
        }
        return soldOutLast.thenComparing(byPrice);
    }

    /** 결과 0건일 때 안내: 마지막 단어를 뺀 검색어, 영어 변환 검색어 */
    static List<String> suggestions(QueryNormalizer.Query q) {
        List<String> out = new ArrayList<>();
        String[] words = q.normalized().split(" ");
        if (words.length > 1) {
            out.add(String.join(" ", java.util.Arrays.copyOf(words, words.length - 1)));
        }
        if (!q.sourceQuery().equalsIgnoreCase(q.normalized())) out.add(q.sourceQuery());
        return out.stream().distinct().collect(Collectors.toList());
    }
}
