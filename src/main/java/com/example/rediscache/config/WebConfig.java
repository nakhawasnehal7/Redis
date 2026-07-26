package com.example.rediscache.config;

import com.example.rediscache.filter.ApiRateLimitFilter;
import com.example.rediscache.filter.SessionAuthFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class WebConfig {

    private final SessionAuthFilter sessionAuthFilter;
    private final ApiRateLimitFilter apiRateLimitFilter;

    @Bean
    public FilterRegistrationBean<SessionAuthFilter> sessionAuthFilterRegistration() {
        FilterRegistrationBean<SessionAuthFilter> registration = new FilterRegistrationBean<>(sessionAuthFilter);
        registration.addUrlPatterns("/api/*");
        registration.setOrder(1);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<ApiRateLimitFilter> apiRateLimitFilterRegistration() {
        FilterRegistrationBean<ApiRateLimitFilter> registration = new FilterRegistrationBean<>(apiRateLimitFilter);
        registration.addUrlPatterns("/api/*");
        // Runs AFTER SessionAuthFilter (order 1) so authenticatedUserId is already
        // set on the request when this filter reads it for per-user limiting.
        registration.setOrder(2);
        return registration;
    }
}
