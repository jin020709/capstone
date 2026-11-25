package com.teamsoda.globalprice.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import com.teamsoda.globalprice.catalog.Offers.PricedOffer;
import com.teamsoda.globalprice.catalog.Offers.ProductGroup;
import com.teamsoda.globalprice.catalog.ProductMatcher;

/** 다나와식 상단 체크표 (명세서 6-2 facets): 결과에 있는 제조사 · 모델 · 용량 · 색상으로 칸을 만들고, 고른 값으로 거른다 */
public final class FacetBuilder {

    private FacetBuilder() {
    }

    /** 체크표에서 고른 값 (비어 있으면 거르지 않음). 값은 소문자 */
    public record FacetFilter(Set<String> brands, Set<String> models, Set<String> storages, Set<String> colors) {
        public static final FacetFilter NONE = new FacetFilter(Set.of(), Set.of(), Set.of(), Set.of());
    }

    /** 체크표 한 칸: value 는 다시 보낼 값, count 는 그 값을 가진 상품 수 */
    public record FacetOption(String value, String label, int count) {
    }

    /** 이번 검색 결과에 실제로 있는 제조사 · 모델 · 용량 · 색상 (체크하기 전 기준) */
    public record Facets(List<FacetOption> brands, List<FacetOption> models, List<FacetOption> storages,
                         List<FacetOption> colors) {
    }

    /** 상품 하나의 체크표 값. 묶기 키(products.match_key)에 모델 규칙이 없는 상품은 brand · model · storage 가 null */
    record FacetInfo(String brand, String model, String modelLabel, Integer storageGb) {
        private static final Pattern TAIL = Pattern.compile("(\\s\\d+\\s?(GB|TB))+(\\sWi-Fi.*)?$");

        static FacetInfo of(ProductGroup g) {
            ProductMatcher.Config c = g.config();
            if (c == null) return new FacetInfo(null, null, null, null);
            String brand = switch (c.family().split("\\|")[0]) {
                case "iphone", "ipad", "macbook" -> "Apple";
                case "galaxy" -> "Samsung";
                case "pixel" -> "Google";
                default -> null;
            };
            return new FacetInfo(brand, c.family(), TAIL.matcher(g.productName()).replaceFirst("").strip(),
                    c.storageGb());
        }
    }

    /** 칸 만들기 + 고른 값으로 거르기. 칸은 거르기 전 결과 기준 */
    record Result(Facets facets, List<ProductGroup> groups) {
    }

    static Result apply(List<ProductGroup> groups, FacetFilter filter) {
        Map<Long, FacetInfo> infos = new LinkedHashMap<>();
        for (ProductGroup g : groups) infos.put(g.productId(), FacetInfo.of(g));
        return new Result(facets(groups, infos), applyFacets(groups, infos, filter));
    }

    static Facets facets(List<ProductGroup> groups, Map<Long, FacetInfo> infos) {
        Map<String, int[]> brands = new LinkedHashMap<>();
        Map<String, String> modelLabels = new LinkedHashMap<>();
        Map<String, int[]> models = new LinkedHashMap<>();
        TreeMap<Integer, int[]> storages = new TreeMap<>();
        Map<String, int[]> colors = new LinkedHashMap<>();
        for (ProductGroup g : groups) {
            FacetInfo f = infos.get(g.productId());
            if (f.brand() != null) brands.computeIfAbsent(f.brand(), k -> new int[1])[0]++;
            if (f.model() != null) {
                models.computeIfAbsent(f.model(), k -> new int[1])[0]++;
                modelLabels.putIfAbsent(f.model(), f.modelLabel());
            }
            if (f.storageGb() != null) storages.computeIfAbsent(f.storageGb(), k -> new int[1])[0]++;
            g.offers().stream().map(o -> o.data().color()).filter(Objects::nonNull).distinct()
                    .forEach(c -> colors.computeIfAbsent(c, k -> new int[1])[0]++);
        }
        Comparator<FacetOption> byCount = Comparator.comparingInt(FacetOption::count).reversed();
        return new Facets(
                brands.entrySet().stream().map(e -> new FacetOption(e.getKey(), e.getKey(), e.getValue()[0]))
                        .sorted(byCount).toList(),
                models.entrySet().stream()
                        .map(e -> new FacetOption(e.getKey(), modelLabels.get(e.getKey()), e.getValue()[0]))
                        .sorted(byCount).toList(),
                storages.entrySet().stream().map(e -> new FacetOption(String.valueOf(e.getKey()),
                        e.getKey() >= 1024 && e.getKey() % 1024 == 0 ? e.getKey() / 1024 + "TB" : e.getKey() + "GB",
                        e.getValue()[0])).toList(),
                colors.entrySet().stream().map(e -> new FacetOption(e.getKey(), e.getKey(), e.getValue()[0]))
                        .sorted(byCount).toList());
    }

    /** 칸끼리는 AND, 한 칸 안에서 여러 개를 고르면 OR. 색상을 고르면 그 색상 판매처만 남김 (가격도 그 색상 기준) */
    static List<ProductGroup> applyFacets(List<ProductGroup> groups, Map<Long, FacetInfo> infos, FacetFilter f) {
        if (f == null) return groups;
        List<ProductGroup> out = new ArrayList<>();
        for (ProductGroup g : groups) {
            FacetInfo i = infos.get(g.productId());
            if (!f.brands().isEmpty() && (i.brand() == null || !f.brands().contains(i.brand().toLowerCase(Locale.ROOT)))) continue;
            if (!f.models().isEmpty() && (i.model() == null || !f.models().contains(i.model()))) continue;
            if (!f.storages().isEmpty() && (i.storageGb() == null || !f.storages().contains(String.valueOf(i.storageGb())))) continue;
            if (!f.colors().isEmpty()) {
                List<PricedOffer> kept = g.offers().stream().filter(o -> {
                    String c = o.data().color();
                    return c != null && f.colors().contains(c.toLowerCase(Locale.ROOT));
                }).toList();
                if (kept.isEmpty()) continue;
                g = g.withOffers(kept);
            }
            out.add(g);
        }
        return out;
    }
}
