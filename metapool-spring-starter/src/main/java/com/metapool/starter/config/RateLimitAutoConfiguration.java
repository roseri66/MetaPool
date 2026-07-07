package com.metapool.starter.config;

import com.metapool.pool.rate.limit.RateLimiterConfig;
import com.metapool.pool.rate.limit.TokenBucketRateLimiter;
import com.metapool.starter.MetaPoolProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * 限流器自动装配。
 *
 * <p>当 classpath 中存在 {@code TokenBucketRateLimiter} 且配置
 * {@code metapool.rate-limit.enabled=true}（默认）时，自动创建限流器。
 *
 * @since 0.1.0
 */
@AutoConfiguration
@ConditionalOnClass(TokenBucketRateLimiter.class)
@ConditionalOnProperty(prefix = "metapool.rate-limit", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RateLimitAutoConfiguration.class);

    @Bean
    public RateLimiterConfig rateLimiterConfig(MetaPoolProperties properties) {
        MetaPoolProperties.RateLimit props = properties.getRateLimit();
        RateLimiterConfig config = new RateLimiterConfig();
        config.setPermitsPerSecond(props.getPermitsPerSecond());
        config.setWarmUpSeconds(props.getWarmUpSeconds());
        return config;
    }

    @Bean
    public TokenBucketRateLimiter tokenBucketRateLimiter(RateLimiterConfig config) {
        TokenBucketRateLimiter rateLimiter = new TokenBucketRateLimiter(config);
        rateLimiter.init();
        log.info("限流器已创建: permitsPerSecond={}, warmUpSeconds={}",
                config.getPermitsPerSecond(), config.getWarmUpSeconds());
        return rateLimiter;
    }
}
