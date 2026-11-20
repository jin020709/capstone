package com.teamsoda.globalprice.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class ModelCodeTest {

    @Test
    void query_findsModelCodes_ignoringCaseHyphenSpace() {
        assertThat(ModelCode.inQuery("32M2N8800")).containsExactly("32m2n8800");
        assertThat(ModelCode.inQuery("삼성 모니터 32M2N8800")).containsExactly("32m2n8800");
        assertThat(ModelCode.inQuery("SM-S938B")).containsExactly("sms938b");
        assertThat(ModelCode.inQuery("A3520")).containsExactly("a3520");
        assertThat(ModelCode.inQuery("MG6A4J/A")).containsExactly("mg6a4j");
        assertThat(ModelCode.inQuery("LS32DG802SNXZA")).containsExactly("ls32dg802snxza");
    }

    @Test
    void query_productNamesAndUnits_areNotCodes() {
        for (String q : List.of("iphone 17 256gb", "galaxy s25 ultra", "아이폰 17", "ddr5 32gb", "rtx 4090", "iphone17 pro",
                "16Gx2", "5G 256GB", "256GB/512GB", "1920x1080 240Hz")) {
            assertThat(ModelCode.inQuery(q)).as(q).isEmpty();
        }
    }

    @Test
    void matches_onlyListingsWithThatCode() {
        List<String> c = ModelCode.inQuery("32M2N8800");
        assertThat(ModelCode.matches("Samsung 32\" M8 Smart Monitor LS32M2N8800 4K UHD", c)).isTrue();
        assertThat(ModelCode.matches("Samsung 32-M2N-8800 Smart Monitor", c)).isTrue();
        assertThat(ModelCode.matches("Samsung M8 Smart Monitor 32 inch", c)).isFalse();
        assertThat(ModelCode.matches("anything", List.of())).isTrue();   // 코드 없는 검색은 그대로
    }
}
