package com.metapool.starter.config;

import com.metapool.pool.lock.LockConfig;
import com.metapool.pool.lock.SmartReentrantLock;
import com.metapool.starter.MetaPoolProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;

/**
 * 分布式锁自动装配。
 *
 * <p>当 classpath 中存在 {@code SmartReentrantLock} 且配置
 * {@code metapool.lock.enabled=true}（默认）时，自动创建分布式锁。
 *
 * <p>锁的 Redis 连接信息从 {@code spring.data.redis.*} 标准配置读取。
 * 默认锁 key 为 {@code metapool:default}，可通过 {@code metapool.lock.key} 覆写。
 *
 * @since 0.1.0
 */
@AutoConfiguration
@ConditionalOnClass({SmartReentrantLock.class, RedisClient.class})
@ConditionalOnProperty(prefix = "metapool.lock", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LockAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LockAutoConfiguration.class);
    private static final String DEFAULT_LOCK_KEY = "metapool:default";

    @Bean
    public LockConfig lockConfig(MetaPoolProperties properties) {
        MetaPoolProperties.Lock props = properties.getLock();
        LockConfig config = new LockConfig();
        config.setDefaultTtlSeconds(props.getDefaultTtlSeconds());
        config.setRenewalIntervalSeconds(props.getRenewalIntervalSeconds());
        config.setMaxRetryCount(props.getMaxRetryCount());
        config.setRetryIntervalMillis(props.getRetryIntervalMillis());
        return config;
    }

    @Bean
    public RedisClient lockRedisClient(
            org.springframework.core.env.Environment environment) {
        String host = environment.getProperty("spring.data.redis.host", "localhost");
        int port = Integer.parseInt(environment.getProperty("spring.data.redis.port", "6379"));
        String password = environment.getProperty("spring.data.redis.password");

        RedisURI.Builder builder = RedisURI.Builder.redis(host, port);
        if (password != null && !password.isEmpty()) {
            builder.withPassword(password.toCharArray());
        }
        return RedisClient.create(builder.build());
    }

    @Bean
    public SmartReentrantLock smartReentrantLock(
            LockConfig config,
            RedisClient lockRedisClient,
            @Value("${metapool.lock.key:" + DEFAULT_LOCK_KEY + "}") String lockKey) {
        SmartReentrantLock lock = new SmartReentrantLock(config, lockRedisClient, lockKey);
        lock.init();
        log.info("分布式锁已创建: lockKey={}, defaultTtlSeconds={}",
                lockKey, config.getDefaultTtlSeconds());
        return lock;
    }
}
