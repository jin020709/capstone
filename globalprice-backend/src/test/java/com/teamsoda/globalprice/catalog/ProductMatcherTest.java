package com.teamsoda.globalprice.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.teamsoda.globalprice.catalog.ProductMatcher.Match;

/** 같은 상품 묶기 키 (다나와식). 잘못 묶는 것보다 덜 묶는 쪽을 택한다 */
class ProductMatcherTest {

    private static String key(String title) {
        Match m = ProductMatcher.match(title);
        return m == null ? null : m.key();
    }

    private static void same(String a, String b) {
        assertThat(key(a)).as(a).isNotNull().doesNotStartWith("t:").isEqualTo(key(b));
    }

    private static void differ(String a, String b) {
        String x = key(a);
        String y = key(b);
        if (x != null && y != null) assertThat(x).as(a + " / " + b).isNotEqualTo(y);
    }

    private static void noModelKey(String title) {
        String k = key(title);
        if (k != null) assertThat(k).as(title).startsWith("t:");
    }

    private static void name(String title, String expected) {
        assertThat(ProductMatcher.match(title).name()).isEqualTo(expected);
    }

    @Test
    void iphone_sameModelAndStorage_ignoresColorCarrierAndShippingText() {
        same("Apple iPhone 17 256GB Mist Blue | DHL Express: With you tomorrow!", "Apple iPhone 17 256GB Black Unlocked");
        same("iPhone 17 512GB Mist Blue", "iPhone 17 512GB Sage");
        same("Apple IPhone 17 Pro Max 1TB", "Apple iPhone 17 Pro Max 1 TB Cosmic Orange eSIM");
        same("Apple iPhone 17 Pro Max 256GB", "APPLE IPHONE 17 PROMAX 256GB DEEP BLUE");
        same("Apple iPhone 17 Pro 256GB / Cosmic Orange", "Apple iPhone 17 Pro 256GB Silver");
        name("APPLE IPHONE 17 PROMAX 256GB", "Apple iPhone 17 Pro Max 256GB");
        name("iphone17pro 512gb", "Apple iPhone 17 Pro 512GB");
        name("Apple iPhone Air 256GB Sky Blue", "Apple iPhone Air 256GB");
        // 실제 검색 결과에서 나온 표기들 (11/17 로컬 테스트)
        same("iphone 17 pro max 256g new/new sealed/sealed", "Apple iPhone 17 Pro Max 256GB");
        same("Apple Iphone 17 5g 256gb White Eu", "Apple iPhone 17 256GB Black");
        same("Apple iPhone 17 (pSIM) 256GB White 6.3\" Unlocked Smartphone", "iPhone 17 256GB Mist Blue");
        same("Apple iPhone 17 256GB - Schwarz, Neu OVP Versiegelt, ohne Simlock", "iPhone 17 256GB");
        same("Apple iPhone 17 Air 256GB [ WHITE - SPACE BLACK - SKY BLUE ]", "Apple iPhone Air 256GB Sky Blue");
        differ("Apple iPhone 17 Air 256GB", "Apple iPhone 17 256GB");
        same("Apple iPhone 17 Pro 256 Go, orange cosmique (MG8H4ZD/A)", "Apple iPhone 17 Pro 256GB");   // 프랑스어 Go = GB
    }

    @Test
    void iphone_differentModelOrStorage_notGrouped() {
        differ("Apple iPhone 17 256GB", "Apple iPhone 17e 256GB");
        differ("Apple iPhone 17 256GB", "Apple iPhone 17 Pro 256GB");
        differ("Apple iPhone 17 Pro 256GB", "Apple iPhone 17 Pro Max 256GB");
        differ("Apple iPhone 17 256GB", "Apple iPhone 17 512GB");
        differ("Apple iPhone 17 256GB", "Apple iPhone 16 256GB");
    }

    @Test
    void ambiguousOrBundle_noModelKey() {
        noModelKey("[Ansuran 36 Bulan - Kad Debit] New Apple iPhone 17 Pro | 256GB · 512GB · 1TB");
        noModelKey("Apple iPhone 17 Pro / 17 Pro Max 256GB");
        noModelKey("Apple iPhone 17");
        noModelKey("Apple iPhone 17 Pro 256GB Bundle with AirPods");
        // 이름(S25)과 모델번호(S25 Ultra)가 다르면 이름 규칙은 포기하고 모델코드로 묶음
        assertThat(key("Samsung Galaxy S25 256GB SM-S938B")).isEqualTo("code:sms938b");
    }

    @Test
    void galaxy_nameAndModelCode() {
        same("Samsung Galaxy S25 Ultra 256GB Titanium Black", "SAMSUNG GALAXY S25 ULTRA 5G 12GB RAM 256GB SM-S938B/DS");
        same("Samsung Galaxy S25+ 512GB", "Galaxy S25 Plus 512GB Navy");
        same("Samsung SM-S931U 128GB Unlocked", "Samsung Galaxy S25 128GB Icyblue");
        same("Samsung Galaxy Z Fold7 512GB", "Galaxy Z Fold 7 512GB Blue Shadow");
        differ("Samsung Galaxy S25 256GB", "Samsung Galaxy S25 Ultra 256GB");
        differ("Samsung Galaxy S25 256GB", "Samsung Galaxy S25 FE 256GB");
        name("Galaxy Z Fold 7 512GB Blue Shadow", "Samsung Galaxy Z Fold7 512GB");
        name("SAMSUNG GALAXY S25 ULTRA 5G 12GB RAM 256GB SM-S938B/DS", "Samsung Galaxy S25 Ultra 256GB");
    }

