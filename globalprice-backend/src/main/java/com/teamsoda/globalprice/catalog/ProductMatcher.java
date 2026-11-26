package com.teamsoda.globalprice.catalog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 같은 상품 판정 (다나와식 묶기). 쇼핑몰 상품명에서 "모델 + 용량"을 뽑아 묶기 키를 만든다.
 * <ul>
 *   <li>핵심 품목: iPhone · Galaxy · Pixel · MacBook · iPad → 모델 + 저장 용량(맥북은 RAM 포함)이 같으면 같은 상품.
 *       색상 · 통신사 · 언락 · 배송 문구는 무시한다 (판매처 줄에서 원래 상품명으로 보여 줌)</li>
 *   <li>애매하면 묶지 않는다: 한 상품명에 용량이 여러 개(256GB · 512GB · 1TB), 모델이 여러 개(17 Pro / 17 Pro Max),
 *       용량이 없음 → 아래 "상품명 완전 일치" 키로 넘어감</li>
 *   <li>그 밖의 상품: 상품명을 소문자 · 공백 정리한 값이 완전히 같을 때만 묶는다</li>
 * </ul>
 * 잘못 묶는 것(다른 상품이 한 줄에 섞임)보다 덜 묶는 것(같은 상품이 두 줄)이 낫다는 기준.
 */
public final class ProductMatcher {

    /** key: products.match_key, name: 묶인 상품의 대표 이름 */
    public record Match(String key, String name) {
    }

    static final int KEY_MAX = 255;

    private ProductMatcher() {
    }

    public static Match match(String title) {
        if (title == null || title.isBlank()) return null;
        String t = normalize(title);
        Match m = BUNDLE.matcher(t).find() ? null : iphone(t);
        if (m == null && !BUNDLE.matcher(t).find()) {
            m = galaxy(t);
            if (m == null) m = pixel(t);
            if (m == null) m = macbook(t);
            if (m == null) m = ipad(t);
            if (m == null) m = device(t);
        }
        if (m != null) return m;
        // 이름 규칙이 없는 상품(모니터 등)은 상품명 속 모델코드가 하나뿐이면 그 코드로 묶는다.
        // 아이폰 등 이름 규칙이 있는 제품인데 규칙에서 빠진 리스팅(용량 여러 개 등)은 코드로도 묶지 않음
        // (A3520 같은 모델번호는 용량 구분이 없어서 256GB · 512GB 가 섞임). 단, 갤럭시 SM- 코드는 그대로 씀
        String code = ModelCode.single(title);
        boolean family = FAMILY.matcher(t).find() && !t.matches(".*(?<![a-z0-9])sm-?[a-z]\\d{3}.*");
        // 모델 목록에 있는 폰인데 용량이 애매해 빠진 경우도 같음 (칩 이름 "Dimensity7300" 이 모델코드로 잡혀 다른 폰과 섞임)
        if (!family && devices.find(t) != null) family = true;
        if (code != null && !family && !BUNDLE.matcher(t).find()) return new Match("code:" + code, cut(title.strip()));
        String flat = t.replaceAll("[^a-z0-9가-힣+.]+", " ").trim();
        if (flat.length() < 6) return null;
        return new Match(cut("t:" + flat), cut(title.strip()));
    }

    // ---------------- 다른 구성 (다나와식) ----------------

    /**
     * 같은 모델의 다른 구성(용량 · 메모리 · 통신 방식). key 가 같은 family 면 상세 화면 "다른 구성"에 함께 나온다.
     *
     * @param family    용량을 뺀 모델 키 (예: iphone|17 pro)
     * @param label     구성 이름 (예: 256GB, 16GB · 512GB, 256GB Wi-Fi + Cellular)
     * @param storageGb 저장 용량 (GB당 가격 계산용, 모르면 null)
     * @param order     정렬값 (작은 구성부터)
     */
    public record Config(String family, String label, Integer storageGb, long order) {
    }

