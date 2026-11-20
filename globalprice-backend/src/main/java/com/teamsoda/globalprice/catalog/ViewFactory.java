package com.teamsoda.globalprice.catalog;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.teamsoda.globalprice.catalog.MallDirectory.Mall;
import com.teamsoda.globalprice.catalog.Offers.OfferData;
import com.teamsoda.globalprice.catalog.Offers.PriceStatus;
import com.teamsoda.globalprice.catalog.Offers.PricedOffer;
import com.teamsoda.globalprice.catalog.Offers.ProductGroup;
import com.teamsoda.globalprice.catalog.Views.AppliedEventView;
import com.teamsoda.globalprice.catalog.Views.CalcView;
import com.teamsoda.globalprice.catalog.Views.EventView;
import com.teamsoda.globalprice.catalog.Views.ExchangeRateView;
import com.teamsoda.globalprice.catalog.Views.OfferView;
import com.teamsoda.globalprice.catalog.Views.ProductSummary;
import com.teamsoda.globalprice.catalog.Views.RatesView;
import com.teamsoda.globalprice.pricing.PricingModels.AppliedEvent;
import com.teamsoda.globalprice.pricing.PricingModels.Breakdown;
import com.teamsoda.globalprice.pricing.PricingModels.FxRate;
import com.teamsoda.globalprice.pricing.PricingModels.FxTable;
import com.teamsoda.globalprice.pricing.PricingModels.StoreEvent;
import com.teamsoda.globalprice.source.MallSource.StockStatus;

/** 계산 결과 → 응답 JSON 모양 */
@Component
public class ViewFactory {

    public static final String BADGE_EVENT = "행사 진행 중";
    public static final String BADGE_LOW_30D = "최근 30일 최저";

    private final MallDirectory malls;

    public ViewFactory(MallDirectory malls) {
        this.malls = malls;
    }

    /** 순서: 비교 대상(예상 구매비용 → shipDays → 쇼핑몰 이름) → 품절 → 이상 가격 → 조회 불가 */
    public static final Comparator<PricedOffer> OFFER_ORDER = Comparator
            .comparingInt((PricedOffer o) -> o.applied() == null ? 3 : o.status() == PriceStatus.suspicious ? 2
                    : o.data().stock() == StockStatus.OUT_OF_STOCK ? 1 : 0)
            .thenComparing(o -> o.total() == null ? Long.MAX_VALUE : o.total())
            .thenComparing(o -> o.data().shipDays() == null ? Integer.MAX_VALUE : o.data().shipDays());

    public OfferView offer(PricedOffer p, OfferPricer.Context ctx) {
        OfferData d = p.data();
        Mall mall = ctx.malls().get(d.mallId());
        String storeName = mall == null ? "쇼핑몰 " + d.mallId() : mall.mallName();
        String country = Origin.of(d.originCountry(), d.currency(), mall == null ? null : mall.countryCode());
        Breakdown b = p.applied();
        LocalDateTime fetched = d.fetchedAt() == null ? null : d.fetchedAt().truncatedTo(ChronoUnit.SECONDS);
        CalcView calc = null;
        AppliedEventView ev = null;
        String shippingNote = null;
        java.math.BigDecimal shipping = d.shippingFee();
        if (b != null) {
            calc = new CalcView(b.productPriceKrw(), b.shippingFeeKrw(), b.discountAmountKrw(), b.customsDutyKrw(),
                    b.vatKrw(), b.paymentFeeKrw(), b.totalAmountKrw(), !b.taxExempt(), b.taxExempt(),
                    b.exemptionUsd(), b.shippingKnown());
            AppliedEvent a = b.appliedEvent();
            if (a != null) {
                ev = new AppliedEventView(a.storeEventId(), a.eventName(), a.eventType().name(),
                        a.discountType() == null ? null : a.discountType().name(), a.discountValue(),
                        a.maxDiscountAmount(), a.discountAmount());
            }
            shipping = b.shippingFeeInOfferCurrency();
            if (!b.shippingKnown()) shippingNote = "배송비 미포함";
        }
        FxRate fx = ctx.fx().get(d.currency()).orElse(null);
        ExchangeRateView rate = fx == null ? null
                : new ExchangeRateView(fx.currencyCode(), fx.effective(), fx.baseDate());
        List<Integer> deliveryDays = d.shipDays() == null ? null : List.of(d.shipDays(), d.shipDays());
        return new OfferView(d.mallProductId(), d.mallVariantId(), d.mallId(),
                mall == null ? String.valueOf(d.mallId()) : malls.storeKey(mall), storeName,
                countryName(country, d.currency(), mall == null ? null : mall.countryCode()), country, d.variantName(), d.stock().name(), p.status().name(),
                fetched, fetched, fetched, d.currency(),
                p.status() == PriceStatus.unavailable ? null : d.price(), shipping, shippingNote, d.productUrl(),
                d.imageUrl(), ev, false, d.shipDays(), deliveryDays, calc, p.total(),
                p.normal() == null ? null : p.normal().totalAmountKrw(), rate, d.listingTitle(), seller(d.productUrl()),
                colorOf(d));
    }

    public static String colorOf(OfferData d) {
        return ColorName.of(d.listingTitle() + (d.variantName() == null ? "" : " " + d.variantName()));
    }

