package com.teamsoda.globalprice.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.teamsoda.globalprice.catalog.Offers.PriceStatus;
import com.teamsoda.globalprice.catalog.Offers.PricedOffer;

/**
 * 이상 가격 판정 (다른 가격비교 서비스 방식을 따름). 걸린 판매처는 지우지 않고 "이상 가격"으로 표시해
 * 최저가 · 정렬 · 비교에서만 뺀다 (품절과 같은 방식).
 * <ul>
 *   <li>할부 · 계약금 표기: 상품명에 할부 · 월 납입 · 계약금 단어가 있으면 그 가격은 일시불 총액이 아님
 *       (Google 쇼핑도 가격은 일시불 전체 금액만 받고 할부금은 따로 받음)</li>
 *   <li>이상하게 싼 가격: 같은 상품의 비교 가능한 판매처가 3곳 이상일 때, 예상 구매비용이 중간값의 1/3 보다 싸면
 *       (Open Food Facts 가격 이상값 기준). 통화 표기 오류 · 계약금 · 미끼 상품이 여기에 걸린다</li>
 *   <li>판매처가 1~2곳뿐인 상품: 같은 모델의 다른 구성(용량만 다른 상품)이 2개 이상이면, 그 최저가들의 중간값 1/3 보다
 *       싸면 이상 가격 ({@link #markBelow}). 예: Fold4 256GB · 512GB 가 70~80만원인데 1TB 가 9만원</li>
 * </ul>
 */
public final class PriceOutliers {

    static final int MIN_OFFERS = 3;
    static final double LOW_RATIO = 1.0 / 3.0;

    private static final Pattern INSTALLMENT = Pattern.compile(
            "installment|instalment|\\bmonthly\\b|/\\s*mo\\b|per\\s+month|\\bdeposit\\b|down\\s*payment|\\bansuran\\b|\\bcicilan\\b"
                    + "|\\bbulan\\b|\\bmensualit|\\braten\\b|ratenzahlung|할부|월\\s*납|계약금|分期|頭金|月々|分割");

    private PriceOutliers() {
    }

    public static boolean installment(String title) {
        return title != null && INSTALLMENT.matcher(title.toLowerCase(Locale.ROOT)).find();
    }

    static final int MIN_FAMILY = 2;

    /** 같은 모델 다른 구성들의 최저가 → 기준값(중간값). 2개 미만이면 null */
    public static Long familyReference(List<Long> otherBests) {
        List<Long> v = otherBests.stream().filter(java.util.Objects::nonNull).sorted().toList();
        if (v.size() < MIN_FAMILY) return null;
        int n = v.size();
        return n % 2 == 1 ? v.get(n / 2) : (v.get(n / 2 - 1) + v.get(n / 2)) / 2;
    }

    /** 같은 시리즈(세대만 다름, 예: Z Fold2 ~ Fold7)와 비교할 때는 구형이 원래 싸니 1/5 로 느슨하게 */
    public static final double SERIES_RATIO = 1.0 / 5.0;
    public static final int MIN_SERIES = 3;

    /** reference 의 1/3 보다 싼 비교 가능 판매처를 suspicious 로 (reference 가 null 이면 그대로) */
    public static List<PricedOffer> markBelow(List<PricedOffer> offers, Long reference) {
        return markBelow(offers, reference, LOW_RATIO);
    }

    public static List<PricedOffer> markBelow(List<PricedOffer> offers, Long reference, double ratio) {
        if (reference == null) return offers;
        List<PricedOffer> out = new ArrayList<>(offers.size());
        for (PricedOffer o : offers) {
            boolean bad = o.comparable() && o.total() < reference * ratio;
            out.add(bad ? new PricedOffer(o.data(), o.applied(), o.normal(), PriceStatus.suspicious) : o);
        }
        return out;
    }

    /** offers 중 이상 가격인 것을 status=suspicious 로 바꾼 새 목록 (순서 유지) */
    public static List<PricedOffer> mark(List<PricedOffer> offers) {
        List<Long> totals = new ArrayList<>();
        for (PricedOffer o : offers) {
            if (o.comparable() && !installment(o.data().listingTitle())) totals.add(o.total());
        }
        Long median = null;
        if (totals.size() >= MIN_OFFERS) {
            totals.sort(Long::compare);
            int n = totals.size();
            median = n % 2 == 1 ? totals.get(n / 2) : (totals.get(n / 2 - 1) + totals.get(n / 2)) / 2;
        }
        List<PricedOffer> out = new ArrayList<>(offers.size());
        for (PricedOffer o : offers) {
            boolean bad = o.applied() != null && (installment(o.data().listingTitle())
                    || (median != null && o.total() < median * LOW_RATIO));
            out.add(bad ? new PricedOffer(o.data(), o.applied(), o.normal(), PriceStatus.suspicious) : o);
        }
        return out;
    }
}
