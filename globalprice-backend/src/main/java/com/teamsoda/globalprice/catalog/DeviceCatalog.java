package com.teamsoda.globalprice.catalog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 휴대폰 · 태블릿 모델 목록 (DB device_models · model_codes, 10_migration_007). 상품명에서 모델 이름이나 모델번호를 찾는다.
 * 아이폰 · 갤럭시 · 픽셀 · 맥북 · 아이패드처럼 전용 규칙이 있는 제품은 여기 없고 그 규칙을 쓴다.
 * <ul>
 *   <li>이름: 상품명 안에서 가장 긴 이름을 찾는다 ("POCO X6 Pro" 가 "POCO X6" 보다 먼저).
 *       제조사 이름(또는 별칭: 샤오미 ↔ 레드미 · 포코, 모토로라 ↔ moto)이 상품명에 있어야 한다</li>
 *   <li>모델번호: 하이픈 · 공백을 뺀 값이 같으면 (CPH2649, 2311DRK48G, XT2413-1)</li>
 *   <li>애매하면 null: 서로 다른 모델 이름이 둘 이상, 모델번호가 다른 모델을 가리킴, 이름과 번호가 서로 다른 모델</li>
 * </ul>
 * 이름을 찾을 때 5G · 4G · LTE 표기는 뺀다. 같은 이름의 5G 판과 4G 판이 목록에 따로 있으면 (Redmi Note 14 Pro / 5G)
 * 상품명의 5G · 4G 표기로 고르고, 표기가 없으면 고르지 않는다 (서로 다른 기기라서).
 */
public final class DeviceCatalog {

    /**
     * @param key   묶기 키에서 용량을 뺀 앞부분 (dev|제조사|이름)
     * @param brand 보여 줄 제조사 (Xiaomi, OnePlus …)
     * @param name  보여 줄 모델 이름 (POCO X6 Pro 5G)
     */
    public record Model(String key, String brand, String name) {
        /** 대표 이름: 모델 이름이 제조사로 시작하면 그대로, 아니면 제조사 + 이름 */
        public String displayName() {
            return name.toLowerCase(Locale.ROOT).startsWith(brand.toLowerCase(Locale.ROOT)) ? name : brand + " " + name;
        }
    }

    /** network: 이름에 붙은 통신 표기 (5g · 4g · 없으면 "") */
    private record Entry(Set<String> brandTokens, Model model, String network) {
    }

    public static final DeviceCatalog EMPTY = new DeviceCatalog(Map.of(), Map.of(), 0);

    /** 이름이 이보다 길면 (단어 수) 찾지 않음 */
    static final int MAX_NAME_TOKENS = 8;
    private static final Set<String> NETWORK = Set.of("5g", "4g", "lte", "3g");
    /** 제조사 별칭: 상품명에 이 중 하나가 있으면 제조사가 있는 것으로 본다 */
    private static final Map<String, List<String>> BRAND_ALIASES = Map.of(
            "xiaomi", List.of("xiaomi", "mi", "redmi", "poco"),
            "redmi", List.of("redmi", "xiaomi"),
            "poco", List.of("poco", "xiaomi"),
            "motorola", List.of("motorola", "moto"),
            "lge", List.of("lg"),
            "lg", List.of("lg"),
            "tct", List.of("alcatel", "tcl"),
            "alcatel", List.of("alcatel", "tcl"),
            "hmd", List.of("hmd", "nokia"),
            "nokia", List.of("nokia", "hmd"));
    private static final Pattern WORD = Pattern.compile("[a-z0-9]+");
    private static final Pattern CODE_TOKEN = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");

    private final Map<String, List<Entry>> byName;
    private final Map<String, Model> byCode;
    private final int size;

    private DeviceCatalog(Map<String, List<Entry>> byName, Map<String, Model> byCode, int size) {
        this.byName = byName;
        this.byCode = byCode;
        this.size = size;
    }

    /**
     * @param models 모델들 (key 가 같으면 처음 것)
     * @param codes  모델번호(소문자 · 영숫자만) → model key. 모르는 key 의 번호는 버린다
     */
    public static DeviceCatalog of(Collection<Model> models, Map<String, String> codes) {
        Map<String, Model> byKey = new HashMap<>();
        Map<String, List<Entry>> byName = new HashMap<>();
        for (Model m : models) {
            if (byKey.putIfAbsent(m.key(), m) != null) continue;
            String name = normalizeName(m.name());
            if (!eligibleName(name)) continue;
            byName.computeIfAbsent(name, k -> new ArrayList<>())
                    .add(new Entry(brandTokens(m.brand(), name), m, network(m.name())));
        }
        Map<String, Model> byCode = new HashMap<>();
        codes.forEach((code, key) -> {
            Model m = byKey.get(key);
            if (m != null && eligibleCode(code)) byCode.put(code, m);
        });
        return new DeviceCatalog(Map.copyOf(byName), Map.copyOf(byCode), byKey.size());
    }

    public int size() {
        return size;
    }

    /** 상품명(소문자) 속 모델. 없거나 애매하면 null */
    public Model find(String title) {
        if (size == 0) return null;
        String[] tokens = words(title);
        Set<String> present = new HashSet<>(Arrays.asList(tokens));
        Set<Model> fromCode = new LinkedHashSet<>();
        Matcher c = CODE_TOKEN.matcher(title);
        while (c.find()) {
            Model m = byCode.get(c.group().replace("-", ""));
            if (m != null) fromCode.add(m);
        }
        if (fromCode.size() > 1) return null;
        Set<Model> fromName = namesIn(tokens, present, network(title));
        if (fromName == null || fromName.size() > 1) return null;
        Model byName = fromName.isEmpty() ? null : fromName.iterator().next();
        Model byCodeModel = fromCode.isEmpty() ? null : fromCode.iterator().next();
        if (byName != null && byCodeModel != null && !byName.equals(byCodeModel)) return null;
        return byName != null ? byName : byCodeModel;
    }

