package com.teamsoda.globalprice.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.teamsoda.globalprice.catalog.ColorName;
import com.teamsoda.globalprice.catalog.Offers.OfferData;
import com.teamsoda.globalprice.catalog.Offers.PriceStatus;
import com.teamsoda.globalprice.catalog.Offers.PricedOffer;
import com.teamsoda.globalprice.catalog.Offers.ProductGroup;
import com.teamsoda.globalprice.catalog.ProductMatcher;
import com.teamsoda.globalprice.search.FacetBuilder.FacetFilter;
import com.teamsoda.globalprice.search.FacetBuilder.FacetInfo;
import com.teamsoda.globalprice.search.FacetBuilder.Facets;
import com.teamsoda.globalprice.source.MallSource.StockStatus;

/** 다나와식 상단 체크표: 결과에 있는 값으로 칸을 만들고, 고른 값만 남긴다 */
class FacetTest {

    private static PricedOffer offer(long id, String title) {
        OfferData d = new OfferData(id, "x", null, id, id, 1, null, BigDecimal.ONE, "USD", null, "US", "u",
                StockStatus.IN_STOCK, null, LocalDateTime.of(2026, 11, 17, 12, 0), null, title, ColorName.of(title),
                null);
        return new PricedOffer(d, null, null, PriceStatus.unavailable);
    }

    /** 저장할 때처럼 상품명으로 묶기 키를 만든 상품 */
    static ProductGroup grp(long id, String name, List<PricedOffer> offers) {
        ProductMatcher.Match m = ProductMatcher.match(name);
        return new ProductGroup(id, name, null, m == null ? null : m.key(), offers);
    }

    private static ProductGroup group(long id, String name, String... titles) {
        List<PricedOffer> offers = new java.util.ArrayList<>();
        for (int i = 0; i < titles.length; i++) offers.add(offer(id * 10 + i, titles[i]));
        return grp(id, name, offers);
    }

    private final List<ProductGroup> groups = List.of(
            group(1, "Samsung Galaxy Z Fold8 256GB", "Galaxy Z Fold8 256GB Lavender", "Galaxy Z Fold8 256GB Cream"),
            group(2, "Samsung Galaxy Z Fold8 512GB", "Galaxy Z Fold8 512GB Lavender"),
            group(3, "Samsung Galaxy Z Fold8 Ultra 512GB", "Galaxy Z Fold8 Ultra 512GB Graphite"),
            group(4, "Phone case for Galaxy", "Phone case for Galaxy"));

    private Map<Long, FacetInfo> infos() {
        Map<Long, FacetInfo> m = new HashMap<>();
        groups.forEach(g -> m.put(g.productId(), FacetInfo.of(g)));
        return m;
    }

    @Test
    void facets_listOnlyValuesInResults() {
        Facets f = FacetBuilder.facets(groups, infos());
        assertThat(f.brands()).extracting(FacetBuilder.FacetOption::label).containsExactly("Samsung");
        assertThat(f.models()).extracting(FacetBuilder.FacetOption::label)
                .containsExactly("Samsung Galaxy Z Fold8", "Samsung Galaxy Z Fold8 Ultra");
        assertThat(f.storages()).extracting(FacetBuilder.FacetOption::label).containsExactly("256GB", "512GB");
        assertThat(f.colors()).extracting(FacetBuilder.FacetOption::value).contains("Lavender", "Cream", "Graphite");
    }

    @Test
    void checkingOneModel_showsOnlyThatModel() {
        String fold8 = FacetInfo.of(groups.getFirst()).model();
        List<ProductGroup> out = FacetBuilder.applyFacets(groups, infos(),
                new FacetFilter(Set.of(), Set.of(fold8), Set.of(), Set.of()));
        assertThat(out).extracting(ProductGroup::productId).containsExactly(1L, 2L);

        List<ProductGroup> lav512 = FacetBuilder.applyFacets(groups, infos(),
                new FacetFilter(Set.of(), Set.of(), Set.of("512"), Set.of("lavender")));
        assertThat(lav512).extracting(ProductGroup::productId).containsExactly(2L);
        // 색상을 고르면 그 색상 판매처만 남음
        List<ProductGroup> cream = FacetBuilder.applyFacets(groups, infos(),
                new FacetFilter(Set.of(), Set.of(), Set.of(), Set.of("cream")));
        assertThat(cream.getFirst().offers()).hasSize(1);
    }

    @Test
    void productWithoutMatchKey_hasNoModelFacet() {   // 묶기 키가 없으면 상품명이 모델처럼 보여도 체크표에 안 넣음
        ProductGroup noKey = new ProductGroup(9, "Samsung Galaxy Z Fold8 256GB", null, null, List.of());
        assertThat(FacetInfo.of(noKey).model()).isNull();
    }
}
