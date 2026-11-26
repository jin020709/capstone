package com.teamsoda.globalprice.catalog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.teamsoda.globalprice.config.GlobalPriceProperties;

import com.teamsoda.globalprice.catalog.Offers.PricedOffer;
import com.teamsoda.globalprice.catalog.Offers.ProductGroup;

/**
 * 이상 가격 판정 (명세서 4-6). 검색 · 상세 · 찜 목록 · 가격 알림 판정이 모두 이것 하나만 쓴다
 * (예전에는 화면마다 규칙이 달라 같은 상품이 검색에서는 "이상 가격", 찜 목록에서는 최저가로 나왔다).
 * <ol>
 *   <li>상품 안: 할부 · 계약금 표기, 판매처 3곳 이상이면 중간값의 1/3 미만 ({@link PriceOutliers#mark})</li>
 *   <li>같은 모델 다른 구성(용량만 다름) 2개 이상의 최저가 중간값의 1/3 미만</li>
 *   <li>다른 구성이 없으면 같은 시리즈(세대만 다름, 모델이 확실한 상품 3개 이상) 중간값의 1/5 미만</li>
 * </ol>
 * 2 의 비교 대상은 DB 에 저장된 같은 모델 상품 + 지금 판정하는 상품들 (지금 계산한 값이 우선),
 * 3 은 지금 판정하는 상품들끼리만 (같은 검색 결과 · 같은 찜 목록).
 */
@Service
public class OutlierService {

    /** 같은 모델 다른 구성 상한 (구성 수는 보통 2~6개) */
    static final int FAMILY_POOL_LIMIT = 50;

    /**
     * 비교 대상 상품 하나.
     *
     * @param family 용량 뺀 모델 키 (예: iphone|17 pro). 묶기 키가 없으면 null
     * @param series 세대 뺀 시리즈 (예: galaxy|z fold). 모르면 null
     * @param best   비교 가능한 판매처 최저 예상 구매비용 (없으면 null)
     */
    public record Member(long productId, String matchKey, String family, String series, Long best, int offerCount) {
    }

    private final CatalogRepository repository;
    private final OfferPricer pricer;
    private final Pattern installment;

    public OutlierService(CatalogRepository repository, OfferPricer pricer, GlobalPriceProperties props) {
        this.repository = repository;
        this.pricer = pricer;
        this.installment = PriceOutliers.installmentPattern(props.outliers().installmentKeywords());
    }

    /** groups 의 이상 가격을 status=suspicious 로 표시한 새 목록 (순서 유지) */
    public List<ProductGroup> judge(List<ProductGroup> groups, OfferPricer.Context ctx) {
        List<ProductGroup> marked = groups.stream().map(this::markWithin).toList();
        Map<Long, Member> pool = new LinkedHashMap<>();
        // 같은 모델 다른 구성
        Set<String> families = new java.util.LinkedHashSet<>();
        for (ProductGroup g : marked) {
            String f = family(g);
            if (f != null) families.add(f);
        }
        for (String f : families) members(f + "|", FAMILY_POOL_LIMIT, ctx).forEach(m -> pool.put(m.productId(), m));
        List<Member> current = marked.stream().map(OutlierService::member).toList();
        current.forEach(m -> pool.put(m.productId(), m));
        return judgeWith(marked, pool.values(), current);
    }

    /**
     * 2 · 3 단계 (순수 함수, 테스트용). marked 는 1단계를 마친 상품들.
     *
     * @param familyPool 같은 모델 다른 구성 비교 대상 (DB + 지금 상품들)
     * @param seriesPool 같은 시리즈 비교 대상 = 지금 상품들만. DB 전체와 비교하면 "아이폰 17 케이스" 검색의 케이스가
     *                   저장된 아이폰 본품 가격과 비교돼 전부 이상 가격이 된다 (11/26 확인)
     */
    static List<ProductGroup> judgeWith(List<ProductGroup> marked, Collection<Member> familyPool,
                                        Collection<Member> seriesPool) {
        List<ProductGroup> out = new ArrayList<>(marked.size());
        for (ProductGroup g : marked) {
            Member me = member(g);
            Long ref = familyReference(me, familyPool);
            if (ref != null) {
                out.add(g.withOffers(PriceOutliers.markBelow(g.offers(), ref)));
                continue;
            }
            Long seriesRef = seriesReference(me, seriesPool);
            out.add(seriesRef == null ? g
                    : g.withOffers(PriceOutliers.markBelow(g.offers(), seriesRef, PriceOutliers.SERIES_RATIO)));
        }
        return out;
    }

    private static Long familyReference(Member me, Collection<Member> pool) {
        if (me.family() == null) return null;
        return PriceOutliers.familyReference(pool.stream()
                .filter(m -> m.productId() != me.productId() && me.family().equals(m.family()))
                .map(Member::best).toList());
    }

    /** 기준은 모델이 확실한(묶기 키로 구성까지 아는) 상품만 */
    private static Long seriesReference(Member me, Collection<Member> pool) {
        if (me.series() == null) return null;
        List<Long> same = pool.stream()
                .filter(m -> m.productId() != me.productId() && m.family() != null && me.series().equals(m.series()))
                .map(Member::best).filter(Objects::nonNull).toList();
        return same.size() >= PriceOutliers.MIN_SERIES ? PriceOutliers.familyReference(same) : null;
    }

    /** DB 에 저장된, 묶기 키가 prefix 로 시작하는 상품들 (저장된 가격 · 쿠폰 제외 · 1단계 판정 후 최저가) */
    public List<Member> members(String keyPrefix, int limit, OfferPricer.Context ctx) {
        Map<Long, String> keys = repository.productsByKeyPrefix(keyPrefix, limit);
        if (keys.isEmpty()) return List.of();
        return pricer.groups(repository.findByProductIds(keys.keySet()), ctx, false, Set.of()).stream()
                .map(g -> member(markWithin(g))).toList();
    }

    /** 1단계: 상품 안 판정 (할부 표기 · 판매처 중간값) */
    private ProductGroup markWithin(ProductGroup g) {
        return g.withOffers(PriceOutliers.mark(g.offers(), installment));
    }

    static Member member(ProductGroup g) {
        String family = family(g);
        String series = family != null ? series(family) : seriesFromName(g.productName());
        Long best = g.offers().stream().filter(PricedOffer::comparable).map(PricedOffer::total).min(Long::compare)
                .orElse(null);
        return new Member(g.productId(), g.matchKey(), family, series, best, g.offers().size());
    }

    private static String family(ProductGroup g) {
        ProductMatcher.Config c = g.config();
        return c == null ? null : c.family();
    }

    /** 제목에 용량이 없어 모델로 안 묶인 상품도 시리즈는 알아낸다 ("Galaxy Z Fold 5 5G F946 ...") */
    private static String seriesFromName(String name) {
        ProductMatcher.Config qc = ProductMatcher.queryConfig(name);
        return qc == null ? null : series(qc.family());
    }

    /** 모델 키에서 세대를 뺀 시리즈 (galaxy|z fold|5| → galaxy|z fold). 세대 칸이 없는 키는 첫 칸 */
    static String series(String family) {
        if (family.startsWith("dev|")) return family;   // 모델 목록 기기는 세대 구분을 모름 → 같은 모델끼리만
        String[] p = family.split("\\|");
        return p.length >= 3 ? p[0] + "|" + p[1] : p[0];
    }
}