    /** 묶기 키 → 다른 구성 정보. 모델코드 · 상품명 키처럼 구성을 모르면 null */
    public static Config config(String key) {
        if (key == null) return null;
        String[] p = key.split("\\|", -1);
        try {
            switch (p[0]) {
                case "iphone": {   // iphone|모델|용량
                    if (p.length != 3) return null;
                    int s = Integer.parseInt(p[2]);
                    return new Config(p[0] + "|" + p[1], gb(s), s, s);
                }
                case "galaxy": {   // galaxy|시리즈|번호|접미사|용량
                    if (p.length != 5) return null;
                    int s = Integer.parseInt(p[4]);
                    return new Config(String.join("|", p[0], p[1], p[2], p[3]), gb(s), s, s);
                }
                case "pixel": {   // pixel|세대|접미사|용량
                    if (p.length != 4) return null;
                    int s = Integer.parseInt(p[3]);
                    return new Config(String.join("|", p[0], p[1], p[2]), gb(s), s, s);
                }
                case "macbook": {   // macbook|라인|크기|칩|RAM|SSD
                    if (p.length != 6) return null;
                    int ssd = Integer.parseInt(p[5]);
                    Integer ram = p[4].equals("?") ? null : Integer.parseInt(p[4]);
                    return new Config(String.join("|", p[0], p[1], p[2], p[3]),
                            (ram == null ? "" : gb(ram) + " · ") + gb(ssd), ssd, (ram == null ? 0L : ram) * 100_000L + ssd);
                }
                case "dev": {   // dev|제조사|모델 이름|용량 (DeviceCatalog)
                    if (p.length != 4) return null;
                    int s = Integer.parseInt(p[3]);
                    return new Config(String.join("|", p[0], p[1], p[2]), gb(s), s, s);
                }
                case "ipad": {   // ipad|라인|크기|칩·세대|용량|wifi·cell
                    if (p.length != 6) return null;
                    int s = Integer.parseInt(p[4]);
                    boolean cell = p[5].equals("cell");
                    return new Config(String.join("|", p[0], p[1], p[2], p[3]),
                            gb(s) + (cell ? " Wi-Fi + Cellular" : " Wi-Fi"), s, s * 2L + (cell ? 1 : 0));
                }
                default:
                    return null;
            }
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 검색어 속 모델 (용량은 있으면 storageGb, 없으면 null). 검색 결과를 "정확히 일치 → 같은 모델 다른 용량 →
     * 비슷한 모델 → 나머지" 순으로 놓을 때 쓴다. 모델을 모르는 검색어면 null.
     */
    public static Config queryConfig(String query) {
        if (query == null || query.isBlank()) return null;
        // 검색어는 "iphone 17 pro 256" 처럼 GB 를 빼고 치는 경우가 많음 → 흔한 용량 숫자는 GB 로 봄
        query = query.replaceAll("(?<![\\w.])(64|128|256|512|1024)(?![\\w.])", "$1gb");
        Match m = match(query);
        if (m != null) {
            Config c = config(m.key());
            if (c != null) return c;
        }
        // 검색어에 용량이 없으면 임시 용량(8TB)을 붙여서 모델만 알아낸다
        Match noStorage = match(query + " 8tb");
        Config c = noStorage == null ? null : config(noStorage.key());
        return c == null ? null : new Config(c.family(), null, null, 0);
    }

    /** 비슷한 모델끼리 같은 값: iPhone 17 · 17 Pro · 17 Pro Max → iphone|17, Fold8 · Fold8 울트라 → galaxy|z fold|8 */
    public static String similarBase(String family) {
        if (family == null) return null;
        String[] p = family.split("\\|", -1);
        return switch (p[0]) {
            case "iphone" -> "iphone|" + p[1].split(" ")[0];
            case "galaxy" -> p.length >= 3 ? String.join("|", p[0], p[1], p[2]) : family;
            case "pixel" -> p.length >= 2 ? p[0] + "|" + p[1] : family;
            case "macbook", "ipad" -> p.length >= 2 ? p[0] + "|" + p[1] : family;
            default -> family;
        };
    }

    // ---------------- 공통 ----------------

    private static final Pattern FAMILY = Pattern.compile("(?<![a-z])(iphone|galaxy|pixel|macbook|ipad)(?![a-z])");

    /** 세트 · 묶음 상품은 본품과 묶지 않음 */
    private static final Pattern BUNDLE = Pattern.compile(
            "(?<![a-z])(bundle|combo|set of|lot of|\\d+\\s?x\\s|with (?:case|airpods|watch|charger))|세트|묶음|사은품");

    static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replace('＋', '+').replace('–', '-').replace('—', '-').replace('‑', '-')
                .replace('”', '"').replace('“', '"').replace('″', '"')
                .replaceAll("\\s+", " ").trim();
    }

    private static final Pattern SLASH_LIST = Pattern.compile(
            "(?<![a-z0-9.])((?:\\d{1,4}\\s?/\\s?)+)\\d{1,4}\\s?(gb|tb)(?![a-z])");
    private static final Pattern CAPACITY =Pattern.compile("(?<![a-z0-9.])(\\d{1,4})\\s?(gb|tb|go|g)(?![a-z])");

    /** 상품명에 나온 용량들 (GB 단위, 중복 제거, 오름차순) */
    static TreeSet<Integer> capacities(String t) {
        TreeSet<Integer> out = new TreeSet<>();
        // "256/512GB" · "128/256/512GB" 처럼 단위를 마지막에만 쓴 목록: 앞 숫자들도 용량 (8/256GB 의 8 은 RAM 이라 아래 64GB 기준에서 빠짐)
        Matcher list = SLASH_LIST.matcher(t);
        while (list.find()) {
            String unit = list.group(2);
            for (String n : list.group(1).split("\\s?/\\s?")) {
                int v = Integer.parseInt(n);
                out.add(unit.equals("tb") && v < 16 ? v * 1024 : v);   // 512/1TB 의 512 는 GB
            }
        }
        Matcher m = CAPACITY.matcher(t);
        while (m.find()) {
            int v = Integer.parseInt(m.group(1));
            // "256 Go" = 프랑스어 GB. "256g" 처럼 B 를 뺀 표기는 저장 용량 값일 때만 (5g 통신 · 16g 같은 표기와 섞이지 않게)
            if (m.group(2).equals("g") && v != 64 && v != 128 && v != 256 && v != 512) continue;
            if (m.group(2).equals("tb")) v *= 1024;
            out.add(v);
        }
        return out;
    }

    /** 휴대폰 · 태블릿 저장 용량: 64GB 이상이 정확히 1개일 때만 (RAM 12GB 같은 값은 무시) */
    static Integer singleStorage(String t) {
        TreeSet<Integer> storage = new TreeSet<>(capacities(t).tailSet(64));
        return storage.size() == 1 ? storage.first() : null;
    }

    static String gb(int v) {
        return v >= 1024 && v % 1024 == 0 ? (v / 1024) + "TB" : v + "GB";
    }

    private static String ordinal(int n) {
        int t = n % 100;
        String suf = t >= 11 && t <= 13 ? "th" : n % 10 == 1 ? "st" : n % 10 == 2 ? "nd" : n % 10 == 3 ? "rd" : "th";
        return n + suf;
    }

    private static String cut(String s) {
        return s.length() <= KEY_MAX ? s : s.substring(0, KEY_MAX);
    }

    private static String title(String s) {
        StringBuilder b = new StringBuilder();
        for (String w : s.trim().split("\\s+")) {
            if (w.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(w.equals("xl") || w.equals("fe") ? w.toUpperCase(Locale.ROOT)
                    : Character.toUpperCase(w.charAt(0)) + w.substring(1));
        }
        return b.toString();
    }

    // ---------------- iPhone ----------------

    private static final Pattern IPHONE = Pattern.compile(
            "iphone\\s?(\\d{1,2}e?|se|air|xs|xr|x)(?:\\s?(pro\\s?max|pro|plus|mini|max|air))?(?![a-z0-9])");

    static Match iphone(String t) {
        Matcher m = IPHONE.matcher(t);
        if (!m.find()) return null;
        String gen = m.group(1);
        String suffix = m.group(2) == null ? "" : m.group(2).replaceAll("\\s", " ").replace("promax", "pro max");
        // 같은 세대의 다른 모델이 함께 적혀 있으면 (17 Pro / 17 Pro Max) 묶지 않음
        if (!gen.matches("se|air|x|xs|xr")) {
            Matcher other = Pattern.compile("(?<![a-z0-9])" + gen + "\\s?(pro\\s?max|pro|plus|mini)?(?![a-z0-9])")
                    .matcher(t);
            TreeSet<String> suffixes = new TreeSet<>();
            while (other.find()) suffixes.add(other.group(1) == null ? "" : other.group(1).replaceAll("\\s", ""));
            if (suffixes.size() > 1) return null;
        }
        if (suffix.equals("air")) {   // "iPhone 17 Air" 는 iPhone Air (다른 모델)
            gen = "air";
            suffix = "";
        }
        while (m.find()) {   // "iPhone 16 / iPhone 17" 처럼 다른 iPhone 이 또 나오면 묶지 않음
            String g2 = m.group(1);
            String s2 = m.group(2) == null ? "" : m.group(2).replaceAll("\\s", " ");
            if (!g2.equals(gen) || !s2.equals(suffix)) return null;
        }
        Integer storage = singleStorage(t);
        if (storage == null) return null;
        String model = (gen + (suffix.isEmpty() ? "" : " " + suffix)).trim();
        String genName = switch (gen) {
            case "se", "xs", "xr", "x" -> gen.toUpperCase(Locale.ROOT);
            case "air" -> "Air";
            default -> gen;
        };
        return new Match("iphone|" + model + "|" + storage,
                "Apple iPhone " + genName + (suffix.isEmpty() ? "" : " " + title(suffix)) + " " + gb(storage));
    }

    // ---------------- Galaxy ----------------

    private static final Pattern GALAXY = Pattern.compile(
            "(?:galaxy|samsung)\\s?(?:galaxy\\s?)?(z\\s?fold|z\\s?flip|fold|flip|tab\\s?s|tab\\s?a|note|s|a|m)\\s?(\\d{1,2})"
                    + "(?:\\s?(\\+|plus|ultra|fe|edge))?(?![a-z0-9])");
    /**
     * 모델번호 → (시리즈, 번호, 접미사). 실제 목록은 DB model_codes ({@link ModelCodes}, 09_migration_006) 이고,
     * 이 내장 목록은 DB 를 아직 못 읽었거나 테이블이 비어 있을 때만 쓴다.
     */
    private static final Map<String, String[]> BUILT_IN_CODES = Map.ofEntries(
            Map.entry("s931", new String[] { "s", "25", "" }),
            Map.entry("s936", new String[] { "s", "25", "+" }),
            Map.entry("s938", new String[] { "s", "25", "ultra" }),
            Map.entry("s937", new String[] { "s", "25", "edge" }),
            Map.entry("s921", new String[] { "s", "24", "" }),
            Map.entry("s926", new String[] { "s", "24", "+" }),
            Map.entry("s928", new String[] { "s", "24", "ultra" }),
            Map.entry("f966", new String[] { "z fold", "7", "" }),
            Map.entry("f766", new String[] { "z flip", "7", "" }),
            Map.entry("f956", new String[] { "z fold", "6", "" }),
            Map.entry("f741", new String[] { "z flip", "6", "" }));
    private static volatile Map<String, String[]> galaxyCodes = BUILT_IN_CODES;
    /** SM-S938B · SM-F946U1 처럼 SM- 가 붙은 번호 (지역 글자는 무시) */
    private static final Pattern SM_CODE = Pattern.compile("(?<![a-z0-9])sm-?([a-z]\\d{3})");
    /** SM- 없이 쓴 번호 ("Galaxy S25 Ultra (S938B)"). 다른 숫자와 헷갈리므로 상품명에 samsung · galaxy 가 있을 때만 */
    private static final Pattern BARE_CODE = Pattern.compile("(?<![a-z0-9-])([sfa]\\d{3})[a-z]{0,2}\\d?(?![a-z0-9])");
    private static final Pattern SAMSUNG = Pattern.compile("(?<![a-z])(samsung|galaxy)(?![a-z])");

    /**
     * DB 에서 읽은 갤럭시 모델번호로 바꾼다. 비어 있으면 내장 목록을 쓴다.
     *
     * @param codes 번호(소문자, SM- 없음) → model_key 의 갤럭시 칸 (시리즈, 번호, 접미사)
     */
    public static void useGalaxyCodes(Map<String, String[]> codes) {
        galaxyCodes = codes == null || codes.isEmpty() ? BUILT_IN_CODES : Map.copyOf(codes);
    }

    /** 상품명 속 갤럭시 모델번호가 가리키는 모델. 번호가 없거나 서로 다른 모델을 가리키면 null */
    private static String[] galaxyFromCode(String t) {
        Map<String, String[]> codes = galaxyCodes;
        String[] found = null;
        List<Matcher> ms = new ArrayList<>(2);
        ms.add(SM_CODE.matcher(t));
        if (SAMSUNG.matcher(t).find()) ms.add(BARE_CODE.matcher(t));
        for (Matcher c : ms) {
            while (c.find()) {
                String[] model = codes.get(c.group(1));
                if (model == null) continue;
                if (found != null && !Arrays.equals(found, model)) return null;
                found = model;
            }
        }
        return found;
    }

    static Match galaxy(String t) {
        String[] fromName = null;
        Matcher m = GALAXY.matcher(t);
        if (m.find()) {
            String series = m.group(1).replaceAll("\\s", " ");
            if (series.equals("fold")) series = "z fold";
            if (series.equals("flip")) series = "z flip";
            series = series.replace("zfold", "z fold").replace("zflip", "z flip").replace("tabs", "tab s")
                    .replace("taba", "tab a");
            String suffix = m.group(3) == null ? "" : m.group(3).equals("plus") ? "+" : m.group(3);
            fromName = new String[] { series, m.group(2), suffix };
            while (m.find()) {   // 다른 Galaxy 모델이 또 나오면 묶지 않음
                String s2 = m.group(3) == null ? "" : m.group(3).equals("plus") ? "+" : m.group(3);
                if (!m.group(2).equals(fromName[1]) || !s2.equals(suffix)) return null;
            }
        }
        String[] fromCode = galaxyFromCode(t);
        String[] model = fromName != null ? fromName : fromCode;
        if (model == null) return null;
        if (fromName != null && fromCode != null && !String.join("|", fromName).equals(String.join("|", fromCode))) {
            return null;
        }
        Integer storage = singleStorage(t);
        if (storage == null) return null;
        String series = model[0];
        String num = model[1];
        String suffix = model[2];
        String seriesName = switch (series) {
            case "z fold" -> "Z Fold" + num;
            case "z flip" -> "Z Flip" + num;
            case "tab s" -> "Tab S" + num;
            case "tab a" -> "Tab A" + num;
            case "note" -> "Note " + num;
            default -> series.toUpperCase(Locale.ROOT) + num;
        };
        String suffixName = switch (suffix) {
            case "" -> "";
            case "+" -> "+";
            case "fe" -> " FE";
            default -> " " + title(suffix);
        };
        return new Match("galaxy|" + series + "|" + num + "|" + suffix + "|" + storage,
                "Samsung Galaxy " + seriesName + suffixName + " " + gb(storage));
    }

    // ---------------- 그 밖의 휴대폰 · 태블릿 (모델 목록) ----------------

    private static volatile DeviceCatalog devices = DeviceCatalog.EMPTY;

    /** DB 에서 읽은 모델 목록으로 바꾼다 ({@link ModelCodes}). null 이면 빈 목록 */
    public static void useDeviceCatalog(DeviceCatalog catalog) {
        devices = catalog == null ? DeviceCatalog.EMPTY : catalog;
    }

    /** 모델 목록에 있는 기기 + 저장 용량 1개 → dev|제조사|이름|용량 */
    static Match device(String t) {
        DeviceCatalog.Model m = devices.find(t);
        if (m == null) return null;
        Integer storage = singleStorage(t);
        if (storage == null) return null;
        return new Match(cut(m.key() + "|" + storage), cut(m.displayName() + " " + gb(storage)));
    }

    // ---------------- Pixel ----------------

    private static final Pattern PIXEL = Pattern.compile(
            "pixel\\s?(\\d{1,2})(a)?(?:\\s?(pro\\s?xl|pro\\s?fold|pro|xl|fold))?(?![a-z0-9])");

    static Match pixel(String t) {
        Matcher m = PIXEL.matcher(t);
        if (!m.find()) return null;
        String gen = m.group(1) + (m.group(2) == null ? "" : "a");
        String suffix = m.group(3) == null ? "" : m.group(3).replaceAll("\\s", " ").replace("proxl", "pro xl")
                .replace("profold", "pro fold");
        while (m.find()) {
            String g2 = m.group(1) + (m.group(2) == null ? "" : "a");
            String s2 = m.group(3) == null ? "" : m.group(3).replaceAll("\\s", " ");
            if (!g2.equals(gen) || !s2.equals(suffix)) return null;
        }
        Integer storage = singleStorage(t);
        if (storage == null) return null;
        return new Match("pixel|" + gen + "|" + suffix + "|" + storage,
                "Google Pixel " + gen + (suffix.isEmpty() ? "" : " " + title(suffix)) + " " + gb(storage));
    }

    // ---------------- MacBook ----------------

    private static final Pattern MACBOOK = Pattern.compile("macbook\\s?(air|pro)(?![a-z])");
    private static final Pattern CHIP = Pattern.compile("(?<![a-z0-9])m([1-5])(?:\\s?(pro|max))?(?![a-z0-9])");
    private static final Pattern SIZE_AFTER_NAME = Pattern.compile(
            "macbook\\s?(?:air|pro)\\s?(13|14|15|16)(?:\\.\\d)?(?![0-9])(?!\\s?(?:gb|tb|core|c\\b))");
    private static final Pattern SIZE_INCH = Pattern.compile(
            "(?<![0-9.])(13|14|15|16)(?:\\.\\d)?\\s?(?:-?\\s?inch|in\\b|\"|인치|형)");

    static Match macbook(String t) {
        Matcher m = MACBOOK.matcher(t);
        if (!m.find()) return null;
        String line = m.group(1);
        while (m.find()) if (!m.group(1).equals(line)) return null;
        Matcher c = CHIP.matcher(t);
        if (!c.find()) return null;
        String chip = "m" + c.group(1) + (c.group(2) == null ? "" : " " + c.group(2));
        while (c.find()) {
            String c2 = "m" + c.group(1) + (c.group(2) == null ? "" : " " + c.group(2));
            if (!c2.equals(chip)) return null;
        }
        String size = null;
        Matcher s = SIZE_AFTER_NAME.matcher(t);
        if (s.find()) size = s.group(1);
        Matcher s2 = SIZE_INCH.matcher(t);
        if (s2.find()) {
            if (size != null && !size.equals(s2.group(1))) return null;
            size = s2.group(1);
        }
        if (size == null) return null;
        TreeSet<Integer> caps = capacities(t);
        TreeSet<Integer> ssd = new TreeSet<>(caps.tailSet(256));
        TreeSet<Integer> ram = new TreeSet<>(caps.headSet(129));
        ram.removeIf(v -> v < 8);
        if (ssd.size() != 1 || ram.size() > 1) return null;
        String ramKey = ram.isEmpty() ? "?" : String.valueOf(ram.first());
        return new Match("macbook|" + line + "|" + size + "|" + chip + "|" + ramKey + "|" + ssd.first(),
                "Apple MacBook " + title(line) + " " + size + " " + chip.toUpperCase(Locale.ROOT).replace(" PRO", " Pro")
                        .replace(" MAX", " Max")
                        + (ram.isEmpty() ? "" : " " + gb(ram.first())) + " " + gb(ssd.first()));
    }

    // ---------------- iPad ----------------

    private static final Pattern IPAD = Pattern.compile("ipad(?:\\s?(pro|air|mini))?(?![a-z])");
    private static final Pattern IPAD_CHIP = Pattern.compile("(?<![a-z0-9])(m[1-5]|a1[4-9](?:\\s?pro)?)(?![a-z0-9])");
    private static final Pattern IPAD_GEN = Pattern.compile("(?<![0-9])(\\d{1,2})(?:th|rd|nd|st)\\s?gen");
    private static final Pattern IPAD_SIZE = Pattern.compile(
            "(?<![0-9.])(8\\.3|10\\.2|10\\.9|11|12\\.9|13)\\s?(?:-?\\s?inch|in\\b|\"|인치|형)");

    static Match ipad(String t) {
        Matcher m = IPAD.matcher(t);
        if (!m.find()) return null;
        String line = m.group(1) == null ? "" : m.group(1);
        while (m.find()) if (!(m.group(1) == null ? "" : m.group(1)).equals(line)) return null;
        String gen = null;
        Matcher c = IPAD_CHIP.matcher(t);
        if (c.find()) gen = c.group(1).replaceAll("\\s", " ");
        Matcher g = IPAD_GEN.matcher(t);
        if (gen == null && g.find()) gen = g.group(1) + "gen";
        if (gen == null) return null;
        Matcher s = IPAD_SIZE.matcher(t);
        String size = s.find() ? s.group(1) : "?";
        Integer storage = singleStorage(t);
        if (storage == null) return null;
        boolean cellular = t.matches(".*(cellular|lte|wi-?fi\\s?\\+\\s?cell|(?<![a-z0-9])5g(?![a-z0-9])).*");
        // 이름은 다시 읽어도 같은 키가 나오게 만든다 (크기는 11", 세대는 11th Gen)
        String genName = gen.endsWith("gen") ? ordinal(Integer.parseInt(gen.replace("gen", ""))) + " Gen"
                : gen.toUpperCase(Locale.ROOT).replace(" PRO", " Pro");
        return new Match("ipad|" + line + "|" + size + "|" + gen + "|" + storage + "|" + (cellular ? "cell" : "wifi"),
                "Apple iPad" + (line.isEmpty() ? "" : " " + title(line)) + (size.equals("?") ? "" : " " + size + "\"")
                        + " " + genName + " " + gb(storage) + (cellular ? " Wi-Fi + Cellular" : " Wi-Fi"));
    }
}
