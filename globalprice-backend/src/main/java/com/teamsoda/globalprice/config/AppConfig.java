package com.teamsoda.globalprice.config;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.ZoneId;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.client.RestClient;

import com.github.benmanes.caffeine.cache.Caffeine;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(GlobalPriceProperties.class)
public class AppConfig {

    public static final String SEARCH_CACHE = "sourceSearch";

    /** 시간에 의존하는 코드는 모두 이 Clock 을 주입받는다 (테스트에서 고정 시각으로 교체) */
    @Bean
    public Clock clock(GlobalPriceProperties props) {
        return Clock.system(ZoneId.of(props.zone()));
    }

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CacheManager cacheManager(GlobalPriceProperties props) {
        CaffeineCacheManager manager = new CaffeineCacheManager(SEARCH_CACHE);
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(props.search().cacheTtl())
                .maximumSize(props.search().cacheMaxSize()));
        return manager;
    }

    /** 외부 API 공통 RestClient 빌더: 연결 2초 · 읽기 3초 */
    @Bean
    public RestClient.Builder externalRestClientBuilder(GlobalPriceProperties props) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(props.search().connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(props.search().readTimeout());
        return RestClient.builder().requestFactory(factory);
    }
}
