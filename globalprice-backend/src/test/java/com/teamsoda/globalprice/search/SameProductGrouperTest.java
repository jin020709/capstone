package com.teamsoda.globalprice.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.teamsoda.globalprice.catalog.Offers.OfferData;
import com.teamsoda.globalprice.catalog.Offers.PriceStatus;
import com.teamsoda.globalprice.catalog.Offers.PricedOffer;
import com.teamsoda.globalprice.catalog.Offers.ProductGroup;
import com.teamsoda.globalprice.source.MallSource.StockStatus;

class SameProductGrouperTest {

    private static PricedOffer offer(long productId, long variantId, String listingTitle, String image) {
        OfferData d = new OfferData(productId, "Apple iPhone 17 256GB", image, variantId, variantId, 1, null,
                new BigDecimal("799"), "USD", null, "US", "https://shop/" + variantId, StockStatus.IN_STOCK, null,
                LocalDateTime.of(2026, 11, 16, 12, 0), null, listingTitle);
        return new PricedOffer(d, null, null, PriceStatus.unavailable);
    }

    @Test
    void sameProductId_becomesOneRow_inFirstSeenOrder() {
        List<ProductGroup> groups = new SameProductGrouper().group(List.of(
                offer(10, 1, "Apple iPhone 17 256GB Black", null),
                offer(20, 2, "Something else", "b.jpg"),
                offer(10, 3, "Apple iPhone 17 256GB Mist Blue | DHL", "a.jpg"),
                offer(10, 3, "Apple iPhone 17 256GB Mist Blue | DHL", "a.jpg")));   // 같은 옵션 중복

        assertThat(groups).extracting(ProductGroup::productId).containsExactly(10L, 20L);
        ProductGroup iphone = groups.getFirst();
        assertThat(iphone.offers()).hasSize(2);
        assertThat(iphone.productName()).isEqualTo("Apple iPhone 17 256GB");
        assertThat(iphone.imageUrl()).isEqualTo("a.jpg");   // 첫 리스팅에 이미지가 없으면 다음 것
        assertThat(iphone.offers()).extracting(o -> o.data().listingTitle())
                .containsExactly("Apple iPhone 17 256GB Black", "Apple iPhone 17 256GB Mist Blue | DHL");
    }
}
