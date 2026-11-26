package com.teamsoda.globalprice.support;

import java.time.Duration;
import java.util.List;

import org.springframework.web.client.RestClient;

import com.teamsoda.globalprice.config.AppConfig;
import com.teamsoda.globalprice.config.GlobalPriceProperties;
import com.teamsoda.globalprice.config.GlobalPriceProperties.AliExpress;
import com.teamsoda.globalprice.config.GlobalPriceProperties.Ebay;
import com.teamsoda.globalprice.config.GlobalPriceProperties.Etsy;
import com.teamsoda.globalprice.config.GlobalPriceProperties.KoreaExim;
import com.teamsoda.globalprice.config.GlobalPriceProperties.Shopify;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Spring 없이 어댑터를 만들 때 쓰는 설정 · 도구 */
public final class TestProps {

    public static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private TestProps() {
    }

    public static GlobalPriceProperties props(String base, Duration readTimeout) {
        return new GlobalPriceProperties("Asia/Seoul",
                new GlobalPriceProperties.Search(20, Duration.ofMinutes(10), 500, Duration.ofSeconds(2), readTimeout,
                        Duration.ofSeconds(3), Duration.ofMinutes(10), Duration.ofHours(6)),
                new GlobalPriceProperties.Pricing("해외 결제 수수료"),
                new GlobalPriceProperties.Home(List.of()),
                RealConfig.filter(List.of("used", "refurbished", "중고"), List.of("case", "케이스")),
                new GlobalPriceProperties.Sources(
                        new Ebay(true, "id", "secret", true, 3000, base, base + "/identity/v1/oauth2/token"),
                        new Shopify(true, "https://example.test/profile.json", 3000, base + "/api/ucp/mcp"),
                        new AliExpress(true, "key", "secret", "track", 3000, base + "/sync"),
                        new Etsy(true, "keystring:secret", 3000, base),
                        new KoreaExim("auth", base + "/exchangeJSON")),
                RealConfig.outliers(), RealConfig.display());
    }

    public static RestClient.Builder rest(GlobalPriceProperties props) {
        return new AppConfig().externalRestClientBuilder(props);
    }
}
