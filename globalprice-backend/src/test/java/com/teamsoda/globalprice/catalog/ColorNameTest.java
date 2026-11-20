package com.teamsoda.globalprice.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ColorNameTest {

    @Test
    void findsColor_inSeveralLanguages_longestNameFirst() {
        assertThat(ColorName.of("Apple iPhone 17 256GB Mist Blue | DHL Express")).isEqualTo("Mist Blue");
        assertThat(ColorName.of("Apple iPhone 17 Pro 256GB シルバー")).isEqualTo("Silver");
        assertThat(ColorName.of("Apple iPhone 17 Pro 256GB Silber")).isEqualTo("Silver");
        assertThat(ColorName.of("Iphone 17 Pro (6.3'' - 256 Go) Bleu Profond")).isEqualTo("Deep Blue");
        assertThat(ColorName.of("Samsung Galaxy Z Fold8 Ultra Violet Shadow")).isEqualTo("Violet Shadow");
        assertThat(ColorName.of("갤럭시 Z 폴드8 256GB 라벤더")).isEqualTo("Lavender");
        assertThat(ColorName.of("iPhone 17 256GB or 512GB Black")).isEqualTo("Black");   // "or" 를 금색으로 보지 않음
    }

    @Test
    void multiColorOrNone_isNull() {
        assertThat(ColorName.of("Apple iPhone 17 256GB MULTI COLOR - New/Sealed")).isNull();
        assertThat(ColorName.of("Apple iPhone 17 256GB")).isNull();
        assertThat(ColorName.of(null)).isNull();
    }
}
