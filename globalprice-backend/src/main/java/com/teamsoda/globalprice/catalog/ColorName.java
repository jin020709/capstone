package com.teamsoda.globalprice.catalog;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 상품명에서 색상 찾기 (다나와처럼 같은 상품 안의 색상별 최저가를 보여 주려고).
 * 긴 이름부터 확인한다 (Mist Blue 가 Blue 로 잡히지 않게). 영어 · 독일어 · 프랑스어 · 일본어 · 한글 표기를 같은 이름으로 모은다.
 * "multi color" 처럼 여러 색을 한 리스팅에 파는 경우는 색상 없음(null).
 */
public final class ColorName {

    private record Entry(String name, Pattern pattern) {
    }

    private static Entry e(String name, String regex) {
        return new Entry(name, Pattern.compile(regex));
    }

    private static final Pattern MULTI = Pattern.compile("multi\\s*-?colou?r|all\\s*colou?rs|여러\\s*색");

    private static final List<Entry> COLORS = List.of(
            e("Cosmic Orange", "cosmic\\s*orange|orange\\s*cosmique|コズミックオレンジ"),
            e("Deep Blue", "deep\\s*blue|bleu\\s*profond|ディープブルー"),
            e("Mist Blue", "mist\\s*blue|nebelblau|bleu\\s*brume|ミストブルー|미스트\\s*블루"),
            e("Sky Blue", "sky\\s*blue|bleu\\s*ciel|スカイブルー|스카이\\s*블루"),
            e("Light Gold", "light\\s*gold|or\\s*clair|ライトゴールド"),
            e("Space Black", "space\\s*black|noir\\s*sid[ée]ral|スペースブラック"),
            e("Cloud White", "cloud\\s*white|blanc\\s*nuage|クラウドホワイト"),
            e("Icy Blue", "icy\\s*blue|icyblue|아이시\\s*블루"),
            e("Titanium Black", "titanium\\s*black|티타늄\\s*블랙"),
            e("Titanium Silver", "titanium\\s*silver|티타늄\\s*실버"),
            e("Titanium Gray", "titanium\\s*gr[ae]y|티타늄\\s*그레이"),
            e("Titanium Blue", "titanium\\s*blue|티타늄\\s*블루"),
            e("Space Gray", "space\\s*gr[ae]y|스페이스\\s*그레이"),
            e("Blue Shadow", "blue\\s*shadow|블루\\s*섀도우"),
            e("Violet Shadow", "violet\\s*shadow|바이올렛\\s*쉐?섀?도우"),
            e("Green Shadow", "green\\s*shadow|그린\\s*섀?쉐?도우"),
            e("Rose Gold", "rose\\s*gold|or\\s*rose|로즈\\s*골드"),
            e("Midnight", "midnight|minuit|ミッドナイト|미드나이트"),
            e("Starlight", "starlight|lumi[èe]re\\s*stellaire|スターライト|스타라이트"),
            e("Graphite", "graphite|graphit|그라파이트"),
            e("Pistachio", "pistachio|피스타치오"),
            e("Cream", "\\bcream\\b|크림"),
            e("Sage", "\\bsage\\b|salbei|sauge|セージ|세이지"),
            e("Lavender", "lavender|lavendel|lavande|ラベンダー|라벤더"),
            e("Navy", "\\bnavy\\b|네이비"),
            e("Mint", "\\bmint\\b|민트"),
            e("Black", "\\bblack\\b|schwarz|\\bnoir\\b|ブラック|블랙"),
            e("White", "\\bwhite\\b|wei(ss|ß)|\\bblanc\\b|ホワイト|화이트"),
            e("Silver", "\\bsilver\\b|silber|\\bargent\\b|シルバー|실버"),
            e("Gold", "\\bgold\\b|ゴールド|골드"),
            e("Blue", "\\bblue\\b|blau|\\bbleu\\b|ブルー|블루"),
            e("Pink", "\\bpink\\b|\\brose\\b|ピンク|핑크"),
            e("Purple", "\\bpurple\\b|violet|パープル|퍼플"),
            e("Green", "\\bgreen\\b|gr[üu]n|\\bvert\\b|グリーン|그린"),
            e("Red", "\\bred\\b|\\brouge\\b|レッド|레드"),
            e("Yellow", "\\byellow\\b|\\bjaune\\b|イエロー|옐로"),
            e("Gray", "\\bgr[ae]y\\b|\\bgrau\\b|\\bgris\\b|グレー|그레이"),
            e("Orange", "\\borange\\b|オレンジ|오렌지"));

    private ColorName() {
    }

    /** 상품명 속 색상 이름 (영문 대표 이름), 없거나 여러 색이면 null */
    public static String of(String title) {
        if (title == null || title.isBlank()) return null;
        String t = title.toLowerCase(Locale.ROOT);
        if (MULTI.matcher(t).find()) return null;
        for (Entry c : COLORS) {
            if (c.pattern().matcher(t).find()) return c.name();
        }
        return null;
    }
}
