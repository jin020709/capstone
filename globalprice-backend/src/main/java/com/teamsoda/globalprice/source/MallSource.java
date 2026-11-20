package com.teamsoda.globalprice.source;

import java.math.BigDecimal;
import java.util.List;

/**
 * 해외 쇼핑몰 소스. 새 쇼핑몰 = 구현체 1개 + api_sources 1행 + shopping_malls 1행.
 * 실패는 {@link SourceException} 으로 올린다.
 */
public interface MallSource {

    /** api_sources.source_name (예: EBAY_BROWSE) */
    String sourceName();

    /** 화면 · 필터용 키 (예: ebay). 프론트 stores 파라미터 값 */
    String storeKey();

    boolean enabled();

    /** 이 소스의 하루 호출 상한 (메모리 카운터) */
    int dailyBudget();

    List<MallOffer> search(String query, SearchOptions options);

    /** 저장된 상품 가격 갱신. 응답에 없는 ID 는 결과에서 빠진다 */
    List<MallOffer> refresh(List<String> externalProductIds);

    /** refresh 한 번에 넘길 수 있는 최대 ID 수 */
    default int refreshBatchSize() {
        return 1;
    }

    enum StockStatus { IN_STOCK, OUT_OF_STOCK, UNKNOWN }

    record SearchOptions(int limit) {
    }

    /**
     * 쇼핑몰 판매 상품 1건 (옵션 1개).
     * @param externalVariantId 옵션 구분이 없으면 "DEFAULT"
     * @param shippingFee       한국 배송비를 응답으로 받으면 값, 모르면 null
     * @param originCountry     모르면 null → shopping_malls.country_code
     */
    record MallOffer(String externalProductId, String externalVariantId, String title, String variantName,
                     BigDecimal price, String currencyCode, BigDecimal shippingFee, String condition,
                     String originCountry, String productUrl, String imageUrl, StockStatus stock, Integer shipDays,
                     String gtin) {
    }
}
