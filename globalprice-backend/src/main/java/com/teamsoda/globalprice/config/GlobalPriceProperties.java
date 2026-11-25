package com.teamsoda.globalprice.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * application.yml 의 globalprice.* 설정.
 * 키 · 시크릿은 환경변수로만 들어오며 toString() 에 찍히지 않도록 각 소스 record 에서 가린다.
 */
@ConfigurationProperties("globalprice")
public record GlobalPriceProperties(
        @DefaultValue("Asia/Seoul") String zone,
        @DefaultValue Search search,
        @DefaultValue Pricing pricing,
        @DefaultValue Home home,
        @DefaultValue Filter filter,
        @DefaultValue Sources sources,
        @DefaultValue Outliers outliers,
        @DefaultValue Display display) {

    public record Search(
            @DefaultValue("20") int perSourceLimit,
            @DefaultValue("10m") Duration cacheTtl,
            @DefaultValue("500") long cacheMaxSize,
            @DefaultValue("2s") Duration connectTimeout,
            @DefaultValue("3s") Duration readTimeout,
            @DefaultValue("3s") Duration detailRefreshTimeout,
            @DefaultValue("10m") Duration rateLimitCooldown,
            @DefaultValue("6h") Duration staleAfter) {
    }

    public record Pricing(@DefaultValue("해외 결제 수수료") String feeRuleName) {
    }

    public record Home(@DefaultValue List<String> hotKeywords) {
    }

    /**
     * 걸러내기 (ResultFilter) 키워드. 영문은 단어 경계로 비교한다.
     *
     * @param carrierKeywords   미국 통신사 잠김 폰 표기 (unlockedKeywords 가 같이 있으면 통과)
     * @param watchBandKeywords 시계를 검색했을 때 밴드만 파는 리스팅
     */
    public record Filter(@DefaultValue List<String> notNewKeywords, @DefaultValue List<String> accessoryKeywords,
                         @DefaultValue List<String> carrierKeywords, @DefaultValue List<String> unlockedKeywords,
                         @DefaultValue List<String> watchBandKeywords) {
    }

    /** 이상 가격 (OutlierService): 상품명에 있으면 일시불 총액이 아닌 할부 · 계약금 표기 */
    public record Outliers(@DefaultValue List<String> installmentKeywords) {
    }

    /** 화면 표시: 쇼핑몰 자체 도메인 (상품 링크가 이 도메인이면 판매자 도메인을 따로 보여 주지 않음) */
    public record Display(@DefaultValue List<String> platformDomains) {
    }

    public record Sources(
            @DefaultValue Ebay ebay,
            @DefaultValue Shopify shopify,
            @DefaultValue AliExpress aliexpress,
            @DefaultValue Etsy etsy,
            @DefaultValue KoreaExim koreaexim) {
    }

    public record Ebay(boolean enabled, String clientId, String clientSecret,
                       @DefaultValue("true") boolean sandbox, @DefaultValue("3000") int dailyBudget,
                       String baseUrl, String tokenUrl) {
        public String resolvedBaseUrl() {
            if (baseUrl != null && !baseUrl.isBlank()) return baseUrl;
            return sandbox ? "https://api.sandbox.ebay.com" : "https://api.ebay.com";
        }
        public String resolvedTokenUrl() {
            if (tokenUrl != null && !tokenUrl.isBlank()) return tokenUrl;
            return resolvedBaseUrl() + "/identity/v1/oauth2/token";
        }
        @Override public String toString() { return "Ebay[enabled=" + enabled + ", sandbox=" + sandbox + "]"; }
    }

    public record Shopify(boolean enabled, String agentProfile, @DefaultValue("3000") int dailyBudget,
                          @DefaultValue("https://catalog.shopify.com/api/ucp/mcp") String endpoint) {
    }

    public record AliExpress(boolean enabled, String appKey, String appSecret, String trackingId,
                             @DefaultValue("3000") int dailyBudget,
                             @DefaultValue("https://api-sg.aliexpress.com/sync") String endpoint) {
        @Override public String toString() { return "AliExpress[enabled=" + enabled + "]"; }
    }

    public record Etsy(boolean enabled, String apiKey, @DefaultValue("3000") int dailyBudget,
                       @DefaultValue("https://openapi.etsy.com") String baseUrl) {
        @Override public String toString() { return "Etsy[enabled=" + enabled + "]"; }
    }

    public record KoreaExim(String authKey,
                            @DefaultValue("https://oapi.koreaexim.go.kr/site/program/financial/exchangeJSON") String url) {
        @Override public String toString() { return "KoreaExim[configured=" + (authKey != null && !authKey.isBlank()) + "]"; }
    }
}
