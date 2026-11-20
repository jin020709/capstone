package com.teamsoda.globalprice.pricing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PriceCalculator 의 입력 · 출력 값 객체 모음. DB · 시간 · 네트워크와 무관한 순수 값이다.
 */
public final class PricingModels {

    private PricingModels() {
    }

    /** 환율 1건. ttsRate(전신환매도율)가 있으면 그것을, 없으면 rateToKrw(매매기준율)를 쓴다. 모두 1 단위 기준 */
    public record FxRate(Long id, String currencyCode, BigDecimal rateToKrw, BigDecimal ttsRate,
                         LocalDate baseDate, LocalDateTime recordedAt) {
        public BigDecimal effective() {
            return ttsRate != null ? ttsRate : rateToKrw;
        }
    }

    /** 통화별 최신 환율 */
    public record FxTable(Map<String, FxRate> rates) {
        public Optional<BigDecimal> rate(String currency) {
            if ("KRW".equals(currency)) return Optional.of(BigDecimal.ONE);
            FxRate r = rates.get(currency);
            return r == null ? Optional.empty() : Optional.of(r.effective());
        }

        public Optional<FxRate> get(String currency) {
            return Optional.ofNullable(rates.get(currency));
        }
    }

    public record ShippingRule(long id, long mallId, BigDecimal baseFee, BigDecimal freeShippingThreshold,
                               String currencyCode) {
    }

    public record TaxRule(long id, Long categoryId, String originCountry, BigDecimal dutyRate, BigDecimal vatRate,
                          BigDecimal exemptionAmountUsd) {
    }

    public record FeeRule(long id, String feeName, BigDecimal feeRate, BigDecimal fixedFeeKrw) {
    }

    public enum EventType { SALE, COUPON }

    public enum DiscountType { RATE, AMOUNT }

    public record StoreEvent(long id, long mallId, String eventName, EventType eventType, String description,
                             DiscountType discountType, BigDecimal discountValue, BigDecimal minPurchaseAmount,
                             BigDecimal maxDiscountAmount, LocalDateTime startAt, LocalDateTime endAt,
                             boolean active, String eventUrl) {
    }

    /** 활성 규칙 묶음 (활성 여부는 조회할 때 이미 걸렀다고 본다. 행사는 기간 · is_active 를 계산기에서 다시 본다) */
    public record PricingRules(List<ShippingRule> shippingRules, List<TaxRule> taxRules, List<FeeRule> feeRules,
                               List<StoreEvent> events, String preferredFeeName) {
        public static PricingRules empty() {
            return new PricingRules(List.of(), List.of(), List.of(), List.of(), null);
        }
    }

    /**
     * 계산할 offer 1건.
     * @param price        쇼핑몰 통화 가격
     * @param shippingFee  한국 배송비(쇼핑몰 통화). 모르면 null → shipping_rules
     * @param originCountry 출발국. null 이면 mallCountry
     * @param categoryId   1차는 항상 null
     */
    public record PriceInput(BigDecimal price, String currency, BigDecimal shippingFee, long mallId,
                             String originCountry, String mallCountry, Long categoryId) {
        public String country() {
            return originCountry != null && !originCountry.isBlank() ? originCountry : mallCountry;
        }
    }

    /** 적용된 행사 1건. discountAmount 는 쇼핑몰 통화 기준 할인액 */
    public record AppliedEvent(long storeEventId, String eventName, EventType eventType, DiscountType discountType,
                               BigDecimal discountValue, BigDecimal maxDiscountAmount, BigDecimal discountAmount) {
    }

    /** 계산 결과. *Krw 는 모두 원 단위로 반올림된 정수 값 */
    public record Breakdown(long productPriceKrw, long discountAmountKrw, long shippingFeeKrw, long customsDutyKrw,
                            long vatKrw, long paymentFeeKrw, long totalAmountKrw,
                            AppliedEvent appliedEvent, boolean shippingKnown, boolean taxExempt,
                            BigDecimal exemptionUsd, BigDecimal shippingFeeInOfferCurrency,
                            LocalDate fxBaseDate, BigDecimal fxRate, boolean ruleMissing) {
    }

    /** 환율이 없어 계산할 수 없는 통화 */
    public static class RateUnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public RateUnavailableException(String currency) {
            super("환율 없음: " + currency);
        }
    }
}
