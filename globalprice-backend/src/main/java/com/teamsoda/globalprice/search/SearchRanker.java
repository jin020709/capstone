package com.teamsoda.globalprice.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;

import com.teamsoda.globalprice.catalog.MallDirectory.Mall;
import com.teamsoda.globalprice.catalog.OfferPricer;
import com.teamsoda.globalprice.catalog.Offers.PricedOffer;
import com.teamsoda.globalprice.catalog.Offers.ProductGroup;
import com.teamsoda.globalprice.catalog.ProductMatcher;

/**
 * 검색 결과 정렬 (명세서 6-2).
 * <ul>
 *   <li>{@link #relevance}: 검색어와 얼마나 맞는지 (price_asc 에서 가격순 앞에 적용)</li>
 *   <li>{@link #order}: 예상 구매비용 → shipDays → 쇼핑몰 이름, 품절은 맨 아래. discount_desc 는 할인율 높은 순</li>
 * </ul>
 */
final class SearchRanker {

    private SearchRanker() {
    }

    /** 모델을 가르는 말: 검색어에 없는데 상품명에 있으면 다른 모델일 가능성이 큼 (fold 검색 → Flip, iphone 17 → 17e) */
    private static final Set<String> VARIANT = Set.of(
            "pro", "max", "plus", "ultra", "mini", "air", "flip", "fold", "fe", "lite", "se", "xl", "edge", "slim", "e");
    /** 상품명에 흔히 붙지만 어떤 상품인지와 무관한 말 (정밀도 계산에서 뺌) */
    private static final Set<String> NOISE = Set.of(
            "apple", "samsung", "google", "sony", "new", "unlocked", "dual", "sim", "esim", "physical", "5g", "4g", "lte",
            "wifi", "wi", "fi", "cellular", "the", "and", "with", "in", "edition", "version", "global", "gb", "tb", "inch");

    /**
     * 검색어와 얼마나 맞는지. 다나와처럼 "이름이 정확히 일치하는 상품이 맨 위, 덜 맞을수록 아래".
     * <ol>
     *   <li>가격이 없는 상품(이상 가격 · 조회 불가 · 품절만)은 맨 아래</li>
     *   <li>묶기 규칙이 있는 모델(아이폰 · 갤럭시 · 픽셀 · 맥북 · 아이패드)이 검색어와 같은 모델 → 맨 위 (용량까지 같으면 더 위)</li>
     *   <li>나머지는 {@link #textScore} 점수 순 (0.1 단위로 끊어서, 비슷한 점수끼리는 가격순)</li>
     *   <li>같은 단계 안에서 모델 묶음끼리는 "그 모델의 최저가" 순 (가나다순이면 Fold2 → Fold4 처럼 구형이 위로 옴),
     *       묶음 안에서는 작은 구성부터 (256 → 512 → 1TB)</li>
     * </ol>
     * 모델은 저장할 때 정한 묶기 키(products.match_key)로 판단한다.
     */
    static Comparator<ProductGroup> relevance(String sourceQuery, String rawQuery, List<ProductGroup> groups) {
        ProductMatcher.Config q = ProductMatcher.queryConfig(sourceQuery);
        if (q == null) q = ProductMatcher.queryConfig(rawQuery);
        final ProductMatcher.Config want = q;
        List<String> qTokens = tokens(sourceQuery == null || sourceQuery.isBlank() ? rawQuery : sourceQuery);
        Map<Long, ProductMatcher.Config> configCache = new HashMap<>();
        Function<ProductGroup, ProductMatcher.Config> conf = g -> configCache.computeIfAbsent(g.productId(),
                id -> g.config());
        Map<Long, Integer> rankCache = new HashMap<>();
        ToIntFunction<ProductGroup> rank = g -> rankCache.computeIfAbsent(g.productId(), id -> {
            ProductMatcher.Config c = conf.apply(g);
            if (want != null && c != null && c.family().equals(want.family())) {
                return want.storageGb() == null || want.storageGb().equals(c.storageGb()) ? 0 : 1;
            }
            // 2(완전 일치) ~ 12(전혀 안 맞음)
            return 2 + (int) Math.round((1.0 - textScore(qTokens, g.productName())) * 10);
        });
        ToLongFunction<ProductGroup> best = g -> g.offers().stream()
                .filter(PricedOffer::comparable).mapToLong(PricedOffer::total).min().orElse(Long.MAX_VALUE);
        Map<String, Long> familyMin = new HashMap<>();
        for (ProductGroup g : groups) {
            ProductMatcher.Config c = conf.apply(g);
            if (c != null) familyMin.merge(c.family() + "#" + rank.applyAsInt(g), best.applyAsLong(g), Math::min);
        }
        return Comparator.comparingInt((ProductGroup g) -> best.applyAsLong(g) == Long.MAX_VALUE ? 1 : 0)
                .thenComparingInt(rank)
                .thenComparingLong(g -> {
                    ProductMatcher.Config c = conf.apply(g);
                    return c == null ? best.applyAsLong(g)
                            : familyMin.getOrDefault(c.family() + "#" + rank.applyAsInt(g), best.applyAsLong(g));
                })
                .thenComparing(g -> {
                    ProductMatcher.Config c = conf.apply(g);
                    return c == null ? "~" : c.family();
                })
                .thenComparingLong(g -> {
                    ProductMatcher.Config c = conf.apply(g);
                    return c == null ? 0L : c.order();
                });
    }

