package com.teamsoda.globalprice.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import com.teamsoda.globalprice.config.GlobalPriceProperties;

/** 단위 테스트용: 실제 application.yml 의 globalprice.* 값 (키워드 목록이 코드가 아니라 설정에 있으므로) */
public final class RealConfig {

    private static final Binder BINDER = binder();

    private RealConfig() {
    }

    private static Binder binder() {
        try {
            List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                    .load("application", new ClassPathResource("application.yml"));
            return new Binder(ConfigurationPropertySources.from(sources));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static GlobalPriceProperties.Filter filter() {
        return BINDER.bind("globalprice.filter", GlobalPriceProperties.Filter.class).get();
    }

    public static GlobalPriceProperties.Outliers outliers() {
        return BINDER.bind("globalprice.outliers", GlobalPriceProperties.Outliers.class).get();
    }

    public static GlobalPriceProperties.Display display() {
        return BINDER.bind("globalprice.display", GlobalPriceProperties.Display.class).get();
    }

    /** 실제 설정의 통신사 · 시계 밴드 목록에 테스트용 새 상품 · 액세서리 키워드만 바꾼 걸러내기 설정 */
    public static GlobalPriceProperties.Filter filter(List<String> notNew, List<String> accessory) {
        GlobalPriceProperties.Filter f = filter();
        return new GlobalPriceProperties.Filter(notNew, accessory, f.carrierKeywords(), f.unlockedKeywords(),
                f.watchBandKeywords());
    }
}
