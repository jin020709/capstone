package com.teamsoda.globalprice.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.teamsoda.globalprice.catalog.CatalogService;
import com.teamsoda.globalprice.catalog.MallDirectory;
import com.teamsoda.globalprice.catalog.MallDirectory.Mall;
import com.teamsoda.globalprice.catalog.OfferPricer;
import com.teamsoda.globalprice.catalog.Offers.OfferData;
import com.teamsoda.globalprice.catalog.ViewFactory;
import com.teamsoda.globalprice.catalog.Views.ProductSummary;
import com.teamsoda.globalprice.common.SyncLogRepository;
import com.teamsoda.globalprice.config.AppConfig;
import com.teamsoda.globalprice.config.GlobalPriceProperties;
import com.teamsoda.globalprice.exception.BusinessException;
import com.teamsoda.globalprice.exception.ErrorCode;
import com.teamsoda.globalprice.pricing.PricingModels.FeeRule;
import com.teamsoda.globalprice.pricing.PricingModels.FxRate;
import com.teamsoda.globalprice.pricing.PricingModels.FxTable;
import com.teamsoda.globalprice.pricing.PricingModels.PricingRules;
import com.teamsoda.globalprice.pricing.PricingModels.TaxRule;
import com.teamsoda.globalprice.pricing.RuleRepository;
import com.teamsoda.globalprice.rate.ExchangeRateService;
import com.teamsoda.globalprice.search.SearchService.SearchRequest;
import com.teamsoda.globalprice.search.SearchService.SearchResponse;
import com.teamsoda.globalprice.search.SearchService.Sort;
import com.teamsoda.globalprice.source.MallSource;
import com.teamsoda.globalprice.source.SourceException;
import com.teamsoda.globalprice.source.SourceGuard;
import com.teamsoda.globalprice.support.TestProps;

