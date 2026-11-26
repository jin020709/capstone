package com.teamsoda.globalprice.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 휴대폰 · 태블릿 모델 목록 (Google Play 지원 기기 CSV → device_models · model_codes) */
class DeviceCatalogTest {

    /** 실제 CSV 의 행들 (11/27 받은 파일에서 골라 옮김) */
    static final String CSV = """
            Retail Branding,Marketing Name,Device,Model
            POCO,POCO X6 Pro 5G,duchamp,2311DRK48G
            POCO,POCO X6 Pro 5G,duchamp,2311DRK48I
            POCO,POCO X6 5G,garnet,23122PCD1G
            Xiaomi,Xiaomi 15 Ultra,xuanyuan,25010PN30G
            Redmi,Redmi Note 13 Pro+ 5G,zircon,23090RA98G
            Redmi,Redmi Note 14 Pro,obsidian,24117RK2CG
            Redmi,Redmi Note 14 Pro 5G,garnet2,24115RA8EG
            OnePlus,OnePlus 13,OP5D55L1,CPH2649
            OnePlus,OnePlus 13,OP5D55L1,CPH2653
            OnePlus,OnePlus 13R,OP5D3BL1,CPH2645
            motorola,"moto g power 5G - 2024",devonn,XT2415-1
            LGE,LG V60 ThinQ 5G,timelm,LM-V600
            TCT (Alcatel),1S,5024D,5024D
            Samsung,Galaxy S25 Ultra,pa3q,SM-S938B
            Samsung,Galaxy S25 Ultra,pa3q,SM-S938U1
            Samsung,Galaxy M55 5G,m55xq,SM-M556B
            Samsung,Galaxy Watch7,wise,SM-L310
            Google,Pixel 10 Pro,blazer,Pixel 10 Pro
            Infinix,NOTE 40 Pro,X6850,Infinix X6850
            Tecno,NOTE 40 Pro,CK7n,CK7n
            """;

    static DeviceCatalogImport.Parsed parsed() throws IOException {
        return DeviceCatalogImport.parse(new ByteArrayInputStream(CSV.getBytes(StandardCharsets.UTF_16)));
    }

    @BeforeEach
    void load() throws IOException {
        DeviceCatalogImport.Parsed p = parsed();
        ProductMatcher.useDeviceCatalog(DeviceCatalog.of(new ArrayList<>(p.models().values()), p.codes()));
        Map<String, String[]> galaxy = new LinkedHashMap<>();
        p.galaxyCodes().forEach((base, f) -> galaxy.put(base, ModelCodes.parseGalaxy(String.join("|", f))));
        ProductMatcher.useGalaxyCodes(galaxy);
    }

    @AfterEach
    void reset() {
        ProductMatcher.useDeviceCatalog(null);
        ProductMatcher.useGalaxyCodes(null);
    }

    private static String key(String title) {
        ProductMatcher.Match m = ProductMatcher.match(title);
        return m == null ? null : m.key();
    }

    @Test
    void parse_splitsGalaxyPixelAndOtherDevices() throws IOException {
        DeviceCatalogImport.Parsed p = parsed();
        assertThat(p.rows()).isEqualTo(20);
        assertThat(p.models()).containsKeys("dev|poco|poco x6 pro 5g", "dev|oneplus|oneplus 13", "dev|motorola|moto g power 5g 2024",
                "dev|lg|lg v60 thinq 5g", "dev|samsung|galaxy watch7");
        assertThat(p.models().get("dev|lg|lg v60 thinq 5g").brand()).isEqualTo("LG");          // LGE → LG
        assertThat(p.models().keySet()).noneMatch(k -> k.contains("pixel") || k.contains("s25"));   // 전용 규칙
        assertThat(p.models()).doesNotContainKey("dev|alcatel|1s");                           // 너무 짧은 이름
        assertThat(p.codes()).containsEntry("cph2649", "dev|oneplus|oneplus 13").containsEntry("xt24151",
                "dev|motorola|moto g power 5g 2024");
        assertThat(String.join("|", p.galaxyCodes().get("s938"))).isEqualTo("galaxy|s|25|ultra");
        assertThat(String.join("|", p.galaxyCodes().get("m556"))).isEqualTo("galaxy|m|55|");
    }

    @Test
    void sameModelAcrossTitlesAndCodes() {
        String k = "dev|poco|poco x6 pro 5g|512";
        assertThat(key("Xiaomi POCO X6 Pro 5G 12GB 512GB Global Version Black")).isEqualTo(k);
        assertThat(key("xiaomi poco x6 pro 512gb international version global version android 14")).isEqualTo(k);
        assertThat(key("New Xiaomi 2311DRK48G Smartphone 512GB Yellow")).isEqualTo(k);   // 모델번호만
        assertThat(ProductMatcher.match("POCO X6 Pro 5G 512GB").name()).isEqualTo("POCO X6 Pro 5G 512GB");
        assertThat(key("OnePlus 13 CPH2653 16GB 512GB Midnight Ocean")).isEqualTo("dev|oneplus|oneplus 13|512");
        assertThat(key("Motorola Moto G Power 2024 128GB Midnight Blue Unlocked"))
                .isEqualTo("dev|motorola|moto g power 5g 2024|128");
        assertThat(key("Redmi Note 13 Pro Plus 256GB")).isEqualTo(key("Xiaomi Redmi Note 13 Pro+ 5G 256GB Black"));
    }