    /** 색상별 최저 예상 구매비용 (비교 가능한 판매처 기준, 싼 색상부터). 색상이 1개 이하면 빈 목록 */
    static List<Views.ColorView> colors(List<PricedOffer> offers) {
        Map<String, long[]> by = new LinkedHashMap<>();   // 이름 → [최저가, 판매처 수]
        for (PricedOffer o : offers) {
            if (!o.comparable()) continue;
            String c = colorOf(o.data());
            if (c == null) continue;
            long[] v = by.computeIfAbsent(c, k -> new long[] { Long.MAX_VALUE, 0 });
            v[0] = Math.min(v[0], o.total());
            v[1]++;
        }
        if (by.size() < 2) return List.of();
        return by.entrySet().stream()
                .map(en -> new Views.ColorView(en.getKey(), en.getValue()[0], (int) en.getValue()[1]))
                .sorted(java.util.Comparator.comparingLong(Views.ColorView::bestTotal)).toList();
    }

    /** 상품 링크의 도메인 (www. 제외). 쇼핑몰 자체 도메인이면 null → 판매처 이름만 표시 */
    static String seller(String url) {
        if (url == null) return null;
        try {
            String host = java.net.URI.create(url.strip()).getHost();
            if (host == null) return null;
            host = host.toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) host = host.substring(4);
            for (String own : new String[] { "ebay.", "aliexpress.", "etsy.com", "amazon.", "rakuten.", "shopify.com",
                    "shop.app" }) {
                if (host.contains(own)) return null;
            }
            return host;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public ProductSummary summary(ProductGroup g, OfferPricer.Context ctx, List<String> extraBadges,
                                  CatalogRepository.StoredOffer meta, int maxOffers) {
        List<PricedOffer> sorted = g.offers().stream().sorted(OFFER_ORDER).toList();
        List<PricedOffer> comparable = sorted.stream().filter(PricedOffer::comparable).toList();
        PricedOffer best = comparable.isEmpty() ? null : comparable.getFirst();
        Long bestNormal = comparable.stream().map(PricedOffer::normal).filter(Objects::nonNull)
                .map(Breakdown::totalAmountKrw).min(Long::compare).orElse(null);
        List<String> badges = new ArrayList<>();
        if (comparable.stream().anyMatch(o -> o.applied().appliedEvent() != null)) badges.add(BADGE_EVENT);
        if (extraBadges != null) badges.addAll(extraBadges);
        List<OfferView> offers = sorted.stream().limit(maxOffers).map(o -> offer(o, ctx)).toList();
        String bestStore = best == null ? null : offers.isEmpty() ? null : offer(best, ctx).storeName();
        return new ProductSummary(g.productId(), g.productName(), null,
                meta == null ? null : meta.brandName(), meta == null ? null : meta.modelNumber(), g.imageUrl(),
                meta == null || meta.categoryName() == null ? "" : meta.categoryName(), null, null, null, badges,
                g.offers().size(), best == null ? null : best.total(), bestNormal, bestStore, offers, colors(sorted));
    }

    public EventView event(StoreEvent e, Map<Long, Mall> mallMap) {
        Mall mall = mallMap.get(e.mallId());
        return new EventView(e.id(), e.id(), mall == null ? String.valueOf(e.mallId()) : malls.storeKey(mall),
                e.mallId(), mall == null ? null : mall.mallName(), e.eventName(), e.eventType().name(),
                e.startAt().toLocalDate(), e.endAt().toLocalDate(), e.startAt(), e.endAt(), e.description(),
                e.discountType() == null ? null : e.discountType().name(), e.discountValue(),
                e.minPurchaseAmount(), e.maxDiscountAmount(), e.eventUrl(),
                mall == null || mall.mallName() == null || mall.mallName().isEmpty() ? null
                        : mall.mallName().substring(0, 1).toUpperCase(Locale.ROOT),
                e.eventType().name().equals("COUPON") ? "쿠폰" : "세일");
    }

    public RatesView rates(FxTable fx) {
        Map<String, java.math.BigDecimal> map = new LinkedHashMap<>();
        java.time.LocalDate baseDate = null;
        LocalDateTime updated = null;
        for (FxRate r : fx.rates().values()) {
            map.put(r.currencyCode(), r.effective());
            if (baseDate == null || r.baseDate().isAfter(baseDate)) baseDate = r.baseDate();
            if (updated == null || (r.recordedAt() != null && r.recordedAt().isAfter(updated))) updated = r.recordedAt();
        }
        return new RatesView("KRW", "한국수출입은행", baseDate,
                updated == null ? null : updated.truncatedTo(ChronoUnit.SECONDS), map);
    }

    /** "원화 결제"는 국내 직구관(쇼핑몰 국가 KR + KRW)만. 해외 상점의 원화 현지가는 출발국 이름 */
    static String countryName(String code, String currency, String mallCountry) {
        if ("KRW".equals(currency) && "KR".equalsIgnoreCase(mallCountry)) return "원화 결제";
        if (code == null) return "EUR".equals(currency) ? "유럽(유로)" : null;
        String name = Locale.of("", code).getDisplayCountry(Locale.KOREAN);
        return name == null || name.isBlank() || name.equals(code) ? code : name;
    }
}
