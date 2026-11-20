package com.teamsoda.globalprice.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.teamsoda.globalprice.pricing.PricingModels.Breakdown;
import com.teamsoda.globalprice.pricing.PricingModels.DiscountType;
import com.teamsoda.globalprice.pricing.PricingModels.EventType;
import com.teamsoda.globalprice.pricing.PricingModels.FeeRule;
import com.teamsoda.globalprice.pricing.PricingModels.FxRate;
import com.teamsoda.globalprice.pricing.PricingModels.FxTable;
import com.teamsoda.globalprice.pricing.PricingModels.PriceInput;
import com.teamsoda.globalprice.pricing.PricingModels.PricingRules;
import com.teamsoda.globalprice.pricing.PricingModels.RateUnavailableException;
import com.teamsoda.globalprice.pricing.PricingModels.ShippingRule;
import com.teamsoda.globalprice.pricing.PricingModels.StoreEvent;
import com.teamsoda.globalprice.pricing.PricingModels.TaxRule;

/**
 * 명세서 5장 단위 테스트. 공통 가정:
 * USD 1,400 · JPY 9.5 (1엔) · 기본 세금(관세 0, 부가세 10%, 면세 $150) · 미국 세금(면세 $200) · 수수료 1%
 * 쇼핑몰 1 = 미국(US), 2 = 일본(JP), 3 = 무료배송 규칙 있는 미국 쇼핑몰, 9 = 배송 규칙 없음, 5 = 국내 직구관(KR)
 */
