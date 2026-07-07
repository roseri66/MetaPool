package com.metapool.starter.config;

import com.metapool.pool.redis.RedisConnectionFactory;
import com.metapool.pool.redis.RedisConnectionPool;
import com.metapool.pool.redis.RedisConnectionValidator;
import com.metapool.pool.redis.RedisPoolConfig;
import com.metapool.starter.MetaPoolProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;

/**
 * Redis 连接池自动装配。
 *
 * <p>当 classpath 中存在 {@code RedisConnectionPool} 且配置
 * {@code metapool.redis.enabled=true}（默认）时，自动创建 Redis 连接池。
 *
 * <p>Redis 连接信息从 {@code spring.data.redis.*} 标准配置读取。
 *
 * @since 0.1.0
 */
@AutoConfiguration
@ConditionalOnClass({RedisConnectionPool.class, RedisClient.class})
@ConditionalOnProperty(prefix = "metapool.redis", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RedisPoolAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RedisPoolAutoConfiguration.class);

    @Bean
    public RedisPoolConfig redisPoolConfig(MetaPoolProperties properties) {
        MetaPoolProperties.RedisPool props = properties.getRedis();
        RedisPoolConfig config = new RedisPoolConfig();
        config.setPoolName(props.getPoolName());
        config.setMinIdle(props.getMinIdle());
        config.setMaxPoolSize(props.getMaxPoolSize());
        config.setMaxLifetimeMinutes(props.getMaxLifetimeMinutes());
        config.setIdleTimeoutSeconds(props.getIdleTimeoutSeconds());
        config.setConnectionTimeoutSeconds(props.getConnectionTimeoutSeconds());
        config.setLeakDetectionThresholdSeconds(props.getLeakDetectionThresholdSeconds());
        return config;
    }

    @Bean
    public RedisConnectionFactory redisConnectionFactory(Environment environment) {
        String host = environment.getProperty("spring.data.redis.host", "localhost");
        int port = Integer.parseInt(environment.getProperty("spring.data.redis.port", "6379"));
        String password = environment.getProperty("spring.data.redis.password");

        RedisURI.Builder builder = RedisURI.Builder.redis(host, port);
        if (password != null && !password.isEmpty()) {
            builder.withPassword(password.toCharArray());
        }
        RedisURI redisUri = builder.build();
        RedisClient redisClient = RedisClient.create(redisUri);

        return () -> {
            StatefulRedisConnection<String, String> conn = redisClient.connect();
            log.debug("创建 Redis 连接: {}:{}", host, port);
            return conn;
        };
    }

    @Bean
    public RedisConnectionValidator redisConnectionValidator() {
        return (connection, timeoutSeconds) -> {
            if (connection instanceof StatefulRedisConnection) {
                try {
                    @SuppressWarnings("unchecked")
                    StatefulRedisConnection<String, String> conn =
                            (StatefulRedisConnection<String, String>) connection;
                    String result = conn.sync().ping();
                    return "PONG".equals(result);
                } catch (Exception e) {
                    log.debug("Redis PING 失败: {}", e.getMessage());
                    return false;
                }
            }
            return false;
        };
    }

    @Bean
    public RedisConnectionPool redisConnectionPool(RedisPoolConfig config,
                                                    RedisConnectionFactory connectionFactory,
                                                    RedisConnectionValidator validator) {
        RedisConnectionPool pool = new RedisConnectionPool(config, connectionFactory, validator);
        pool.init();
        log.info("Redis 连接池已创建: poolName={}, minIdle={}, maxPoolSize={}",
                config.getPoolName(), config.getMinIdle(), config.getMaxPoolSize());
        return pool;
    }
}
