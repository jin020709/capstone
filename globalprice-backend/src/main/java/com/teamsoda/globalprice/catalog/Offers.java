package com.teamsoda.globalprice.catalog;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import com.teamsoda.globalprice.pricing.PricingModels.Breakdown;
import com.teamsoda.globalprice.source.MallSource.MallOffer;
import com.teamsoda.globalprice.source.MallSource.StockStatus;

/** 검색 · 상세 · 찜에서 함께 쓰는 offer 모델 */
public final class Offers {

    private Offers() {
    }

    /**
     * 저장된(또는 방금 저장한) offer 1건. productId 가 반드시 있다.
     * productName = 묶인 상품의 대표 이름, listingTitle = 쇼핑몰에 올라온 원래 상품명 (판매처 줄 표시용)
     */
    public record OfferData(long productId, String productName, String imageUrl, long mallProductId,
                            long mallVariantId, long mallId, String variantName, BigDecimal price, String currency,
                            BigDecimal shippingFee, String originCountry, String productUrl, StockStatus stock,
                            Integer shipDays, LocalDateTime fetchedAt, Long categoryId, String listingTitle) {

        public static OfferData of(MallOffer o, CatalogRepository.SavedIds ids, long mallId, LocalDateTime fetchedAt) {
            String name = ids.productName() != null ? ids.productName() : o.title();
            return new OfferData(ids.productId(), name, o.imageUrl(), ids.mallProductId(), ids.mallVariantId(),
                    mallId, o.variantName(), o.price(), o.currencyCode(), o.shippingFee(), o.originCountry(),
                    o.productUrl(), o.stock() == null ? StockStatus.UNKNOWN : o.stock(), o.shipDays(), fetchedAt,
                    null, o.title());
        }

        public static OfferData of(CatalogRepository.StoredOffer s) {
            return new OfferData(s.productId(), s.productName(), s.imageUrl(), s.mallProductId(), s.mallVariantId(),
                    s.mallId(), s.variantName(), s.currentPrice(), s.currencyCode(), s.shippingFee(), null,
                    s.productUrl(), s.stockStatus(), null, s.fetchedAt(), s.categoryId(),
                    s.listingTitle() != null ? s.listingTitle() : s.productName());
        }
    }

    /** 가격 상태: ok(갱신 6시간 이내) · stale(초과) · unavailable(계산 불가) */
    public enum PriceStatus {
        ok, stale, unavailable,
        /** 이상 가격 (할부 · 계약금 표기, 같은 상품 중간값의 1/3 미만). 표시는 하되 비교 · 최저가에서 뺌 */
        suspicious
    }

    /**
     * 계산된 offer. applied = 요청 기준(쿠폰 여부 반영), normal = 행사 · 쿠폰 없이 계산한 값(취소선 가격).
     * 계산이 불가하면 둘 다 null, status = unavailable.
     */
    public record PricedOffer(OfferData data, Breakdown applied, Breakdown normal, PriceStatus status) {
        public Long total() {
            return applied == null ? null : applied.totalAmountKrw();
        }

        public boolean comparable() {
            return applied != null && data.stock() != StockStatus.OUT_OF_STOCK && status != PriceStatus.suspicious;
        }
    }

    /** 같은 상품으로 묶인 offer 들 (같은 productId) */
    public record ProductGroup(long productId, String productName, String imageUrl, List<PricedOffer> offers) {
        public PricedOffer best() {
            return offers.stream().filter(PricedOffer::comparable)
                    .min(java.util.Comparator.comparingLong(PricedOffer::total))
                    .orElse(offers.isEmpty() ? null : offers.getFirst());
        }
    }
}