    @Test
    void separate4gAnd5gModels_pickedByTitle() {   // Redmi Note 14 Pro (4G, Helio) 와 5G(Dimensity) 는 다른 기기
        assertThat(key("Xiaomi Redmi Note 14 Pro 5G 8GB+256GB Dual SIM")).isEqualTo("dev|redmi|redmi note 14 pro 5g|256");
        assertThat(key("Xiaomi Redmi Note 14 Pro 4G 512GB ROM 12GB RAM")).isEqualTo("dev|redmi|redmi note 14 pro|512");
        assertThat(key("Redmi Note 14 Pro 8/256GB")).doesNotStartWith("dev|");   // 표기 없음 → 고르지 않음
        assertThat(key("Xiaomi 24115RA8EG 256GB")).isEqualTo("dev|redmi|redmi note 14 pro 5g|256");   // 번호로는 확실
        // 한 판만 있는 기기는 표기가 없어도 됨
        assertThat(key("OnePlus 13 256GB")).isEqualTo(key("OnePlus 13 5G 256GB"));
    }

    @Test
    void longestNameWins_andDifferentModelsDiffer() {
        assertThat(key("POCO X6 5G 256GB")).isEqualTo("dev|poco|poco x6 5g|256");
        assertThat(key("POCO X6 Pro 256GB")).isEqualTo("dev|poco|poco x6 pro 5g|256");
        assertThat(key("OnePlus 13R 256GB")).isEqualTo("dev|oneplus|oneplus 13r|256");
        assertThat(key("POCO X6 Pro 256GB")).isNotEqualTo(key("POCO X6 Pro 512GB"));
    }

    @Test
    void ambiguousOrUnsafe_noDeviceKey() {
        // 다른 모델 두 개 (호환 목록)
        assertThat(key("Xiaomi POCO X6 Pro / POCO X6 256GB")).doesNotStartWith("dev|");
        // 이름은 X6 Pro, 모델번호는 X6
        assertThat(key("POCO X6 Pro 256GB 23122PCD1G")).doesNotStartWith("dev|");
        // 용량 없음 · 용량 둘
        assertThat(key("POCO X6 Pro 5G Global")).doesNotStartWith("dev|");
        assertThat(key("POCO X6 Pro 256GB 512GB")).doesNotStartWith("dev|");
        assertThat(key("Xiaomi Poco X6 Pro 6.67\" 64MP 256/512GB SmartPhone Sealed")).doesNotStartWith("dev|");
        // 제조사 이름이 없으면 이름만으로는 안 찾음 (NOTE 40 Pro 는 Infinix · Tecno 둘 다)
        assertThat(key("Note 40 Pro 256GB")).doesNotStartWith("dev|");
        assertThat(key("Infinix Note 40 Pro 256GB")).isEqualTo("dev|infinix|note 40 pro|256");
        assertThat(key("Tecno Note 40 Pro 256GB")).isEqualTo("dev|tecno|note 40 pro|256");
        // 용량이 애매해 빠진 폰은 칩 이름을 모델코드로 잡아 묶지 않음 (11/27 실제: code:dimensity7300)
        assertThat(key("Xiaomi Redmi Note 14 Pro 5G 128/256/512GB Dimensity7300 Ultra")).startsWith("t:");
        // 세트 상품
        assertThat(key("OnePlus 13 512GB Bundle with case")).doesNotStartWith("dev|");
    }

    @Test
    void galaxyCodesFromCatalog_reachNewSeries() {
        assertThat(key("Samsung SM-M556B/DS 256GB")).isEqualTo("galaxy|m|55||256");   // 내장 · 시드 목록에 없던 M 시리즈
        assertThat(key("SAMSUNG SM-S938U1 512GB Unlocked")).isEqualTo("galaxy|s|25|ultra|512");
    }

    @Test
    void configAndOutlierSeries_forDeviceKeys() {
        ProductMatcher.Config c = ProductMatcher.config("dev|poco|poco x6 pro 5g|512");
        assertThat(c.family()).isEqualTo("dev|poco|poco x6 pro 5g");
        assertThat(c.label()).isEqualTo("512GB");
        assertThat(OutlierService.series(c.family())).isEqualTo("dev|poco|poco x6 pro 5g");   // 제조사 전체와 비교하지 않음
        assertThat(ProductMatcher.queryConfig("poco x6 pro").family()).isEqualTo("dev|poco|poco x6 pro 5g");
    }

    @Test
    void emptyCatalog_behavesAsBefore() {
        ProductMatcher.useDeviceCatalog(null);
        assertThat(key("Xiaomi POCO X6 Pro 5G 12GB 512GB")).startsWith("t:");
    }
}
