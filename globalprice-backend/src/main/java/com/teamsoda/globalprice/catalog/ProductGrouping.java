package com.teamsoda.globalprice.catalog;

import java.util.Locale;

import org.springframework.stereotype.Component;

import com.teamsoda.globalprice.source.MallSource.MallOffer;

/**
 * 같은 상품 묶기 판단 (명세서 4-3 · 4-5): 리스팅을 어느 products 행에 붙일지 정한다.
 * DB 작업은 {@link Store} 로만 해서 판단 규칙을 DB 없이 단위 테스트할 수 있다 (실제 Store = {@link CatalogRepository}).
 */
@Component
public class ProductGrouping {

    /** 묶기 판단에 필요한 DB 작업 (단순 SQL 만) */
    public interface Store {
        Long productByKey(String matchKey);

        String matchKey(long productId);

        long insertProduct(String name, String imageUrl, String matchKey);

        /** 이미지가 비어 있을 때만 채움 */
        void fillImage(long productId, String imageUrl);

        void setKeyAndName(long productId, String matchKey, String name);

        /** 이름이 다를 때만 바꿈 */
        void rename(long productId, String name);

        int listingCount(long productId);

        int alertCount(long productId);

        void moveListing(long mallProductId, long productId);

        /** from 의 찜을 to 로 옮김 (to 에 이미 찜한 사용자 것은 그대로 둠) */
        void moveWishlists(long fromProductId, long toProductId);

        void deleteProduct(long productId);
    }

    private final Store store;

    public ProductGrouping(Store store) {
        this.store = store;
    }

    /** 묶기 판단에 쓰는 글자: 상품명 + 옵션 이름 (Shopify 는 용량이 옵션에만 있음) */
    public static String matchText(MallOffer o) {
        String title = o.title() == null ? "" : o.title();
        String v = o.variantName() == null ? "" : o.variantName().strip();
        // 옵션 이름이 상품명을 그대로 담고 있으면 (Shopify 일부 상점) 두 번 붙이지 않음
        if (v.isEmpty() || title.toLowerCase(Locale.ROOT).contains(v.toLowerCase(Locale.ROOT))) return title;
        return title + " " + v;
    }

    /** 처음 보는 리스팅의 상품: 같은 묶기 키의 상품이 있으면 그것, 없으면 새로 만든다 */
    public long productForNewListing(ProductMatcher.Match match, String listingTitle, String image) {
        if (match == null) return store.insertProduct(listingTitle, image, null);
        Long existing = store.productByKey(match.key());
        if (existing != null) {
            if (image != null) store.fillImage(existing, image);
            return existing;
        }
        // 동시에 같은 키를 처음 넣으면 UNIQUE 위반 → CatalogService.save 가 한 번 더 시도해서 위 경로로 감
        return store.insertProduct(productName(match, listingTitle), image, match.key());
    }

    /**
     * 이미 저장된 리스팅의 상품 정리. 지금 상품명으로 만든 묶기 키가 상품의 키와 다르면 맞는 상품으로 옮긴다.
     * 예전 방식(키 없는 상품 1 : 리스팅 1)이나, 판단 규칙이 나중에 고쳐진 경우("iPhone 17 Air" → iPhone Air)도
     * 다음 검색 때 저절로 바로잡힌다.
     * <ul>
     *   <li>리스팅이 혼자인 상품이고 맞는 키의 상품이 아직 없으면 → 이 상품에 키를 달고 대표 이름으로 바꿈</li>
     *   <li>그 밖에는 → 리스팅을 맞는 키의 상품(없으면 새로 만듦)으로 옮김. 빈 상품은 찜을 옮긴 뒤 지움.
     *       혼자인 상품에 가격 알림이 걸려 있으면 알림이 끊기지 않게 그대로 둠</li>
     * </ul>
     *
     * @return 리스팅이 붙은 상품 id
     */
    public long productForExistingListing(long mallProductId, long productId, ProductMatcher.Match match,
                                          String listingTitle, String image) {
        if (match == null) return keepImage(productId, image);
        String currentKey = store.matchKey(productId);
        if (match.key().equals(currentKey)) {
            // 예전에 상품명이 두 번 붙어 저장된 것 정리 (모델코드 · 상품명 키 상품은 리스팅 이름 그대로)
            if (keepsListingName(match)) store.rename(productId, listingTitle);
            return keepImage(productId, image);
        }
        boolean alone = store.listingCount(productId) == 1;
        Long target = store.productByKey(match.key());
        if (target == null && alone) {
            store.setKeyAndName(productId, match.key(), productName(match, listingTitle));
            return keepImage(productId, image);
        }
        if (alone && store.alertCount(productId) > 0) return keepImage(productId, image);
        if (target == null) target = store.insertProduct(productName(match, listingTitle), image, match.key());
        store.moveListing(mallProductId, target);
        if (alone) {
            store.moveWishlists(productId, target);
            store.deleteProduct(productId);   // 옮기지 못한 중복 찜은 CASCADE 로 정리
        }
        return keepImage(target, image);
    }

    private long keepImage(long productId, String image) {
        if (image != null) store.fillImage(productId, image);
        return productId;
    }

    /** 모델코드 · 상품명 키는 리스팅 원래 이름이 대표 이름 */
    private static boolean keepsListingName(ProductMatcher.Match match) {
        return match.key().startsWith("t:") || match.key().startsWith("code:");
    }

    /** 상품 대표 이름: 모델 규칙으로 묶이면 정리된 이름, 모델코드 · 상품명 키면 리스팅 원래 이름 */
    static String productName(ProductMatcher.Match match, String listingTitle) {
        return keepsListingName(match) ? listingTitle : CatalogRepository.cut(match.name(), 255);
    }
}
