package com.teamsoda.globalprice.catalog;

import java.util.Locale;
import java.util.Map;

/**
 * 판매처 출발국 추정.
 * DB 에서 다시 읽은 리스팅은 출발국이 없어서 전부 쇼핑몰 나라(eBay US → 미국)로 찍혔다.
 * 쇼핑몰 API 가 준 값이 있으면 그걸 쓰고, 없으면 결제 통화가 쇼핑몰 기본 통화와 다를 때 통화의 나라로 본다
 * (예: Shopify 의 AED 상점 → 아랍에미리트, eBay 의 CAD 판매자 → 캐나다). 면세 한도($200 / $150)도 이 값으로 정해짐.
 */
public final class Origin {

    private static final Map<String, String> HOME = Map.ofEntries(
            Map.entry("USD", "US"), Map.entry("CAD", "CA"), Map.entry("AUD", "AU"), Map.entry("NZD", "NZ"),
            Map.entry("GBP", "GB"), Map.entry("JPY", "JP"), Map.entry("CNY", "CN"), Map.entry("CNH", "CN"),
            Map.entry("HKD", "HK"), Map.entry("TWD", "TW"), Map.entry("SGD", "SG"), Map.entry("AED", "AE"),
            Map.entry("CHF", "CH"), Map.entry("SEK", "SE"), Map.entry("NOK", "NO"), Map.entry("DKK", "DK"),
            Map.entry("PLN", "PL"), Map.entry("CZK", "CZ"), Map.entry("INR", "IN"), Map.entry("THB", "TH"),
            Map.entry("MYR", "MY"), Map.entry("VND", "VN"), Map.entry("PHP", "PH"), Map.entry("IDR", "ID"),
            Map.entry("MXN", "MX"), Map.entry("BRL", "BR"), Map.entry("SAR", "SA"), Map.entry("ILS", "IL"),
            Map.entry("TRY", "TR"), Map.entry("ZAR", "ZA"), Map.entry("KRW", "KR"),
            // 중동 · 기타 (11/18: 쿠웨이트 KWD 상점이 '미국 발송'으로 찍혀서 추가)
            Map.entry("KWD", "KW"), Map.entry("QAR", "QA"), Map.entry("BHD", "BH"), Map.entry("OMR", "OM"),
            Map.entry("JOD", "JO"), Map.entry("EGP", "EG"), Map.entry("PKR", "PK"), Map.entry("BDT", "BD"),
            Map.entry("LKR", "LK"), Map.entry("NGN", "NG"), Map.entry("KES", "KE"), Map.entry("MAD", "MA"),
            Map.entry("CLP", "CL"), Map.entry("COP", "CO"), Map.entry("PEN", "PE"), Map.entry("ARS", "AR"),
            Map.entry("HUF", "HU"), Map.entry("RON", "RO"), Map.entry("BGN", "BG"), Map.entry("ISK", "IS"),
            Map.entry("UAH", "UA"), Map.entry("KZT", "KZ"), Map.entry("GEL", "GE"), Map.entry("MOP", "MO"));

    private Origin() {
    }

    /** @return 두 글자 나라 코드, 유로처럼 나라를 정할 수 없으면 null */
    public static String of(String originCountry, String currency, String mallCountry) {
        if (originCountry != null && !originCountry.isBlank()) return originCountry.toUpperCase(Locale.ROOT);
        if (currency == null) return mallCountry;
        String cur = currency.toUpperCase(Locale.ROOT);
        if (mallCountry != null && cur.equals(currencyOf(mallCountry))) return mallCountry;
        if (cur.equals("KRW")) return mallCountry;          // 원화 결제는 쇼핑몰 나라 그대로 (국내 직구관 판정용)
        if (cur.equals("EUR")) return null;                 // 유로권은 나라를 알 수 없음
        return HOME.getOrDefault(cur, mallCountry);
    }

    static String currencyOf(String country) {
        for (var e : HOME.entrySet()) {
            if (e.getValue().equalsIgnoreCase(country) && !e.getKey().equals("CNH")) return e.getKey();
        }
        return null;
    }
}
