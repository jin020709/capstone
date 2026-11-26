package com.teamsoda.globalprice.catalog;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 제조사 모델코드 (예: 32M2N8800 · SM-S938B · A3520 · MG6A4J/A).
 * <ul>
 *   <li>검색어에 모델코드가 있으면 그 코드가 상품명에 정확히 들어간 리스팅만 보여 준다 → 박스 · 케이스 · 다른 모델이 안 섞임</li>
 *   <li>상품명에 모델코드가 하나뿐이면 같은 코드끼리 같은 상품으로 묶는다</li>
 * </ul>
 * 비교할 때는 대소문자 · 하이픈 · 공백 · 슬래시를 무시한다 (SM-S938B = sm s938b = SMS938B).
 * 모델코드로 보는 기준: 영문과 숫자가 섞인 5~20자이고 숫자가 3개 이상 (또는 6자 이상 · 숫자 2개 · 영문 3개 이상).
 * 256GB · 240Hz · 5G · 13-inches · 40oz 같은 단위 표기, iPhone17 · Galaxy25 같은 제품 이름,
 * Dimensity7300 같은 칩 이름은 제외 (칩 이름은 여러 회사 기기에 같은 값이라 다른 상품이 섞임).
 */
public final class ModelCode {

    private static final Pattern SPLIT = Pattern.compile("[\\s,;()\\[\\]{}|·•\"'*+!?/]+");
    private static final Pattern NOT_ALNUM = Pattern.compile("[^a-z0-9]");
    /** 숫자 + 단위 (256gb · 240hz · 5g · 4k · 1080p · 6.9inch · 13inches · 40oz 등) */
    private static final Pattern UNIT = Pattern.compile(
            "^\\d+(gb|tb|mb|mah|wh|hz|khz|mhz|ghz|w|mm|cm|m|in|inch|inches|k|p|mp|fps|g|v|a|ms|nm|ml|l|kg|oz|floz|lb|lbs"
                    + "|ct|ea|pcs|pc|x)$");
    /** 수량 · 크기 표기 (16gx2 · 2x16gb · 1920x1080) */
    private static final Pattern TIMES = Pattern.compile("^\\d+(gb|g|tb)?x\\d+(gb|g|tb)?$");
    /** 제품 · 칩 이름 + 숫자 (iphone17 · galaxys25 · pixel10 · ipad11 · dimensity7300 · helio100) */
    private static final Pattern NAME_NUMBER = Pattern.compile(
            "^(iphone|galaxy|galaxys|pixel|ipad|macbook|airpods|watch|series|gen|ios|android|windows|win"
                    + "|dimensity|helio|snapdragon|exynos|kirin|tensor|unisoc|tiger|bionic)\\d.*");

    private ModelCode() {
    }

    /** 검색어 속 모델코드 (정규화된 값, 나온 순서) */
    public static List<String> inQuery(String query) {
        return new ArrayList<>(codes(query));
    }

    /** 상품명 속 모델코드가 딱 하나일 때 그 코드, 아니면 null (둘 이상이면 어느 것이 본품 코드인지 모름) */
    public static String single(String title) {
        Set<String> c = codes(title);
        return c.size() == 1 ? c.iterator().next() : null;
    }

    /** text 에 codes 중 하나라도 들어 있으면 true (하이픈 · 공백 무시). codes 가 비어 있으면 true */
    public static boolean matches(String text, List<String> codes) {
        if (codes == null || codes.isEmpty()) return true;
        if (text == null) return false;
        String flat = NOT_ALNUM.matcher(text.toLowerCase(Locale.ROOT)).replaceAll("");
        for (String c : codes) {
            if (flat.contains(c)) return true;
        }
        return false;
    }

    static Set<String> codes(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null || text.isBlank()) return out;
        for (String token : SPLIT.split(text.toLowerCase(Locale.ROOT))) {
            String norm = NOT_ALNUM.matcher(token).replaceAll("");
            if (isCode(norm)) out.add(norm);
        }
        return out;
    }

    static boolean isCode(String s) {
        if (s.length() < 5 || s.length() > 20) return false;
        int digits = 0;
        int letters = 0;
        for (char ch : s.toCharArray()) {
            if (Character.isDigit(ch)) digits++;
            else letters++;
        }
        if (digits == 0 || letters == 0) return false;
        if (UNIT.matcher(s).matches() || TIMES.matcher(s).matches() || NAME_NUMBER.matcher(s).matches()) return false;
        return digits >= 3 || (s.length() >= 6 && digits >= 2 && letters >= 3);
    }
}
