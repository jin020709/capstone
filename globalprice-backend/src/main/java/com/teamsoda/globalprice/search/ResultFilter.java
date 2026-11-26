package com.teamsoda.globalprice.search;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.teamsoda.globalprice.config.GlobalPriceProperties;
import com.teamsoda.globalprice.source.MallSource.MallOffer;

/**
 * 1차 걸러내기: 새 상품만 · 액세서리 · 부품 · 통신사 잠김 · 시계 밴드 제외. 키워드는 application.yml(globalprice.filter).
 * 박스만 파는 리스팅 · "for iPhone" 표기는 문장 구조를 보는 정규식이라 코드에 둔다.
 * 영문 키워드는 단어 경계로 비교한다 ("case" 는 걸러도 "showcase" 는 통과, 복수형 cases 도 걸림).
 * "for iPhone" 같은 호환 액세서리 표기, 검색한 브랜드 앞에 for / compatible with 가 붙은 표기("Stand for Sony ...")도 제외.
 */
@Component
public class ResultFilter {

    private static final Pattern FOR_MODEL = Pattern.compile("\\b(for|compatible with)\\s+(iphone|galaxy|ipad|macbook|airpods|samsung|apple|pixel)\\b",
            Pattern.CASE_INSENSITIVE);

    /** 본품 없이 박스 · 구성품만 파는 리스팅 ("(Box & Accessories) ONLY", "empty box") */
    private static final Pattern BOX_ONLY = Pattern.compile(
            "\\bbox(?:es)?\\b(?:\\s*(?:&|and|\\+)\\s*(?:accessories|accessory|manual|charger|cable)s?)?[\\s)\\]]*only\\b"
                    + "|\\bonly\\s+(?:the\\s+)?box\\b|\\bempty\\s+box\\b|\\bno\\s+(?:phone|device)\\s+included\\b|박스만|공박스",
            Pattern.CASE_INSENSITIVE);

    private final List<Pattern> notNew;
    private final List<Pattern> accessory;
    private final Pattern carrier;
    private final Pattern unlocked;
    private final Pattern watchBand;

    @org.springframework.beans.factory.annotation.Autowired
    public ResultFilter(GlobalPriceProperties props) {
        this(props.filter());
    }

    ResultFilter(GlobalPriceProperties.Filter f) {
        this.notNew = f.notNewKeywords().stream().map(ResultFilter::pattern).toList();
        this.accessory = f.accessoryKeywords().stream().map(ResultFilter::pattern).toList();
        this.carrier = anyOf(f.carrierKeywords());
        this.unlocked = anyOf(f.unlockedKeywords());
        this.watchBand = anyOf(f.watchBandKeywords());
    }

    /**
     * @param query 사용자가 액세서리 자체를 찾는 경우(예: "iphone case")는 그 키워드로 거르지 않는다
     */
    public boolean accept(MallOffer o, String query) {
        if (o.condition() != null && !"NEW".equals(o.condition().toUpperCase(Locale.ROOT))) return false;
        String title = o.title() == null ? "" : o.title();
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        for (Pattern p : notNew) {
            if (p.matcher(title).find()) return false;
        }
        if (BOX_ONLY.matcher(title).find() && !q.contains("box") && !q.contains("박스")) return false;
        boolean wantsAccessory = false;
        for (Pattern p : accessory) {
            boolean inQuery = p.matcher(q).find();
            wantsAccessory |= inQuery;
            if (p.matcher(title).find() && !inQuery) return false;
        }
        // 시계를 찾는데 밴드 · 루프만 파는 리스팅 ("Apple Watch Ultra 1/2/3 Band Black Titanium")
        if (WATCH.matcher(q).find() && !watchBand.matcher(q).find() && watchBand.matcher(title).find()) return false;

        // 미국 통신사 잠김 폰 (AT&T · Verizon 등): 한국에서 개통이 안 돼 비교 대상이 아님. "Unlocked" 가 같이 있으면 통과
        if (carrier.matcher(title).find() && !unlocked.matcher(title).find() && !carrier.matcher(q).find()) return false;

        // "for iPhone" 같은 호환 상품 표기: 사용자가 액세서리를 찾는 중이 아니면 제외
        if (wantsAccessory || FOR_MODEL.matcher(q).find() || FOR_ANY.matcher(q).find()) return true;
        if (FOR_MODEL.matcher(title).find()) return false;
        Pattern forBrand = forBrand(q);
        return forBrand == null || !forBrand.matcher(title).find();
    }

    private static final Pattern WATCH = Pattern.compile("(?<![a-z])watch(?![a-z])");

    /** 정규식 목록 중 하나라도 (영문 단어 경계). 목록이 비면 아무것도 안 걸림 */
    static Pattern anyOf(List<String> regexes) {
        if (regexes.isEmpty()) return Pattern.compile("(?!)");
        return Pattern.compile("(?<![a-z])(" + String.join("|", regexes) + ")(?![a-z])", Pattern.CASE_INSENSITIVE);
    }

    private static final Pattern FOR_ANY = Pattern.compile("\\b(for|compatible with|fits)\\b");

    /** 검색어 첫 단어(브랜드인 경우가 많음) 앞에 for / compatible with / fits 가 붙은 표기. 예: "anker power bank" → "for Anker" */
    static Pattern forBrand(String q) {
        String first = q.strip().split("\\s+")[0];
        if (first.length() < 3 || !first.matches("[a-z][a-z0-9-]*")) return null;
        // "Keyboard for 13 Inch iPad Pro" 처럼 for 와 브랜드 사이에 단어가 3개까지 끼어도 잡음
        return Pattern.compile("(?<![a-z])(for|für|fur|pour|para|compatible\\s+with|fits)\\s+(?:[\\w.\"-]+\\s+){0,3}" + Pattern.quote(first)
                + "(?![a-z])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private static Pattern pattern(String keyword) {
        String k = keyword.toLowerCase(Locale.ROOT).strip();
        boolean ascii = k.chars().allMatch(c -> c < 128);
        String quoted = Pattern.quote(k);
        // 영문은 복수형(cases, covers, brushes)도 같은 키워드로
        return Pattern.compile(ascii ? "(?<![a-z0-9])" + quoted + "(?:e?s)?(?![a-z0-9])" : quoted,
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
}
