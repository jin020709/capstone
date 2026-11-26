package com.teamsoda.globalprice.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.teamsoda.globalprice.catalog.Offers.OfferData;
import com.teamsoda.globalprice.catalog.Offers.PriceStatus;
import com.teamsoda.globalprice.catalog.Offers.PricedOffer;
import com.teamsoda.globalprice.catalog.Offers.ProductGroup;
import com.teamsoda.globalprice.pricing.PricingModels.Breakdown;
import com.teamsoda.globalprice.source.MallSource.StockStatus;

/** 이상 가격 2 · 3 단계: 같은 모델 다른 구성, 같은 시리즈와 비교 (검색 · 상세 · 찜 · 알림 공통) */
class OutlierServiceTest {

    static PricedOffer ok(long id, long total) {
        OfferData d = new OfferData(id, "x", null, id, id, 1, null, BigDecimal.ONE, "USD", null, "US", "u",
                StockStatus.IN_STOCK, null, LocalDateTime.of(2026, 11, 17, 12, 0), null, "t", null, null);
        Breakdown b = new Breakdown(total, 0, 0, 0, 0, 0, total, null, true, true, null, null, null, null, false);
        return new PricedOffer(d, b, b, PriceStatus.ok);
    }

    /** 저장할 때처럼 상품명으로 묶기 키를 만든 상품 */
    static ProductGroup grp(long id, String name, long total) {
        ProductMatcher.Match m = ProductMatcher.match(name);
        return new ProductGroup(id, name, null, m == null ? null : m.key(), List.of(ok(id, total)));
    }

    /** 검색처럼 지금 판정하는 상품들만 비교 대상인 경우 */
    static List<ProductGroup> judge(List<ProductGroup> gs) {
        List<OutlierService.Member> pool = new ArrayList<>();
        gs.forEach(g -> pool.add(OutlierService.member(g)));
        return OutlierService.judgeWith(gs, pool, pool);
    }

    @Test
    void marksLoneCheapConfig() {
        List<ProductGroup> out = judge(List.of(
                grp(1, "Samsung Galaxy Z Fold4 256GB", 705_680),
                grp(2, "Samsung Galaxy Z Fold4 512GB", 823_812),
                grp(3, "Samsung Galaxy Z Fold4 1TB", 93_147),
                grp(4, "Phone case for Galaxy", 9_000)));
        assertThat(out.get(2).offers().getFirst().status()).isEqualTo(PriceStatus.suspicious);
        assertThat(out.get(0).offers().getFirst().comparable()).isTrue();
        assertThat(out.get(1).offers().getFirst().comparable()).isTrue();
        assertThat(out.get(3).offers().getFirst().comparable()).isTrue();   // 모델 모르는 상품은 그대로
    }

    @Test
    void fallsBackToSeries_withLooserRatio() {   // Fold5 는 다른 용량이 없음 → Z Fold 시리즈와 비교
        List<ProductGroup> out = judge(List.of(
                grp(1, "Samsung Galaxy Z Fold4 256GB", 705_680),
                grp(2, "Samsung Galaxy Z Fold7 256GB", 1_965_686),
                grp(3, "Samsung Galaxy Z Fold2 256GB", 300_000),
                grp(4, "Samsung Galaxy Z Fold 5 5G F946 (garbled)", 22_034)));
        assertThat(out.get(3).offers().getFirst().status()).isEqualTo(PriceStatus.suspicious);
        assertThat(out.get(2).offers().getFirst().comparable()).isTrue();   // 구형 Fold2 30만원은 1/5 기준이라 남음
        assertThat(OutlierService.series("galaxy|z fold|5|")).isEqualTo("galaxy|z fold");
    }

    @Test
    void comparesWithStoredConfigs_evenWhenAloneInThisRequest() {   // 상세 · 찜 목록: 다른 구성은 DB 에만 있음
        ProductGroup lone = grp(3, "Samsung Galaxy Z Fold4 1TB", 93_147);
        List<OutlierService.Member> pool = List.of(
                OutlierService.member(grp(1, "Samsung Galaxy Z Fold4 256GB", 705_680)),
                OutlierService.member(grp(2, "Samsung Galaxy Z Fold4 512GB", 823_812)),
                OutlierService.member(lone));
        assertThat(OutlierService.judgeWith(List.of(lone), pool, List.of(OutlierService.member(lone))).getFirst()
                .offers().getFirst().status()).isEqualTo(PriceStatus.suspicious);
    }

    @Test
    void caseIsNotComparedWithStoredPhonesOfSameSeries() {   // 11/26: "아이폰 17 케이스" 결과가 전부 이상 가격이 됨
        ProductGroup caseGroup = grp(9, "iPhone 17 Silicone Case", 56_180);
        List<OutlierService.Member> dbPhones = new ArrayList<>(List.of(
                OutlierService.member(grp(1, "Apple iPhone 17 256GB", 1_230_060)),
                OutlierService.member(grp(2, "Apple iPhone 17 Pro 256GB", 1_497_158)),
                OutlierService.member(grp(3, "Apple iPhone 17 Pro Max 256GB", 1_821_135))));
        dbPhones.add(OutlierService.member(caseGroup));
        // 같은 모델 비교 대상(DB)에는 본품이 있지만, 시리즈 비교는 같은 요청의 상품끼리만
        assertThat(OutlierService.judgeWith(List.of(caseGroup), dbPhones, List.of(OutlierService.member(caseGroup)))
                .getFirst().offers().getFirst().comparable()).isTrue();
    }

    @Test
    void noComparisonWithoutEnoughOthers() {
        ProductGroup lone = grp(3, "Samsung Galaxy Z Fold4 1TB", 93_147);
        assertThat(judge(List.of(lone, grp(1, "Samsung Galaxy Z Fold4 256GB", 705_680))).getFirst()
                .offers().getFirst().comparable()).isTrue();   // 다른 구성 1개뿐 → 기준 없음
    }
}