class PriceCalculatorTest {

    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 13, 12, 0);
    static final LocalDate BASE = LocalDate.of(2026, 11, 12);

    static FxTable fx = new FxTable(Map.of(
            "USD", new FxRate(1L, "USD", bd("1400"), null, BASE, NOW),
            "JPY", new FxRate(2L, "JPY", bd("9.5"), null, BASE, NOW)));

    static TaxRule defaultTax = new TaxRule(1, null, null, bd("0"), bd("0.10"), bd("150"));
    static TaxRule usTax = new TaxRule(2, null, "US", bd("0"), bd("0.10"), bd("200"));
    static FeeRule fee1 = new FeeRule(1, "해외 결제 수수료", bd("0.0100"), bd("0"));
    static FeeRule fixed500 = new FeeRule(2, "기타 결제 수수료", bd("0"), bd("500"));
    static ShippingRule freeOver100 = new ShippingRule(10, 3, bd("15"), bd("100"), "USD");

    static PricingRules rules(StoreEvent... events) {
        return new PricingRules(List.of(freeOver100), List.of(defaultTax, usTax), List.of(fixed500, fee1),
                List.of(events), "해외 결제 수수료");
    }

    static PriceInput us(String price, String shipping) {
        return new PriceInput(bd(price), "USD", shipping == null ? null : bd(shipping), 1, null, "US", null);
    }

    static StoreEvent event(long id, long mall, EventType type, DiscountType dt, String value, String min, String max) {
        return new StoreEvent(id, mall, "행사" + id, type, null, dt, value == null ? null : bd(value),
                min == null ? null : bd(min), max == null ? null : bd(max),
                NOW.minusDays(1), NOW.plusDays(10), true, null);
    }

    static Breakdown calc(PriceInput in, PricingRules r, boolean coupon) {
        return PriceCalculator.calculate(in, fx, r, NOW, coupon);
    }

    // ---------------- 시드 기준 금액 ----------------

    /**
     * iPhone 17 · Amazon US: $799, 배송 $15, 쿠폰 10% ($100 이상, 최대 $50)
     * 상품가 799×1400 = 1,118,600 / 할인 50×1400 = 70,000 / 배송 15×1400 = 21,000
     * 면세판정 (1,118,600−70,000)/1400 = $749 > $200 → 과세
     * 과세가격 1,069,600 · 관세 0 · 부가세 106,960 · 수수료 10,696 → 합계 1,187,256
     */
    @Test
    void seed_iphoneAmazon_1187256() {
        StoreEvent coupon = event(1, 1, EventType.COUPON, DiscountType.RATE, "0.10", "100", "50");
        Breakdown b = calc(us("799.00", "15.00"), rules(coupon), true);
        assertThat(b.productPriceKrw()).isEqualTo(1_118_600);
        assertThat(b.discountAmountKrw()).isEqualTo(70_000);
        assertThat(b.shippingFeeKrw()).isEqualTo(21_000);
        assertThat(b.customsDutyKrw()).isZero();
        assertThat(b.vatKrw()).isEqualTo(106_960);
        assertThat(b.paymentFeeKrw()).isEqualTo(10_696);
        assertThat(b.totalAmountKrw()).isEqualTo(1_187_256);
        assertThat(b.appliedEvent().discountAmount()).isEqualByComparingTo("50");
        assertThat(b.taxExempt()).isFalse();
    }

    /**
     * iPhone 17 · Rakuten (JP, 기본 세금 규칙): ¥119,800, 배송 ¥1,200, 쿠폰 5% (¥10,000 이상, 최대 ¥2,000)
     * 상품가 1,138,100 / 할인 19,000 / 배송 11,400 / 과세가격 1,130,500
     * 부가세 113,050 · 수수료 11,305 → 합계 1,254,855
     */
    @Test
    void seed_iphoneRakuten_1254855() {
        StoreEvent coupon = event(2, 2, EventType.COUPON, DiscountType.RATE, "0.05", "10000", "2000");
        PriceInput in = new PriceInput(bd("119800"), "JPY", bd("1200"), 2, null, "JP", null);
        Breakdown b = calc(in, rules(coupon), true);
        assertThat(b.discountAmountKrw()).isEqualTo(19_000);
        assertThat(b.vatKrw()).isEqualTo(113_050);
        assertThat(b.paymentFeeKrw()).isEqualTo(11_305);
        assertThat(b.totalAmountKrw()).isEqualTo(1_254_855);
    }

    /**
     * Galaxy S25 · Amazon US: $749, 배송 $12, 쿠폰 10% 최대 $50
     * 상품가 1,048,600 / 할인 70,000 / 배송 16,800 / 과세가격 995,400 / 부가세 99,540 / 수수료 9,954 → 1,104,894
     */
    @Test
    void seed_galaxyAmazon_1104894() {
        StoreEvent coupon = event(1, 1, EventType.COUPON, DiscountType.RATE, "0.10", "100", "50");
        Breakdown b = calc(us("749.00", "12.00"), rules(coupon), true);
        assertThat(b.totalAmountKrw()).isEqualTo(1_104_894);
    }

    // ---------------- 면세 한도 ----------------

    /** 미국 $200.00 → 280,000 / 1400 = $200 ≤ $200 면세. 수수료 2,800 → 282,800 */
    @Test
    void usExemption_exactlyAtLimit_isExempt() {
        Breakdown b = calc(us("200.00", "0"), rules(), false);
        assertThat(b.taxExempt()).isTrue();
        assertThat(b.vatKrw()).isZero();
        assertThat(b.totalAmountKrw()).isEqualTo(282_800);
    }

    /** 미국 $200.01 → 상품가 280,014 과세. 부가세 28,001(28,001.4) · 수수료 2,800(2,800.14) → 310,815 */
    @Test
    void usExemption_justOverLimit_isTaxed() {
        Breakdown b = calc(us("200.01", "0"), rules(), false);
        assertThat(b.taxExempt()).isFalse();
        assertThat(b.vatKrw()).isEqualTo(28_001);
        assertThat(b.paymentFeeKrw()).isEqualTo(2_800);
        assertThat(b.totalAmountKrw()).isEqualTo(310_815);
    }

    /** 기타 국가(CN) $150 면세 → 210,000 + 2,100 = 212,100 / $150.01 과세 → 210,014 + 21,001 + 2,100 = 233,115 */
    @Test
    void otherCountryExemption_150() {
        PriceInput at = new PriceInput(bd("150.00"), "USD", bd("0"), 1, "CN", "US", null);
        PriceInput over = new PriceInput(bd("150.01"), "USD", bd("0"), 1, "CN", "US", null);
        assertThat(calc(at, rules(), false).totalAmountKrw()).isEqualTo(212_100);
        Breakdown b = calc(over, rules(), false);
        assertThat(b.taxExempt()).isFalse();
        assertThat(b.totalAmountKrw()).isEqualTo(233_115);
    }

    /** 면세 판정은 배송비 제외: $190 + 배송 $50 → 물품 $190 ≤ $200 면세 (배송 포함 $240 이어도) */
    @Test
    void exemption_excludesShipping() {
        Breakdown b = calc(us("190", "50"), rules(), false);
        assertThat(b.taxExempt()).isTrue();
        assertThat(b.vatKrw()).isZero();
    }

    // ---------------- 할인 ----------------

    /** $300, SALE 20% 최대 $50 → 할인 $50(70,000). 물품 350,000($250) 과세. 부가세 35,000 · 수수료 3,500 → 388,500 */
    @Test
    void rateDiscount_cappedByMax() {
        StoreEvent sale = event(3, 1, EventType.SALE, DiscountType.RATE, "0.20", null, "50");
        Breakdown b = calc(us("300", "0"), rules(sale), false);
        assertThat(b.discountAmountKrw()).isEqualTo(70_000);
        assertThat(b.appliedEvent().discountAmount()).isEqualByComparingTo("50");
        assertThat(b.totalAmountKrw()).isEqualTo(388_500);
    }

    /** $90 에 쿠폰 $10 ($100 이상) → 최소 구매금액 미달로 미적용. 126,000 + 1,260 = 127,260 */
    @Test
    void coupon_minPurchaseNotMet_notApplied() {
        StoreEvent coupon = event(4, 1, EventType.COUPON, DiscountType.AMOUNT, "10", "100", null);
        Breakdown b = calc(us("90", "0"), rules(coupon), true);
        assertThat(b.appliedEvent()).isNull();
        assertThat(b.totalAmountKrw()).isEqualTo(127_260);
    }

    /** 쿠폰은 couponEnabled=false 면 후보에서 빠지고, SALE 은 항상 후보 */
    @Test
    void coupon_onlyWhenEnabled_saleAlways() {
        StoreEvent coupon = event(4, 1, EventType.COUPON, DiscountType.AMOUNT, "10", null, null);
        StoreEvent sale = event(5, 1, EventType.SALE, DiscountType.AMOUNT, "5", null, null);
        assertThat(calc(us("120", "0"), rules(coupon, sale), false).appliedEvent().storeEventId()).isEqualTo(5);
        assertThat(calc(us("120", "0"), rules(coupon, sale), true).appliedEvent().storeEventId()).isEqualTo(4);
    }

    /** 다른 쇼핑몰 · 기간 밖 · 비활성 행사는 적용 안 됨 */
    @Test
    void event_otherMallOrOutOfPeriod_ignored() {
        StoreEvent otherMall = event(6, 2, EventType.SALE, DiscountType.AMOUNT, "10", null, null);
        StoreEvent expired = new StoreEvent(7, 1, "끝난 행사", EventType.SALE, null, DiscountType.AMOUNT, bd("10"),
                null, null, NOW.minusDays(10), NOW.minusDays(1), true, null);
        StoreEvent inactive = new StoreEvent(8, 1, "비활성", EventType.SALE, null, DiscountType.AMOUNT, bd("10"),
                null, null, NOW.minusDays(1), NOW.plusDays(1), false, null);
        assertThat(calc(us("120", "0"), rules(otherMall, expired, inactive), true).appliedEvent()).isNull();
    }

    /** 정액 ¥500 할인, ¥5,000 상품: 47,500 − 4,750 = 42,750 ($30.5 면세), 수수료 427.5 → 428 (HALF_UP) → 43,178 */
    @Test
    void amountDiscount_convertedToKrw() {
        StoreEvent sale = event(9, 2, EventType.SALE, DiscountType.AMOUNT, "500", null, null);
        PriceInput in = new PriceInput(bd("5000"), "JPY", bd("0"), 2, null, "JP", null);
        Breakdown b = calc(in, rules(sale), false);
        assertThat(b.discountAmountKrw()).isEqualTo(4_750);
        assertThat(b.paymentFeeKrw()).isEqualTo(428);
        assertThat(b.totalAmountKrw()).isEqualTo(43_178);
    }

    /** $5 상품에 정액 $10 할인 → 할인은 상품가까지만 (7,000). 합계 0 */
    @Test
    void discount_neverExceedsProductPrice() {
        StoreEvent sale = event(10, 1, EventType.SALE, DiscountType.AMOUNT, "10", null, null);
        Breakdown b = calc(us("5", "0"), rules(sale), false);
        assertThat(b.discountAmountKrw()).isEqualTo(b.productPriceKrw()).isEqualTo(7_000);
        assertThat(b.totalAmountKrw()).isZero();
    }

    /** 할인 정보 없는 SALE → 적용 안 함. $100 → 140,000 + 1,400 = 141,400 */
    @Test
    void saleWithoutDiscountInfo_isZero() {
        StoreEvent sale = event(11, 1, EventType.SALE, null, null, null, null);
        Breakdown b = calc(us("100", "0"), rules(sale), false);
        assertThat(b.appliedEvent()).isNull();
        assertThat(b.totalAmountKrw()).isEqualTo(141_400);
    }

    /** 가장 큰 할인 1건만 적용 */
    @Test
    void onlyLargestDiscountApplied() {
        StoreEvent small = event(12, 1, EventType.SALE, DiscountType.AMOUNT, "5", null, null);
        StoreEvent big = event(13, 1, EventType.SALE, DiscountType.RATE, "0.10", null, null);
        Breakdown b = calc(us("120", "0"), rules(small, big), false);
        assertThat(b.appliedEvent().storeEventId()).isEqualTo(13);
        assertThat(b.discountAmountKrw()).isEqualTo(16_800);   // $12 × 1400
    }

    // ---------------- 징수 최저한 (관세 + 부가세 1만원 미만) ----------------

    /**
     * 일본 규칙(면세 0, 부가세 10%): ¥5,000 → 47,500 과세, 부가세 4,750 &lt; 10,000 → 0. 수수료 475 → 47,975
     * ¥11,000 → 104,500, 부가세 10,450 ≥ 10,000 → 유지. 수수료 1,045 → 115,995
     */
    @Test
    void taxBelow10000_isZero() {
        TaxRule jp = new TaxRule(3, null, "JP", bd("0"), bd("0.10"), bd("0"));
        PricingRules r = new PricingRules(List.of(), List.of(defaultTax, jp), List.of(fee1), List.of(), null);
        Breakdown low = PriceCalculator.calculate(
                new PriceInput(bd("5000"), "JPY", bd("0"), 2, null, "JP", null), fx, r, NOW, false);
        assertThat(low.taxExempt()).isFalse();
        assertThat(low.vatKrw()).isZero();
        assertThat(low.totalAmountKrw()).isEqualTo(47_975);
        Breakdown high = PriceCalculator.calculate(
                new PriceInput(bd("11000"), "JPY", bd("0"), 2, null, "JP", null), fx, r, NOW, false);
        assertThat(high.vatKrw()).isEqualTo(10_450);
        assertThat(high.totalAmountKrw()).isEqualTo(115_995);
    }

    // ---------------- 배송비 ----------------

    /** 무료배송 기준 $100: $100 → 무료, 140,000 + 1,400 = 141,400 */
    @Test
    void freeShipping_atThreshold() {
        PriceInput in = new PriceInput(bd("100"), "USD", null, 3, null, "US", null);
        Breakdown b = calc(in, rules(), false);
        assertThat(b.shippingFeeKrw()).isZero();
        assertThat(b.shippingKnown()).isTrue();
        assertThat(b.totalAmountKrw()).isEqualTo(141_400);
    }

    /** $99.99 → 139,986 + 배송 21,000, 수수료 1,609.86 → 1,610 → 162,596 */
    @Test
    void freeShipping_justBelowThreshold() {
        PriceInput in = new PriceInput(bd("99.99"), "USD", null, 3, null, "US", null);
        Breakdown b = calc(in, rules(), false);
        assertThat(b.shippingFeeKrw()).isEqualTo(21_000);
        assertThat(b.shippingFeeInOfferCurrency()).isEqualByComparingTo("15");
        assertThat(b.totalAmountKrw()).isEqualTo(162_596);
    }

    /** 배송비도 규칙도 없음 → 0, shippingKnown=false. $50 → 70,000 + 700 = 70,700 */
    @Test
    void shippingUnknown() {
        PriceInput in = new PriceInput(bd("50"), "USD", null, 9, null, "US", null);
        Breakdown b = calc(in, rules(), false);
        assertThat(b.shippingKnown()).isFalse();
        assertThat(b.shippingFeeKrw()).isZero();
        assertThat(b.totalAmountKrw()).isEqualTo(70_700);
    }

    // ---------------- 원화 · 통화 · 반올림 ----------------

    /** 원화 결제 ₩150,000 + 배송 ₩3,000 → 수수료 0, 면세 → 153,000 */
    @Test
    void krwPayment_noFee() {
        PriceInput in = new PriceInput(bd("150000"), "KRW", bd("3000"), 5, null, "KR", null);
        Breakdown b = calc(in, rules(), false);
        assertThat(b.paymentFeeKrw()).isZero();
        assertThat(b.totalAmountKrw()).isEqualTo(153_000);
    }

    /** 해외(US) 상점이 원화 현지가 ₩150,000 를 보여 줘도 해외결제 수수료 1% 적용 → 1,500 */
    @Test
    void krwPriceFromForeignMall_stillHasFee() {
        PriceInput in = new PriceInput(bd("150000"), "KRW", bd("0"), 1, null, "US", null);
        assertThat(calc(in, rules(), false).paymentFeeKrw()).isEqualTo(1_500);
    }

    /** JPY 9.4567 × 1,234 = 11,669.5678 → 11,670 / IDR 0.084512 × 150,000 = 12,676.8 → 12,677 */
    @Test
    void hundredUnitCurrencies_storedPerUnit() {
        FxTable t = new FxTable(Map.of(
                "USD", new FxRate(1L, "USD", bd("1400"), null, BASE, NOW),
                "JPY", new FxRate(2L, "JPY", bd("9.456700"), null, BASE, NOW),
                "IDR", new FxRate(3L, "IDR", bd("0.084512"), null, BASE, NOW)));
        PricingRules r = rules();
        assertThat(PriceCalculator.calculate(new PriceInput(bd("1234"), "JPY", bd("0"), 2, null, "JP", null),
                t, r, NOW, false).productPriceKrw()).isEqualTo(11_670);
        assertThat(PriceCalculator.calculate(new PriceInput(bd("150000"), "IDR", bd("0"), 2, null, "ID", null),
                t, r, NOW, false).productPriceKrw()).isEqualTo(12_677);
    }

    /** 1.2345 × 1,000 = 1,234.5 → HALF_UP 1,235 (HALF_EVEN 이면 1,234) */
    @Test
    void rounding_halfUp() {
        FxTable t = new FxTable(Map.of("USD", new FxRate(1L, "USD", bd("1000"), null, BASE, NOW)));
        Breakdown b = PriceCalculator.calculate(us("1.2345", "0"), t, rules(), NOW, false);
        assertThat(b.productPriceKrw()).isEqualTo(1_235);
    }

    /** 전신환매도율(tts)이 있으면 우선: $100 × 1,413.6 = 141,360 */
    @Test
    void ttsRate_preferred() {
        FxTable t = new FxTable(Map.of("USD", new FxRate(1L, "USD", bd("1400"), bd("1413.6"), BASE, NOW)));
        assertThat(PriceCalculator.calculate(us("100", "0"), t, rules(), NOW, false).productPriceKrw())
                .isEqualTo(141_360);
    }

    @Test
    void missingRate_throws() {
        PriceInput in = new PriceInput(bd("10"), "EUR", bd("0"), 1, null, "DE", null);
        assertThatThrownBy(() -> calc(in, rules(), false)).isInstanceOf(RateUnavailableException.class);
    }

    // ---------------- 규칙 우선순위 ----------------

    @Test
    void taxRulePriority_categoryCountry_category_country_default() {
        TaxRule catCountry = new TaxRule(10, 1L, "US", bd("0.13"), bd("0.1"), bd("200"));
        TaxRule cat = new TaxRule(11, 1L, null, bd("0.08"), bd("0.1"), bd("150"));
        List<TaxRule> all = new ArrayList<>(List.of(defaultTax, usTax, cat, catCountry));
        assertThat(PriceCalculator.selectTaxRule(all, 1L, "US").id()).isEqualTo(10);
        assertThat(PriceCalculator.selectTaxRule(all, 1L, "JP").id()).isEqualTo(11);
        assertThat(PriceCalculator.selectTaxRule(all, null, "US").id()).isEqualTo(2);
        assertThat(PriceCalculator.selectTaxRule(all, null, "JP").id()).isEqualTo(1);
        assertThat(PriceCalculator.selectTaxRule(all, 2L, "JP").id()).isEqualTo(1);
    }

    @Test
    void feeRule_preferredName_thenLowestId() {
        assertThat(PriceCalculator.selectFeeRule(List.of(fixed500, fee1), "해외 결제 수수료").id()).isEqualTo(1);
        assertThat(PriceCalculator.selectFeeRule(List.of(fixed500), "해외 결제 수수료").id()).isEqualTo(2);
    }

    /** 규칙이 하나도 없으면 오류 없이 세금 0 · 수수료 0, ruleMissing=true */
    @Test
    void noRules_zeroTaxAndFee() {
        Breakdown b = PriceCalculator.calculate(us("500", "0"), fx, PricingRules.empty(), NOW, false);
        assertThat(b.vatKrw()).isZero();
        assertThat(b.paymentFeeKrw()).isZero();
        assertThat(b.ruleMissing()).isTrue();
        assertThat(b.totalAmountKrw()).isEqualTo(700_000);
    }

    static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
