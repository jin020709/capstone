package com.teamsoda.globalprice.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.teamsoda.globalprice.source.MallSource.MallOffer;
import com.teamsoda.globalprice.source.MallSource.StockStatus;

class QueryAndFilterTest {

    QueryNormalizer normalizer = new QueryNormalizer(SeedAliases.load());   // 실제 별칭 데이터 (05 · 08 마이그레이션)
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

    /** 11/26: 한글로 "핸드폰 케이스" · "아이폰 17 케이스" 를 검색하면 케이스가 안 나옴 (번역 안 된 한글이 쇼핑몰로 가고, 영어 case 는 걸러짐) */
    @Test
    void koreanAccessoryQuery_isTranslatedSoCasesPassTheFilter() {
        assertThat(normalizer.normalize("핸드폰 케이스").sourceQuery()).isEqualTo("phone case");
        assertThat(normalizer.normalize("휴대폰 케이스").sourceQuery()).isEqualTo("phone case");
        String q = normalizer.normalize("아이폰 17 케이스").sourceQuery();
        assertThat(q).isEqualTo("iphone 17 case");
        // 걸러내기는 번역된 검색어로 판단하므로 영어 상품명의 case 가 통과
        assertThat(filter.accept(o("Clear Case for iPhone 17", "NEW"), q)).isTrue();
        assertThat(filter.accept(o("Clear Case for iPhone 17", "NEW"), "아이폰 17 케이스")).isFalse();   // 번역 전 (예전 동작)
        // 본품 검색은 그대로 케이스를 거름
        assertThat(filter.accept(o("Clear Case for iPhone 17", "NEW"), normalizer.normalize("아이폰 17").sourceQuery()))
                .isFalse();
    }

    @Test
    void normalize_newSeedWords() {
        assertThat(normalizer.normalize("갤럭시폴드7").sourceQuery()).isEqualTo("galaxy z fold 7");
        assertThat(normalizer.normalize("아이폰 필름").sourceQuery()).isEqualTo("iphone screen protector");
        assertThat(normalizer.normalize("자급제 갤럭시 s25").sourceQuery()).isEqualTo("unlocked galaxy s25");
        assertThat(normalizer.normalize("플스5").sourceQuery()).isEqualTo("playstation 5");
        assertThat(normalizer.normalize("맥북 에어").sourceQuery()).isEqualTo("macbook air");   // 11/18 csv 추가분도 그대로
    }

    @Test
    void normalizer_reloadsEveryTenMinutes_andKeepsLastDictionaryOnFailure() {
        java.util.concurrent.atomic.AtomicReference<java.util.Map<String, String>> db =
                new java.util.concurrent.atomic.AtomicReference<>(java.util.Map.of("케이스", "case"));
        java.util.concurrent.atomic.AtomicBoolean fail = new java.util.concurrent.atomic.AtomicBoolean();
        java.time.Instant t0 = java.time.Instant.parse("2026-11-26T00:00:00Z");
        java.util.concurrent.atomic.AtomicReference<java.time.Instant> now = new java.util.concurrent.atomic.AtomicReference<>(t0);
        java.time.Clock clock = new java.time.Clock() {
            public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
            public java.time.Clock withZone(java.time.ZoneId z) { return this; }
            public java.time.Instant instant() { return now.get(); }
        };
        QueryNormalizer n = new QueryNormalizer(() -> {
            if (fail.get()) throw new IllegalStateException("table missing");
            return db.get();
        }, clock);

        assertThat(n.normalize("케이스").sourceQuery()).isEqualTo("case");
        db.set(java.util.Map.of("케이스", "phone case"));
        assertThat(n.normalize("케이스").sourceQuery()).isEqualTo("case");          // 10분 전에는 다시 안 읽음
        now.set(t0.plus(QueryNormalizer.RELOAD_EVERY));
        assertThat(n.normalize("케이스").sourceQuery()).isEqualTo("phone case");    // 10분 지나면 다시 읽음
        fail.set(true);
        now.set(t0.plus(QueryNormalizer.RELOAD_EVERY.multipliedBy(2)));
        assertThat(n.normalize("케이스").sourceQuery()).isEqualTo("phone case");    // 실패하면 마지막 사전 유지
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