/** 가짜 소스 3개로 검색 서비스 검증 (DB 없음: 저장은 가짜 CatalogService) */
class SearchServiceTest {

    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 14, 12, 0);

    /** 동작을 바꿀 수 있는 가짜 소스 */
    static class FakeSource implements MallSource {
        final String name;
        final AtomicInteger calls = new AtomicInteger();
        Supplier<List<MallOffer>> behavior = List::of;
        int budget = 1000;

        FakeSource(String name) {
            this.name = name;
        }

        @Override public String sourceName() { return name; }
        @Override public String storeKey() { return name.toLowerCase(); }
        @Override public boolean enabled() { return true; }
        @Override public int dailyBudget() { return budget; }

        @Override
        public List<MallOffer> search(String query, SearchOptions options) {
            calls.incrementAndGet();
            return behavior.get();
        }

        @Override
        public List<MallOffer> refresh(List<String> ids) {
            return List.of();
        }
    }

    FakeSource a = new FakeSource("A");
    FakeSource b = new FakeSource("B");
    FakeSource c = new FakeSource("C");
    SearchService service;

    @BeforeEach
    void setUp() {
        GlobalPriceProperties props = TestProps.props("http://unused", Duration.ofMillis(200));   // 소스 제한 3.2초
        Clock clock = Clock.fixed(NOW.atZone(ZoneId.of("Asia/Seoul")).toInstant(), ZoneId.of("Asia/Seoul"));

        Map<Long, Mall> malls = Map.of(
                1L, new Mall(1, "A Mall", "US", true, "A"),
                2L, new Mall(2, "B Mall", "CN", true, "B"),
                3L, new Mall(3, "C Mall", "US", true, "C"));
        MallDirectory directory = mock(MallDirectory.class);
        when(directory.sources()).thenReturn(List.of(a, b, c));
        when(directory.all()).thenReturn(malls);
        when(directory.storeKey(any())).thenAnswer(inv -> ((Mall) inv.getArgument(0)).sourceName().toLowerCase());

        ExchangeRateService rates = mock(ExchangeRateService.class);
        when(rates.currentTable()).thenReturn(new FxTable(Map.of(
                "USD", new FxRate(1L, "USD", new BigDecimal("1400"), null, LocalDate.of(2026, 11, 13), NOW))));
        RuleRepository rules = mock(RuleRepository.class);
        when(rules.activeRules()).thenReturn(new PricingRules(List.of(),
                List.of(new TaxRule(1, null, null, BigDecimal.ZERO, new BigDecimal("0.1"), new BigDecimal("150"))),
                List.of(new FeeRule(1, "해외 결제 수수료", new BigDecimal("0.01"), BigDecimal.ZERO)), List.of(), null));
        OfferPricer pricer = new OfferPricer(rates, rules, directory, clock, props);

        // 저장: 받은 순서대로 ID 를 붙여 돌려준다
        CatalogService catalog = mock(CatalogService.class);
        AtomicLong ids = new AtomicLong();
        when(catalog.save(anyLong(), anyList(), any())).thenAnswer(inv -> {
            long mallId = inv.getArgument(0);
            List<MallSource.MallOffer> offers = inv.getArgument(1);
            List<OfferData> out = new ArrayList<>();
            for (MallSource.MallOffer o : offers) {
                long id = ids.incrementAndGet();
                // 실제 저장과 같은 팩토리 (출발국 · 색상 계산 포함)
                out.add(OfferData.of(o, new com.teamsoda.globalprice.catalog.CatalogRepository.SavedIds(
                        id, id, id, o.title(), null), mallId, malls.get(mallId).countryCode(), NOW));
            }
            return out;
        });

        SourceCaller caller = new SourceCaller(new SourceGuard(clock, props), catalog, new ResultFilter(props),
                mock(SyncLogRepository.class), new AppConfig().cacheManager(props), clock, props);
        // 이상 가격 비교용 DB 상품은 없음 (가짜 Repository 는 빈 결과)
        com.teamsoda.globalprice.catalog.OutlierService outliers = new com.teamsoda.globalprice.catalog.OutlierService(
                mock(com.teamsoda.globalprice.catalog.CatalogRepository.class), pricer, props);
        service = new SearchService(new QueryNormalizer(Map.of("아이폰", "iphone")), directory, caller, pricer,
                new SameProductGrouper(), outliers, new ViewFactory(directory, props), props);
    }

    static MallSource.MallOffer offer(String id, String title, String usd, String shipping,
                                      MallSource.StockStatus stock) {
        return new MallSource.MallOffer(id, "DEFAULT", title, null, new BigDecimal(usd), "USD",
                shipping == null ? null : new BigDecimal(shipping), "NEW", null, null, null, stock, null, null);
    }

    static SearchRequest req(String q) {
        return new SearchRequest(q, Set.of(), Set.of(), null, null, false, false, false, false, false, Sort.price_asc,
                0, 20);
    }

    @Test
    void partialFailure_returns200WithSourceStatuses() {
        a.behavior = () -> List.of(offer("a1", "iPhone 17", "120", "0", MallSource.StockStatus.IN_STOCK),
                offer("a2", "iPhone 17 Pro", "100", "0", MallSource.StockStatus.IN_STOCK));
        b.behavior = () -> {
            throw new SourceException.BadResponse("B HTTP 503", 503);
        };
        c.behavior = () -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        };

        SearchResponse r = service.search(req("아이폰 17"));

        assertThat(r.sourceQuery()).isEqualTo("iphone 17");
        assertThat(r.items()).extracting(ProductSummary::name).containsExactly("iPhone 17 Pro", "iPhone 17");
        assertThat(r.sources()).extracting(s -> s.storeId() + ":" + s.status())
                .containsExactlyInAnyOrder("a:ok", "b:error", "c:timeout");
        // $100 → 140,000 + 수수료 1,400 = 141,400 (면세)
        assertThat(r.items().getFirst().bestTotal()).isEqualTo(141_400);
        assertThat(r.limitedToTopN()).isEqualTo(20);
    }

    @Test
    void allSourcesFail_returns503() {
        SourceException.BadResponse e503 = new SourceException.BadResponse("HTTP 503", 503);
        a.behavior = () -> { throw e503; };
        b.behavior = () -> { throw new SourceException.RateLimited("429"); };
        c.behavior = () -> { throw new SourceException.AuthFailed("401", 401); };
        assertThatThrownBy(() -> service.search(req("iphone")))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.EXTERNAL_API_UNAVAILABLE));
    }

    @Test
    void allSourcesBadFormat_returns502() {
        a.behavior = () -> { throw new SourceException.BadResponse("format", null); };
        b.behavior = () -> { throw new SourceException.BadResponse("format", null); };
        c.behavior = () -> { throw new SourceException.BadResponse("format", null); };
        assertThatThrownBy(() -> service.search(req("iphone")))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.EXTERNAL_INVALID_RESPONSE));
    }

    @Test
    void cacheHit_makesNoExternalCalls() {
        a.behavior = () -> List.of(offer("a1", "iPhone 17", "120", "0", MallSource.StockStatus.IN_STOCK));
        SearchResponse first = service.search(req("iphone"));
        SearchResponse second = service.search(req("  iphone  "));   // 정규화 후 같은 검색어
        assertThat(a.calls.get() + b.calls.get() + c.calls.get()).isEqualTo(3);
        assertThat(first.fromCache()).isFalse();
        assertThat(second.fromCache()).isTrue();
        assertThat(second.items()).hasSize(1);
    }

    @Test
    void filtersAccessoriesAndUsed_andSortsSoldOutLast() {
        a.behavior = () -> List.of(
                offer("a1", "iPhone 17 Case", "10", "0", MallSource.StockStatus.IN_STOCK),
                offer("a2", "Used iPhone 17", "300", "0", MallSource.StockStatus.IN_STOCK),
                offer("a3", "iPhone 17 (sold out)", "50", "0", MallSource.StockStatus.OUT_OF_STOCK),
                offer("a4", "iPhone 17", "150", "0", MallSource.StockStatus.IN_STOCK));
        SearchResponse r = service.search(req("iphone"));
        assertThat(r.items()).extracting(ProductSummary::name).containsExactly("iPhone 17", "iPhone 17 (sold out)");

        SearchRequest inStock = new SearchRequest("iphone", Set.of(), Set.of(), null, null, false, false, true, false,
                false, Sort.price_asc, 0, 20);
        assertThat(service.search(inStock).items()).extracting(ProductSummary::name).containsExactly("iPhone 17");
    }

    @Test
    void countryAndPriceAndStoreFilters() {
        a.behavior = () -> List.of(offer("a1", "Phone US", "100", "0", MallSource.StockStatus.IN_STOCK));
        b.behavior = () -> List.of(offer("b1", "Phone CN", "50", "0", MallSource.StockStatus.IN_STOCK));
        SearchRequest cn = new SearchRequest("phone", Set.of(), Set.of("CN"), null, null, false, false, false, false,
                false, Sort.price_asc, 0, 20);
        assertThat(service.search(cn).items()).extracting(ProductSummary::name).containsExactly("Phone CN");

        SearchRequest range = new SearchRequest("phone", Set.of(), Set.of(), 100_000L, null, false, false, false,
                false, false, Sort.price_asc, 0, 20);
        assertThat(service.search(range).items()).extracting(ProductSummary::name).containsExactly("Phone US");

        SearchRequest onlyA = new SearchRequest("phone", Set.of("a"), Set.of(), null, null, false, false, false,
                false, false, Sort.price_asc, 0, 20);
        SearchResponse r = service.search(onlyA);
        assertThat(r.sources()).extracting(s -> s.storeId()).containsExactly("a");
    }

    /** 프론트 필터의 amazon-us 처럼 API 가 없는 판매처만 고르면 503 이 아니라 빈 결과 */
    @Test
    void storeWithoutSource_isEmpty200() {
        SearchRequest r = new SearchRequest("phone", Set.of("amazon-us"), Set.of(), null, null, false, false, false,
                false, false, Sort.price_asc, 0, 20);
        SearchResponse res = service.search(r);
        assertThat(res.items()).isEmpty();
        assertThat(a.calls.get() + b.calls.get() + c.calls.get()).isZero();
    }

    @Test
    void paging() {
        List<MallSource.MallOffer> many = new ArrayList<>();
        for (int i = 1; i <= 5; i++) many.add(offer("a" + i, "Item " + i, String.valueOf(10 * i), "0",
                MallSource.StockStatus.IN_STOCK));
        a.behavior = () -> many;
        SearchResponse p1 = service.search(new SearchRequest("item", Set.of(), Set.of(), null, null, false, false,
                false, false, false, Sort.price_asc, 1, 2));
        assertThat(p1.totalElements()).isEqualTo(5);
        assertThat(p1.totalPages()).isEqualTo(3);
        assertThat(p1.items()).extracting(ProductSummary::name).containsExactly("Item 3", "Item 4");
    }

    @Test
    void budgetExceeded_isSkipped_and429CoolsDown() {
        a.budget = 0;
        b.behavior = () -> { throw new SourceException.RateLimited("429"); };
        c.behavior = () -> List.of(offer("c1", "Thing", "20", "0", MallSource.StockStatus.IN_STOCK));
        SearchResponse r = service.search(req("thing"));
        assertThat(r.sources()).extracting(s -> s.storeId() + ":" + s.status())
                .containsExactlyInAnyOrder("a:skipped", "b:error", "c:ok");
        assertThat(a.calls.get()).isZero();

        service.search(req("other thing"));   // B 는 쿨다운 중이라 호출하지 않음
        assertThat(b.calls.get()).isEqualTo(1);
    }

    @Test
    void noResults_givesSuggestions() {
        SearchResponse r = service.search(req("아이폰 17 울트라"));
        assertThat(r.items()).isEmpty();
        assertThat(r.suggestions()).contains("아이폰 17");
    }

    @Test
    void invalidQuery_is400() {
        assertThatThrownBy(() -> service.search(req("   "))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.search(req("x".repeat(51)))).isInstanceOf(BusinessException.class);
    }
}
