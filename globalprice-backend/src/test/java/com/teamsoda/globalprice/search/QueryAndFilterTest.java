package com.teamsoda.globalprice.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.teamsoda.globalprice.source.MallSource.MallOffer;
import com.teamsoda.globalprice.source.MallSource.StockStatus;

class QueryAndFilterTest {

    QueryNormalizer normalizer = new QueryNormalizer();   // 실제 aliases.csv
    ResultFilter filter = new ResultFilter(List.of("used", "refurbished", "for parts", "salvage", "as-is", "중고", "리퍼"),
            List.of("case", "cover", "protector", "screen protector", "charger", "케이스", "필름"));

    @Test
    void normalize_trimsAndTranslates() {
        var q = normalizer.normalize("  아이폰   17  ");
        assertThat(q.normalized()).isEqualTo("아이폰 17");
        assertThat(q.sourceQuery()).isEqualTo("iphone 17");
        assertThat(normalizer.normalize("갤럭시s25 울트라").sourceQuery()).isEqualTo("galaxy s25 ultra");
        assertThat(normalizer.normalize("아이폰17").sourceQuery()).isEqualTo("iphone 17");
        assertThat(normalizer.normalize("프로틴").sourceQuery()).isEqualTo("프로틴");   // 사전에 없는 한글은 그대로
        assertThat(normalizer.normalize("MacBook Air").sourceQuery()).isEqualTo("MacBook Air");
    }

    @Test
    void filter_rejectsUsedAndAccessories() {
        assertThat(filter.accept(o("Apple iPhone 17 256GB", "NEW"), "iphone 17")).isTrue();
        assertThat(filter.accept(o("Apple iPhone 17", "USED"), "iphone 17")).isFalse();
        assertThat(filter.accept(o("iPhone 17 - Refurbished", null), "iphone 17")).isFalse();
        assertThat(filter.accept(o("Clear Case for iPhone 17", "NEW"), "iphone 17")).isFalse();
        assertThat(filter.accept(o("Magnetic Wallet for iPhone 17", "NEW"), "iphone 17")).isFalse();
        assertThat(filter.accept(o("아이폰 17 강화 필름", null), "아이폰 17")).isFalse();
        assertThat(filter.accept(o("Display Showcase Stand", "NEW"), "stand")).isTrue();   // 단어 경계
        // 박스 · 구성품만 파는 리스팅 (본품 없음)
        assertThat(filter.accept(o("Apple A3520 iPhone 17, Sage, 256GB - (Box & Accessories) ONLY", "NEW"), "iphone 17")).isFalse();
        assertThat(filter.accept(o("iPhone 17 Pro EMPTY BOX", "NEW"), "iphone 17")).isFalse();
        assertThat(filter.accept(o("iPhone 17 256GB new in box, only 2 left", "NEW"), "iphone 17")).isTrue();
        assertThat(filter.accept(o("Apple iPhone 17 256GB Sealed in Box", "NEW"), "iphone 17")).isTrue();
        // 실제 eBay 제목 (conditionId 1000 인데 부품용)
        assertThat(filter.accept(o("Apple iPhone 17e - 256GB - Black - as-is for salvage/parts/disassembly", "NEW"),
                "iphone 17")).isFalse();
        assertThat(filter.accept(o("iPhone 17 16 Pro Max Privacy Screen (4 PACK) Protector Anti Spy", "NEW"),
                "iphone 17")).isFalse();
    }

    @Test
    void filter_keepsAccessoryWhenUserSearchesForIt() {
        assertThat(filter.accept(o("Clear Case for iPhone 17", "NEW"), "iphone 17 case")).isTrue();
    }

    @Test
    void filter_dropsCarrierLockedPhones() {   // 11/18 실제: eBay $950 "(AT&T) with Apple Airpods pro" 가 512GB 최저가로 뜸
        ResultFilter f = new ResultFilter(List.of("used"), List.of("case"));
        assertThat(f.accept(o("Apple iPhone 17 Pro Max - 512 GB - Deep Blue (AT&T) with Apple Airpods pro", "NEW"), "iphone 17 pro max")).isFalse();
        assertThat(f.accept(o("iPhone 17 Pro 256GB Verizon", "NEW"), "iphone 17 pro")).isFalse();
        assertThat(f.accept(o("iPhone 17 Pro 256GB Unlocked (works with AT&T, T-Mobile)", "NEW"), "iphone 17 pro")).isTrue();
        assertThat(f.accept(o("Apple iPhone 17 Pro Max 512GB Dual SIM", "NEW"), "iphone 17 pro max")).isTrue();
        assertThat(f.accept(o("iPhone 17 Pro 256GB Verizon", "NEW"), "iphone 17 verizon")).isTrue();   // 통신사를 직접 검색
    }

    @Test
    void filter_dropsAliExpressEtsyAccessories() {   // 11/18 실제 AliExpress · Etsy 결과
        ResultFilter f = new ResultFilter(List.of("used"), List.of("case", "cover", "stand", "bag", "shell", "repair kit",
                "hinge", "cleaner", "brush", "keycap", "puller", "keychain", "attachment", "slimcase", "handytasche"));
        assertThat(f.accept(o("Airpod Pro 3 Cases, Poke Characters Earphone Cases", "NEW"), "airpods pro 3")).isFalse();
        assertThat(f.accept(o("5 in 1 Earphone Cleaner Brush Kit", "NEW"), "airpods pro 3")).isFalse();
        assertThat(f.accept(o("Brand For Anker Charging Base, 100W Fast Charging", "NEW"), "anker power bank")).isFalse();
        assertThat(f.accept(o("Sony WH-1000XM4 / WH-1000XM5 Headphone Stand", "NEW"), "sony wh-1000xm5")).isFalse();
        assertThat(f.accept(o("1-2PCS Keycap Puller Switch Pullers for Mechanical Keyboards", "NEW"), "mechanical keyboard")).isFalse();
        assertThat(f.accept(o("Handytasche für Xiaomi 15T", "NEW"), "xiaomi 15")).isFalse();
        // 본품은 남김
        assertThat(f.accept(o("Anker 737 Power Bank (PowerCore 24K) 140W", "NEW"), "anker power bank")).isTrue();
        assertThat(f.accept(o("Sony WH-1000XM5 Wireless Noise Canceling Headphones", "NEW"), "sony wh-1000xm5")).isTrue();
        assertThat(f.accept(o("Keychron K2 Wireless Mechanical Keyboard", "NEW"), "mechanical keyboard")).isTrue();
        assertThat(f.accept(o("Laptop Keyboard Stand", "NEW"), "keyboard stand")).isTrue();   // 거치대를 찾는 중
    }

    static MallOffer o(String title, String condition) {
        return new MallOffer("x", "DEFAULT", title, null, BigDecimal.TEN, "USD", null, condition, null, null, null,
                StockStatus.IN_STOCK, null, null);
    }
}