    /** 상품명에 나온 모델 이름들 (다른 이름 안에 들어 있는 짧은 이름은 뺌). 한 이름이 여러 모델이면 null */
    private Set<Model> namesIn(String[] tokens, Set<String> present, String titleNetwork) {
        record Span(int start, int end, Model model) {
        }
        List<Span> spans = new ArrayList<>();
        for (int i = 0; i < tokens.length; i++) {
            for (int len = Math.min(MAX_NAME_TOKENS, tokens.length - i); len >= 1; len--) {
                List<Entry> entries = byName.get(String.join(" ", Arrays.copyOfRange(tokens, i, i + len)));
                if (entries == null) continue;
                List<Entry> ok = new ArrayList<>();
                for (Entry e : entries) {
                    if (e.brandTokens().stream().anyMatch(present::contains)) ok.add(e);
                }
                if (ok.isEmpty()) continue;
                if (ok.size() > 1 && titleNetwork.isEmpty()) return null;   // 5G · 4G 판이 따로 있는데 상품명에 표기 없음
                if (ok.size() > 1) {   // 같은 이름의 5G · 4G 판 → 상품명 표기로 고름 (4G 는 표기 없는 판도 후보)
                    ok = ok.stream().filter(e -> e.network().equals(titleNetwork)
                            || (titleNetwork.equals("4g") && e.network().isEmpty())).toList();
                    if (ok.size() > 1) ok = ok.stream().filter(e -> e.network().equals(titleNetwork)).toList();
                }
                if (ok.size() != 1) return null;   // 고를 수 없음 (다른 제조사가 둘 다 있거나 5G · 4G 표기 없음)
                spans.add(new Span(i, i + len, ok.getFirst().model()));
                break;   // 이 위치에서는 가장 긴 이름만
            }
        }
        Set<Model> out = new LinkedHashSet<>();
        for (Span s : spans) {
            boolean inside = spans.stream().anyMatch(o -> o != s && o.start() <= s.start() && s.end() <= o.end()
                    && (o.end() - o.start()) > (s.end() - s.start()));
            if (!inside) out.add(s.model());
        }
        return out;
    }

    /** 이름 · 상품명 공통 정리: 소문자 영숫자 단어, + → plus, 5G · LTE 표기 제거 */
    static String[] words(String s) {
        String t = s.toLowerCase(Locale.ROOT).replace("+", " plus ");
        List<String> out = new ArrayList<>();
        Matcher m = WORD.matcher(t);
        while (m.find()) {
            if (!NETWORK.contains(m.group())) out.add(m.group());
        }
        return out.toArray(String[]::new);
    }

    /** 찾기용 이름 (통신 표기 뺌): Redmi Note 14 Pro+ 5G → redmi note 14 pro plus */
    public static String normalizeName(String name) {
        return String.join(" ", words(name));
    }

    /** 키에 쓰는 이름 (통신 표기 남김): Redmi Note 14 Pro+ 5G → redmi note 14 pro plus 5g */
    public static String keyName(String name) {
        List<String> out = new ArrayList<>();
        Matcher m = WORD.matcher(name.toLowerCase(Locale.ROOT).replace("+", " plus "));
        while (m.find()) out.add(m.group());
        return String.join(" ", out);
    }

    /** 통신 표기: 5g · 4g(LTE 포함) · "" */
    static String network(String s) {
        String t = s.toLowerCase(Locale.ROOT);
        if (Pattern.compile("(?<![a-z0-9])5g(?![a-z0-9])").matcher(t).find()) return "5g";
        if (Pattern.compile("(?<![a-z0-9])(4g|lte)(?![a-z0-9])").matcher(t).find()) return "4g";
        return "";
    }

    /** 찾을 만한 이름: 두 단어 이상이고 숫자가 든 단어가 있음, 또는 영문 + 숫자가 섞인 5자 이상 한 단어 */
    static boolean eligibleName(String name) {
        if (name.isEmpty()) return false;
        String[] w = name.split(" ");
        if (w.length > MAX_NAME_TOKENS) return false;
        if (w.length >= 2) return Arrays.stream(w).anyMatch(x -> x.matches(".*\\d.*"));
        return w[0].length() >= 5 && w[0].matches(".*\\d.*") && w[0].matches(".*[a-z].*");
    }

    /** 찾을 만한 모델번호: 영문 + 숫자 5자 이상 */
    static boolean eligibleCode(String code) {
        return code.length() >= 5 && code.matches("[a-z0-9]+") && code.matches(".*\\d.*") && code.matches(".*[a-z].*");
    }

    /** 제조사 단어 + 별칭. 이름 첫 단어가 별칭이면 그것도 (POCO X6 → poco) */
    static Set<String> brandTokens(String brand, String normalizedName) {
        Set<String> out = new LinkedHashSet<>();
        for (String w : words(brand)) {
            out.add(w);
            out.addAll(BRAND_ALIASES.getOrDefault(w, List.of()));
        }
        String first = normalizedName.split(" ")[0];
        if (BRAND_ALIASES.containsKey(first)) out.addAll(BRAND_ALIASES.get(first));
        return out;
    }

    /** 키에 쓰는 제조사 값 (소문자 영숫자) */
    public static String brandSlug(String brand) {
        return String.join("", words(brand));
    }
}