    @Test
    void pixelMacbookIpad() {
        same("Google Pixel 10 Pro XL 256GB Obsidian", "Pixel 10 Pro XL 256 GB Moonstone Unlocked");
        differ("Google Pixel 10 Pro 256GB", "Google Pixel 10 Pro XL 256GB");
        same("Apple MacBook Air 13-inch M4 16GB RAM 512GB SSD Sky Blue", "MacBook Air 13 M4 16GB 512GB Midnight");
        differ("Apple MacBook Air 13-inch M4 16GB 512GB", "Apple MacBook Air 15-inch M4 16GB 512GB");
        differ("Apple MacBook Pro 14\" M4 Pro 24GB 1TB", "Apple MacBook Pro 14\" M4 Max 36GB 1TB");
        noModelKey("Apple MacBook Air M4 16GB 512GB");   // 화면 크기 없음
        name("Apple MacBook Pro 14\" M4 Pro 24GB 1TB", "Apple MacBook Pro 14 M4 Pro 24GB 1TB");
        same("Apple iPad Pro 11-inch M4 256GB Wi-Fi Space Black", "iPad Pro 11\" (M4) 256GB WiFi Silver");
        differ("Apple iPad Pro 11-inch M4 256GB Wi-Fi", "Apple iPad Pro 11-inch M4 256GB Wi-Fi + Cellular");
        name("Apple iPad 11th Gen 128GB Wi-Fi Blue", "Apple iPad 11th Gen 128GB Wi-Fi");
    }

    @Test
    void config_sameModelDifferentStorage_isOneFamily() {
        ProductMatcher.Config a = ProductMatcher.config(key("Apple iPhone 17 Pro 256GB"));
        ProductMatcher.Config b = ProductMatcher.config(key("Apple iPhone 17 Pro 1TB Silver"));
        assertThat(a.family()).isEqualTo(b.family()).isEqualTo("iphone|17 pro");
        assertThat(a.label()).isEqualTo("256GB");
        assertThat(b.label()).isEqualTo("1TB");
        assertThat(b.storageGb()).isEqualTo(1024);
        assertThat(ProductMatcher.config(key("Apple iPhone 17 256GB")).family()).isNotEqualTo(a.family());
        assertThat(ProductMatcher.config(key("Apple MacBook Air 13-inch M4 16GB RAM 512GB SSD")).label()).isEqualTo("16GB · 512GB");
        assertThat(ProductMatcher.config(key("Apple iPad Pro 11-inch M4 256GB Wi-Fi + Cellular")).label())
                .isEqualTo("256GB Wi-Fi + Cellular");
        assertThat(ProductMatcher.config("code:ls32m2n8800")).isNull();
        assertThat(ProductMatcher.config(null)).isNull();
    }

    @Test
    void queryConfig_findsModel_withOrWithoutStorage() {
        ProductMatcher.Config a = ProductMatcher.queryConfig("iphone 17 pro 256");   // GB 없이 친 용량
        assertThat(a.family()).isEqualTo("iphone|17 pro");
        assertThat(a.storageGb()).isEqualTo(256);
        ProductMatcher.Config b = ProductMatcher.queryConfig("galaxy z fold8");
        assertThat(b.family()).isEqualTo("galaxy|z fold|8|");
        assertThat(b.storageGb()).isNull();
        assertThat(ProductMatcher.queryConfig("samsung monitor")).isNull();
        // 비슷한 모델: 폴드8 ↔ 폴드8 울트라, 17 Pro ↔ 17 Pro Max
        assertThat(ProductMatcher.similarBase("galaxy|z fold|8|ultra")).isEqualTo(ProductMatcher.similarBase(b.family()));
        assertThat(ProductMatcher.similarBase("iphone|17 pro max")).isEqualTo(ProductMatcher.similarBase(a.family()));
    }

    @Test
    void generatedName_readsBackToSameKey() {   // 검색 순서 · 체크표가 상품 이름을 다시 읽어서 씀
        for (String t : List.of("Apple iPhone 17 Pro Max 256GB Cosmic Orange", "Samsung Galaxy Z Fold8 Ultra 512GB Graphite",
                "Galaxy Z Fold 7 512GB Blue Shadow", "Google Pixel 10 Pro XL 256GB Obsidian",
                "Apple MacBook Air 13-inch M4 16GB RAM 512GB SSD", "Apple iPad Pro 11-inch M4 256GB Wi-Fi + Cellular",
                "Apple iPad 11th Gen 128GB Wi-Fi Blue", "Apple iPad mini A17 Pro 128GB Wi-Fi")) {
            Match m = ProductMatcher.match(t);
            assertThat(ProductMatcher.match(m.name()).key()).as(t).isEqualTo(m.key());
        }
    }

    @Test
    void otherProducts_onlyIdenticalTitles() {
        assertThat(key("Studio 2 Wireless On-Ear Headphones Coral"))
                .isEqualTo(key("Studio 2  Wireless On-Ear Headphones Coral"));
        differ("Logitech Wireless Mouse Black", "Logitech Wireless Mouse White");
        // 모델코드가 하나뿐인 상품은 코드로 묶음 (색상 무시)
        assertThat(key("JBL Tune 520BT Wireless on-ear Headphones - Black"))
                .isEqualTo(key("JBL Tune 520BT Wireless on-ear Headphones - White")).isEqualTo("code:520bt");
        assertThat(key("Samsung 32\" M8 Smart Monitor LS32M2N8800 4K UHD 60Hz")).isEqualTo("code:ls32m2n8800");
        assertThat(ProductMatcher.match(null)).isNull();
        assertThat(ProductMatcher.match("  ")).isNull();
    }
}
