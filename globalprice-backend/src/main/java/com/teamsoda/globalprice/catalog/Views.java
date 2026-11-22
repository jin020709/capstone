package com.teamsoda.globalprice.catalog;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 응답 JSON 모양. 필드 이름은 BACKEND_SPEC.md 6장을 따른다 (ProductSummary · Offer · Event).
 * 시각은 KST LocalDateTime (yyyy-MM-dd'T'HH:mm:ss).
 */
public final class Views {

    private Views() {
    }

    public record CalcView(long productPriceKrw, long shippingFeeKrw, long discountAmountKrw, long customsDutyKrw,
                           long vatKrw, long paymentFeeKrw, long totalAmountKrw, boolean taxed, boolean taxExempt,
                           BigDecimal exemptionUsd, boolean shippingKnown) {
    }

    public record AppliedEventView(long storeEventId, String eventName, String eventType, String discountType,
                                   BigDecimal discountValue, BigDecimal maxDiscountAmount, BigDecimal discountAmount) {
    }

    public record ExchangeRateView(String currencyCode, BigDecimal rateToKrw, LocalDate baseDate) {
    }

    /** 같은 상품 안의 색상별 최저가 (다나와처럼 검색 결과 · 상세에서 색상마다 한 줄). 색상이 2개 이상일 때만 채움 */
    public record ColorView(String name, Long bestTotal, int offerCount) {
    }

    /**
     * 판매처 offer 1건 (= mall_product_variants 1행).
     * title = 쇼핑몰에 올라온 원래 상품명, seller = 판매자 도메인 (Shopify 처럼 여러 상점이 한 플랫폼에 있을 때)
     */
    public record OfferView(long mallProductId, long mallVariantId, long mallId, String storeId, String storeName,
                            String countryName, String countryCode, String variantName, String stockStatus,
                            String status, LocalDateTime calculatedAt, LocalDateTime updatedAt,
                            LocalDateTime fetchedAt, String currency, BigDecimal price, BigDecimal shipping,
                            String shippingNote, String url, String imageUrl, AppliedEventView appliedEvent,
                            boolean taxIncluded, Integer shipDays, List<Integer> deliveryDays, CalcView calc,
                            Long total, Long totalNormal, ExchangeRateView exchangeRate, String title,
                            String seller, String color) {
    }

    /** 검색 · 찜 목록의 상품 1건 */
    public record ProductSummary(long id, String name, String spec, String brand, String model, String imageUrl,
                                 String categoryName, Double rating, Integer reviewCount, Integer popularity,
                                 List<String> badges, int offerCount, Long bestTotal, Long bestNormalTotal,
                                 String bestStore, List<OfferView> offers, List<ColorView> colors) {
    }

    public record EventView(long id, long storeEventId, String storeId, long mallId, String storeName, String name,
                            String eventType, LocalDate start, LocalDate end, LocalDateTime startAt,
                            LocalDateTime endAt, String benefit, String discountType, BigDecimal discountValue,
                            BigDecimal minPurchaseAmount, BigDecimal maxDiscountAmount, String url, String mark,
                            String tag) {
    }

    public record RatesView(String base, String source, LocalDate baseDate, LocalDateTime updatedAt,
                            Map<String, BigDecimal> rates) {
    }
}
