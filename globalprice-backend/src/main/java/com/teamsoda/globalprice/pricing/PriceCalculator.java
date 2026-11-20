package com.teamsoda.globalprice.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.teamsoda.globalprice.pricing.PricingModels.AppliedEvent;
import com.teamsoda.globalprice.pricing.PricingModels.Breakdown;
import com.teamsoda.globalprice.pricing.PricingModels.DiscountType;
import com.teamsoda.globalprice.pricing.PricingModels.EventType;
import com.teamsoda.globalprice.pricing.PricingModels.FeeRule;
import com.teamsoda.globalprice.pricing.PricingModels.FxTable;
import com.teamsoda.globalprice.pricing.PricingModels.PriceInput;
import com.teamsoda.globalprice.pricing.PricingModels.PricingRules;
import com.teamsoda.globalprice.pricing.PricingModels.RateUnavailableException;
import com.teamsoda.globalprice.pricing.PricingModels.ShippingRule;
import com.teamsoda.globalprice.pricing.PricingModels.StoreEvent;
import com.teamsoda.globalprice.pricing.PricingModels.TaxRule;

/**
 * 한국 도착 예상 구매비용 계산기 (명세서 5장).
 *
 * <p>순수 함수: DB · 시계 · 네트워크에 의존하지 않는다. 현재 시각도 인자로 받는다.
 * 모든 금액은 BigDecimal 로 계산하고 항목마다 원 단위 HALF_UP 반올림 후 합산한다.
 *
 * <pre>
 * 1. 환율       : tts_rate 우선, 없으면 rate_to_krw. KRW 는 1
 * 2. 상품가     = round(price × 환율)
 * 3. 할인       : 같은 쇼핑몰 · 활성 · 기간 내 · 최소 구매금액 충족 행사 중 할인액 최대 1건. COUPON 은 couponEnabled 일 때만
 * 4. 배송비     : offer 값 > shipping_rules(무료배송 기준 이상이면 0) > 모름(0, shippingKnown=false)
 * 5. 면세 판정  : (상품가 − 할인)의 USD 환산 ≤ 면세 한도면 관세 · 부가세 0 (배송비 제외)
 * 6. 과세가격   = 상품가 − 할인 + 배송비, 관세 = round(과세가격 × 관세율), 부가세 = round((과세가격 + 관세) × 부가세율)
 *                관세 + 부가세 &lt; 10,000원이면 둘 다 0 (관세법 제40조)
 * 7. 수수료     = round((상품가 − 할인 + 배송비) × fee_rate + fixed_fee_krw). KRW 결제는 0
 * 8. 합계       = 상품가 − 할인 + 배송비 + 관세 + 부가세 + 수수료
 * </pre>
 */
public final class PriceCalculator {

    static final BigDecimal MIN_TAX_KRW = BigDecimal.valueOf(10_000);

    private PriceCalculator() {
    }

    public static Breakdown calculate(PriceInput in, FxTable fx, PricingRules rules, LocalDateTime now,
                                      boolean couponEnabled) {
        Objects.requireNonNull(in.price(), "price");
        String cur = in.currency();
        BigDecimal rate = fx.rate(cur).orElseThrow(() -> new RateUnavailableException(cur));
        boolean ruleMissing = false;

        // 2. 상품가
        BigDecimal productKrw = krw(in.price().multiply(rate));

        // 3. 할인
        AppliedEvent applied = bestEvent(in, rules.events(), now, couponEnabled);
        BigDecimal discountKrw = BigDecimal.ZERO;
        if (applied != null) {
            discountKrw = krw(applied.discountAmount().multiply(rate)).min(productKrw);
            if (discountKrw.signum() == 0) applied = null;
        }
        BigDecimal itemKrw = productKrw.subtract(discountKrw);

        // 4. 배송비
        boolean shippingKnown = true;
        BigDecimal shippingKrw;
        BigDecimal shippingInOfferCur;
        if (in.shippingFee() != null) {
            shippingInOfferCur = in.shippingFee();
            shippingKrw = krw(in.shippingFee().multiply(rate));
        } else {
            ShippingRule rule = rules.shippingRules().stream()
                    .filter(r -> r.mallId() == in.mallId()).findFirst().orElse(null);
            if (rule == null) {
                shippingKnown = false;
                shippingKrw = BigDecimal.ZERO;
                shippingInOfferCur = null;
            } else {
                BigDecimal ruleRate = fx.rate(rule.currencyCode())
                        .orElseThrow(() -> new RateUnavailableException(rule.currencyCode()));
                BigDecimal threshold = rule.freeShippingThreshold();
                boolean free = threshold != null
                        && productKrw.compareTo(krw(threshold.multiply(ruleRate))) >= 0;
                shippingKrw = free ? BigDecimal.ZERO : krw(rule.baseFee().multiply(ruleRate));
                shippingInOfferCur = rule.currencyCode().equals(cur) ? (free ? BigDecimal.ZERO : rule.baseFee())
                        : shippingKrw.divide(rate, 2, RoundingMode.HALF_UP);
            }
        }

        // 5 · 6. 관세 · 부가세
        TaxRule tax = selectTaxRule(rules.taxRules(), in.categoryId(), in.country());
        BigDecimal duty = BigDecimal.ZERO;
        BigDecimal vat = BigDecimal.ZERO;
        boolean exempt = true;
        BigDecimal exemptionUsd = null;
        if (tax == null) {
            ruleMissing = true;
        } else {
            exemptionUsd = tax.exemptionAmountUsd();
            Optional<BigDecimal> usd = fx.rate("USD");
            if (usd.isPresent()) {
                BigDecimal itemUsd = itemKrw.divide(usd.get(), 6, RoundingMode.HALF_UP);
                exempt = itemUsd.compareTo(tax.exemptionAmountUsd()) <= 0;
            } else {
                exempt = false;   // USD 환율이 없으면 면세 여부를 알 수 없음 → 보수적으로 과세
                ruleMissing = true;
            }
            if (!exempt) {
                BigDecimal taxable = itemKrw.add(shippingKrw);
                duty = krw(taxable.multiply(tax.dutyRate()));
                vat = krw(taxable.add(duty).multiply(tax.vatRate()));
                if (duty.add(vat).compareTo(MIN_TAX_KRW) < 0) {
                    duty = BigDecimal.ZERO;
                    vat = BigDecimal.ZERO;
                }
            }
        }

        // 7. 결제 수수료
        // 원화로 파는 국내 직구관(쇼핑몰 국가 KR)만 수수료 0. 해외 상점이 원화 현지가를 보여 줘도 해외결제로 본다
        BigDecimal fee = BigDecimal.ZERO;
        boolean domesticKrw = "KRW".equals(cur) && "KR".equalsIgnoreCase(in.mallCountry());
        if (!domesticKrw) {
            FeeRule feeRule = selectFeeRule(rules.feeRules(), rules.preferredFeeName());
            if (feeRule == null) {
                ruleMissing = true;
            } else {
                fee = krw(itemKrw.add(shippingKrw).multiply(feeRule.feeRate()).add(feeRule.fixedFeeKrw()));
            }
        }

        BigDecimal total = itemKrw.add(shippingKrw).add(duty).add(vat).add(fee);
        var fxRate = fx.get(cur).orElse(null);
        return new Breakdown(productKrw.longValueExact(), discountKrw.longValueExact(), shippingKrw.longValueExact(),
                duty.longValueExact(), vat.longValueExact(), fee.longValueExact(), total.longValueExact(),
                applied, shippingKnown, exempt, exemptionUsd, shippingInOfferCur,
                fxRate == null ? null : fxRate.baseDate(), rate, ruleMissing);
    }

