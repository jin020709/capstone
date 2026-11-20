package com.teamsoda.globalprice.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.teamsoda.globalprice.auth.LoginInterceptor;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LoginInterceptor())
                .addPathPatterns("/api/wishlist/**", "/api/wishlist",
                        "/api/alerts/**", "/api/alerts",
                        "/api/notifications/**", "/api/notifications",
                        "/api/auth/me", "/api/auth/logout");
    }
}
