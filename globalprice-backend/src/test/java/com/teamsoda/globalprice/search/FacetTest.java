package com.teamsoda.globalprice.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.teamsoda.globalprice.catalog.Offers.OfferData;
import com.teamsoda.globalprice.catalog.Offers.PriceStatus;
import com.teamsoda.globalprice.catalog.Offers.PricedOffer;
import com.teamsoda.globalprice.catalog.Offers.ProductGroup;
import com.teamsoda.globalprice.search.SearchService.FacetFilter;
import com.teamsoda.globalprice.search.SearchService.FacetInfo;
import com.teamsoda.globalprice.search.SearchService.Facets;
import com.teamsoda.globalprice.source.MallSource.StockStatus;

/** 다나와식 상단 체크표: 결과에 있는 값으로 칸을 만들고, 고른 값만 남긴다 */
class FacetTest {

    private static PricedOffer offer(long id, String title) {
        OfferData d = new OfferData(id, "x", null, id, id, 1, null, BigDecimal.ONE, "USD", null, "US", "u",
                StockStatus.IN_STOCK, null, LocalDateTime.of(2026, 11, 17, 12, 0), null, title);
        return new PricedOffer(d, null, null, PriceStatus.unavailable);
    }

    private static ProductGroup group(long id, String name, String... titles) {
        List<PricedOffer> offers = new java.util.ArrayList<>();
        for (int i = 0; i < titles.length; i++) offers.add(offer(id * 10 + i, titles[i]));
        return new ProductGroup(id, name, null, offers);
    }

    private final List<ProductGroup> groups = List.of(
            group(1, "Samsung Galaxy Z Fold8 256GB", "Galaxy Z Fold8 256GB Lavender", "Galaxy Z Fold8 256GB Cream"),
            group(2, "Samsung Galaxy Z Fold8 512GB", "Galaxy Z Fold8 512GB Lavender"),
            group(3, "Samsung Galaxy Z Fold8 Ultra 512GB", "Galaxy Z Fold8 Ultra 512GB Graphite"),
            group(4, "Phone case for Galaxy", "Phone case for Galaxy"));

    private Map<Long, FacetInfo> infos() {
        Map<Long, FacetInfo> m = new HashMap<>();
        groups.forEach(g -> m.put(g.productId(), FacetInfo.of(g.productName())));
        return m;
    }

    @Test
    void facets_listOnlyValuesInResults() {
        Facets f = SearchService.facets(groups, infos());
        assertThat(f.brands()).extracting(SearchService.FacetOption::label).containsExactly("Samsung");
        assertThat(f.models()).extracting(SearchService.FacetOption::label)
                .containsExactly("Samsung Galaxy Z Fold8", "Samsung Galaxy Z Fold8 Ultra");
        assertThat(f.storages()).extracting(SearchService.FacetOption::label).containsExactly("256GB", "512GB");
        assertThat(f.colors()).extracting(SearchService.FacetOption::value).contains("Lavender", "Cream", "Graphite");
    }

    @Test
    void checkingOneModel_showsOnlyThatModel() {
        String fold8 = FacetInfo.of("Samsung Galaxy Z Fold8 256GB").model();
        List<ProductGroup> out = SearchService.applyFacets(groups, infos(),
                new FacetFilter(Set.of(), Set.of(fold8), Set.of(), Set.of()));
        assertThat(out).extracting(ProductGroup::productId).containsExactly(1L, 2L);

        List<ProductGroup> lav512 = SearchService.applyFacets(groups, infos(),
                new FacetFilter(Set.of(), Set.of(), Set.of("512"), Set.of("lavender")));
        assertThat(lav512).extracting(ProductGroup::productId).containsExactly(2L);
        // 색상을 고르면 그 색상 판매처만 남음
        List<ProductGroup> cream = SearchService.applyFacets(groups, infos(),
                new FacetFilter(Set.of(), Set.of(), Set.of(), Set.of("cream")));
        assertThat(cream.getFirst().offers()).hasSize(1);
    }

    private static PricedOffer ok(long id, long total) {
        OfferData d = new OfferData(id, "x", null, id, id, 1, null, BigDecimal.ONE, "USD", null, "US", "u",
                StockStatus.IN_STOCK, null, LocalDateTime.of(2026, 11, 17, 12, 0), null, "t");
        com.teamsoda.globalprice.pricing.PricingModels.Breakdown b = new com.teamsoda.globalprice.pricing.PricingModels.Breakdown(
                total, 0, 0, 0, 0, 0, total, null, true, true, null, null, null, null, false);
        return new PricedOffer(d, b, b, PriceStatus.ok);
    }

    @Test
    void familyOutliers_marksLoneCheapConfig() {
        List<ProductGroup> gs = List.of(
                new ProductGroup(1, "Samsung Galaxy Z Fold4 256GB", null, List.of(ok(1, 705_680))),
                new ProductGroup(2, "Samsung Galaxy Z Fold4 512GB", null, List.of(ok(2, 823_812))),
                new ProductGroup(3, "Samsung Galaxy Z Fold4 1TB", null, List.of(ok(3, 93_147))),
                new ProductGroup(4, "Phone case for Galaxy", null, List.of(ok(4, 9_000))));
        List<ProductGroup> out = SearchService.familyOutliers(gs);
        assertThat(out.get(2).offers().getFirst().status()).isEqualTo(PriceStatus.suspicious);
        assertThat(out.get(0).offers().getFirst().comparable()).isTrue();
        assertThat(out.get(1).offers().getFirst().comparable()).isTrue();
        assertThat(out.get(3).offers().getFirst().comparable()).isTrue();   // 모델 모르는 상품은 그대로
    }

    @Test
    void familyOutliers_fallsBackToSeries_withLooserRatio() {   // Fold5 는 다른 용량이 없음 → Z Fold 시리즈와 비교
        List<ProductGroup> gs = List.of(
                new ProductGroup(1, "Samsung Galaxy Z Fold4 256GB", null, List.of(ok(1, 705_680))),
                new ProductGroup(2, "Samsung Galaxy Z Fold7 256GB", null, List.of(ok(2, 1_965_686))),
                new ProductGroup(3, "Samsung Galaxy Z Fold2 256GB", null, List.of(ok(3, 300_000))),
                new ProductGroup(4, "Samsung Galaxy Z Fold 5 5G F946 (garbled)", null, List.of(ok(4, 22_034))));
        List<ProductGroup> out = SearchService.familyOutliers(gs);
        assertThat(out.get(3).offers().getFirst().status()).isEqualTo(PriceStatus.suspicious);
        assertThat(out.get(2).offers().getFirst().comparable()).isTrue();   // 구형 Fold2 30만원은 1/5 기준이라 남음
        assertThat(SearchService.series("galaxy|z fold|5|")).isEqualTo("galaxy|z fold");
    }
}