    /** 후보 행사 중 할인액(쇼핑몰 통화)이 가장 큰 1건. 할인 정보 없는 SALE 은 0 이므로 적용되지 않는다 */
    static AppliedEvent bestEvent(PriceInput in, List<StoreEvent> events, LocalDateTime now, boolean couponEnabled) {
        AppliedEvent best = null;
        for (StoreEvent e : events) {
            if (e.mallId() != in.mallId() || !e.active()) continue;
            if (now.isBefore(e.startAt()) || now.isAfter(e.endAt())) continue;
            if (e.eventType() == EventType.COUPON && !couponEnabled) continue;
            if (e.minPurchaseAmount() != null && in.price().compareTo(e.minPurchaseAmount()) < 0) continue;
            BigDecimal amount = discountAmount(in.price(), e);
            if (amount.signum() <= 0) continue;
            if (best == null || amount.compareTo(best.discountAmount()) > 0) {
                best = new AppliedEvent(e.id(), e.eventName(), e.eventType(), e.discountType(), e.discountValue(),
                        e.maxDiscountAmount(), amount);
            }
        }
        return best;
    }

    static BigDecimal discountAmount(BigDecimal price, StoreEvent e) {
        if (e.discountType() == null || e.discountValue() == null) return BigDecimal.ZERO;
        BigDecimal v;
        if (e.discountType() == DiscountType.RATE) {
            v = price.multiply(e.discountValue());
            if (e.maxDiscountAmount() != null) v = v.min(e.maxDiscountAmount());
        } else {
            v = e.discountValue();
        }
        return v.min(price);
    }

    /** 카테고리+국가 > 카테고리 > 국가 > 기본 */
    static TaxRule selectTaxRule(List<TaxRule> rules, Long categoryId, String country) {
        return rules.stream()
                .filter(r -> r.categoryId() == null || r.categoryId().equals(categoryId))
                .filter(r -> r.originCountry() == null || r.originCountry().equalsIgnoreCase(country))
                .min(Comparator.comparingInt(PriceCalculator::taxSpecificity))
                .orElse(null);
    }

    private static int taxSpecificity(TaxRule r) {
        boolean c = r.categoryId() != null;
        boolean o = r.originCountry() != null;
        if (c && o) return 0;
        if (c) return 1;
        if (o) return 2;
        return 3;
    }

    /** 활성 수수료 규칙 중 설정된 이름이 있으면 그것, 없으면 ID 가 가장 작은 것 */
    static FeeRule selectFeeRule(List<FeeRule> rules, String preferredName) {
        if (preferredName != null) {
            for (FeeRule r : rules) {
                if (preferredName.equals(r.feeName())) return r;
            }
        }
        return rules.stream().min(Comparator.comparingLong(FeeRule::id)).orElse(null);
    }

    static BigDecimal krw(BigDecimal v) {
        return v.setScale(0, RoundingMode.HALF_UP);
    }
}
