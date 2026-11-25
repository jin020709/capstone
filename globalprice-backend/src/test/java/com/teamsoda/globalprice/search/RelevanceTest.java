package com.teamsoda.globalprice.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/** 검색 순서: 이름이 정확히 맞을수록 위 (11/18 실서버 정확도 측정 결과로 만든 사례) */
class RelevanceTest {

    private static double s(String q, String name) {
        return SearchRanker.textScore(SearchRanker.tokens(q), name);
    }

    @Test
    void tokens_splitModelNumbers() {
        assertThat(SearchRanker.tokens("Galaxy Z Fold7 256 GB")).containsExactly("galaxy", "z", "fold", "7", "256gb");
        assertThat(SearchRanker.tokens("iPhone 17e")).containsExactly("iphone", "17", "e");
    }

    @Test
    void foldQuery_putsFlipBelowFold() {
        assertThat(s("galaxy z fold", "Samsung Galaxy Z Fold 7")).isGreaterThan(s("galaxy z fold", "Samsung Galaxy Z Flip4 256GB"));
        assertThat(s("galaxy z fold", "Samsung Galaxy Z Flip4 256GB")).isLessThan(0.5);
    }

    @Test
    void exactNameFirst_thenCloser() {
        assertThat(s("iphone 17 pro max", "iPhone 17 Pro Max")).isEqualTo(1.0);
        assertThat(s("iphone 17 pro max", "iPhone 17 Pro Max")).isGreaterThan(s("iphone 17 pro max", "UAG Monarch pro iphone pro max 2026 iphone 17 pro max"));
        assertThat(s("iphone 17 pro max", "UAG Monarch pro iphone pro max 2026 iphone 17 pro max")).isGreaterThan(s("iphone 17 pro max", "Apple iPhone 17 Pro 256GB"));
    }

    @Test
    void otherVariantOrGeneration_goesDown() {
        assertThat(s("iphone 17", "Apple iPhone 17 256GB")).isGreaterThan(s("iphone 17", "Apple iPhone 17e 6.1\" A3634"));
        assertThat(s("galaxy z flip 7", "Samsung Galaxy Z Flip7 FE 128GB")).isGreaterThan(s("galaxy z flip 7", "Samsung Galaxy Z Flip6 256GB"));
    }

    @Test
    void unrelated_isZero() {
        assertThat(s("mechanical keyboard", "Gaming mouse")).isZero();
        assertThat(s("", "anything")).isEqualTo(1.0);
        assertThat(SearchRanker.tokens(null)).isEqualTo(List.of());
    }
}
