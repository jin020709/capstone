package com.teamsoda.globalprice.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.teamsoda.globalprice.catalog.Offers.OfferData;
import com.teamsoda.globalprice.catalog.Offers.PriceStatus;
import com.teamsoda.globalprice.catalog.Offers.PricedOffer;
import com.teamsoda.globalprice.pricing.PricingModels.Breakdown;
import com.teamsoda.globalprice.source.MallSource.StockStatus;

class PriceOutliersTest {

    private static PricedOffer offer(long id, long total, String title) {
        OfferData d = new OfferData(1, "Apple iPhone 17 Pro Max 256GB", null, id, id, 1, null, BigDecimal.ONE, "USD", null,
                "US", "https://shop/" + id, StockStatus.IN_STOCK, null, LocalDateTime.of(2026, 11, 17, 12, 0), null, title);
        Breakdown b = new Breakdown(total, 0, 0, 0, 0, 0, total, null, true, true, null, null, null, null, false);
        return new PricedOffer(d, b, b, PriceStatus.ok);
    }

    @Test
    void farBelowMedian_isSuspicious_andNotComparable() {
        List<PricedOffer> out = PriceOutliers.mark(List.of(
                offer(1, 83_047, "Apple iPhone 17 Pro Max - 6.9\" - 256GB - Cosmic Orange"),
                offer(2, 1_876_488, "Apple iPhone 17 Pro Max 256GB"),
                offer(3, 1_914_343, "iPhone 17 pro max - 256GB - Cosmic Orange"),
                offer(4, 1_929_466, "Apple iPhone 17 Pro Max 256GB Dual SIM")));
        assertThat(out.get(0).status()).isEqualTo(PriceStatus.suspicious);
        assertThat(out.get(0).comparable()).isFalse();
        assertThat(out.subList(1, 4)).allMatch(PricedOffer::comparable);
    }

    @Test
    void installmentWording_isSuspicious_evenWhenAlone() {
        List<PricedOffer> out = PriceOutliers.mark(List.of(
                offer(1, 169_016, "[Ansuran 36 Bulan - Kad Debit] New Apple iPhone 17 Pro | 256GB")));
        assertThat(out.getFirst().status()).isEqualTo(PriceStatus.suspicious);
    }

    @Test
    void fewOffersOrNormalSpread_untouched() {
        assertThat(PriceOutliers.mark(List.of(offer(1, 100_000, "a"), offer(2, 1_000_000, "b"))))
                .allMatch(PricedOffer::comparable);   // 2곳뿐이면 중간값으로 판단하지 않음
        assertThat(PriceOutliers.mark(List.of(offer(1, 1_500_000, "a"), offer(2, 1_800_000, "b"), offer(3, 2_000_000, "c"))))
                .allMatch(PricedOffer::comparable);
        assertThat(PriceOutliers.installment("Apple iPhone 17 Pro 256GB Monthly Deal")).isTrue();
        assertThat(PriceOutliers.installment("Apple iPhone 17 Pro 256GB")).isFalse();
    }

    @Test
    void familyReference_catchesLoneCheapListing() {   // Fold4 256 · 512 가 70~80만원인데 1TB 가 9만원
        Long ref = PriceOutliers.familyReference(java.util.Arrays.asList(705_680L, 823_812L, null));
        assertThat(ref).isEqualTo(764_746L);
        assertThat(PriceOutliers.markBelow(List.of(offer(1, 93_147, "Samsung Galaxy Z Fold4 1TB")), ref).getFirst().status())
                .isEqualTo(PriceStatus.suspicious);
        assertThat(PriceOutliers.markBelow(List.of(offer(1, 1_100_000, "Samsung Galaxy Z Fold4 1TB")), ref))
                .allMatch(PricedOffer::comparable);
        assertThat(PriceOutliers.familyReference(List.of(705_680L))).isNull();   // 다른 구성 1개로는 판단 안 함
    }
}
