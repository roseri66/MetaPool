package com.metapool.starter.health;

import com.metapool.common.lifecycle.PoolStats;
import com.metapool.pool.redis.RedisConnectionPool;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

/**
 * Redis 连接池健康检查。
 *
 * @since 0.1.0
 */
@Component
@ConditionalOnClass(RedisConnectionPool.class)
@ConditionalOnBean(RedisConnectionPool.class)
public class RedisPoolHealthIndicator implements HealthIndicator {

    private final RedisConnectionPool pool;

    public RedisPoolHealthIndicator(RedisConnectionPool pool) {
        this.pool = pool;
    }

    @Override
    public Health health() {
        try {
            PoolStats stats = pool.stats();
            if (stats == null) {
                return Health.down()
                        .withDetail("pool", "redis")
                        .withDetail("error", "stats unavailable")
                        .build();
            }
            Health.Builder builder = stats.getLeakDetected() > 0
                    ? Health.down().withDetail("leakDetected", stats.getLeakDetected())
                    : Health.up();
            return builder
                    .withDetail("pool", "redis")
                    .withDetail("activeCount", stats.getActiveCount())
                    .withDetail("idleCount", stats.getIdleCount())
                    .withDetail("pendingCount", stats.getPendingCount())
                    .withDetail("poolSize", stats.getPoolSize())
                    .build();
        } catch (Exception e) {
            return Health.down()
                    .withDetail("pool", "redis")
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