    /**
     * 검색어 단어가 상품명에 얼마나 들어 있는지 0~1.
     * <ul>
     *   <li>포함률: 검색어 단어 중 상품명에 있는 비율 (가장 중요)</li>
     *   <li>정밀도: 상품명이 검색어 말고 다른 말을 얼마나 덧붙였는지 (짧고 딱 맞을수록 높음)</li>
     *   <li>다른 모델 감점: 검색어에 없는 모델 구분 말(Flip · Pro · Max · e …)이 붙으면 하나당 0.3</li>
     *   <li>다른 세대 감점: 검색어의 모델 번호가 상품명에 없으면 하나당 0.2</li>
     * </ul>
     */
    static double textScore(List<String> qTokens, String name) {
        if (qTokens.isEmpty()) return 1.0;
        Set<String> nSet = new HashSet<>(tokens(name));
        Set<String> qSet = new HashSet<>(qTokens);
        long matched = qSet.stream().filter(nSet::contains).count();
        double coverage = (double) matched / qSet.size();
        long extra = nSet.stream().filter(t -> !qSet.contains(t) && !NOISE.contains(t) && !t.matches("\\d+(gb|tb)?")).count();
        double precision = matched == 0 ? 0 : (double) matched / (matched + Math.min(extra, 10));
        long otherModel = nSet.stream().filter(t -> VARIANT.contains(t) && !qSet.contains(t)).count();
        // 검색어의 모델 번호(17, 7, 25 …)가 상품명에 없으면 다른 세대 → 한 번 더 감점 (Flip 7 검색 → Flip6)
        long missingNumber = qSet.stream().filter(t -> t.matches("\\d{1,4}") && !nSet.contains(t)).count();
        double s = coverage * (0.6 + 0.4 * precision) - 0.3 * otherModel - 0.2 * missingNumber;
        return Math.max(0, Math.min(1, s));
    }

    /** 소문자 · 글자와 숫자 사이를 띄움 (fold7 → fold 7, 17e → 17 e, s25 → s 25) · 기호로 나눔 */
    static List<String> tokens(String s) {
        if (s == null) return List.of();
        String t = s.toLowerCase(Locale.ROOT)
                .replaceAll("(\\d+)\\s*(gb|tb)\\b", "$1$2")              // 256 GB → 256gb (용량은 한 덩어리)
                .replaceAll("([a-z])(\\d)", "$1 $2")
                .replaceAll("(\\d)([a-z])", "$1 $2")
                .replaceAll("(\\d+) (gb|tb)\\b", "$1$2");
        List<String> out = new ArrayList<>();
        for (String w : t.split("[^a-z0-9가-힣]+")) if (!w.isEmpty()) out.add(w);
        return out;
    }

    /** 예상 구매비용 오름차순 → shipDays → 쇼핑몰 이름. 품절은 맨 아래. discount_desc 는 할인율 높은 순 */
    static Comparator<ProductGroup> order(SearchService.Sort sort, OfferPricer.Context ctx) {
        Comparator<ProductGroup> soldOutLast = Comparator.comparingInt(g -> g.best().comparable() ? 0 : 1);
        Comparator<ProductGroup> byPrice = Comparator
                .comparingLong((ProductGroup g) -> g.best().total())
                .thenComparingInt(g -> g.best().data().shipDays() == null ? Integer.MAX_VALUE
                        : g.best().data().shipDays())
                .thenComparing(g -> {
                    Mall m = ctx.malls().get(g.best().data().mallId());
                    return m == null ? "" : m.mallName();
                });
        if (sort == SearchService.Sort.discount_desc) {
            Comparator<ProductGroup> byDiscount = Comparator.comparingDouble((ProductGroup g) -> {
                PricedOffer b = g.best();
                long normal = b.normal().totalAmountKrw();
                return normal == 0 ? 0 : -(double) (normal - b.total()) / normal;
            });
            return soldOutLast.thenComparing(byDiscount).thenComparing(byPrice);
        }
        return soldOutLast.thenComparing(byPrice);
    }
}
