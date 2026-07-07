package com.smartpool.starter.health;

import com.smartpool.common.lifecycle.PoolStats;
import com.smartpool.pool.db.DbConnectionPool;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

/**
 * 数据库连接池健康检查。
 *
 * @since 0.1.0
 */
@Component
@ConditionalOnClass(DbConnectionPool.class)
@ConditionalOnBean(DbConnectionPool.class)
public class DbPoolHealthIndicator implements HealthIndicator {

    private final DbConnectionPool pool;

    public DbPoolHealthIndicator(DbConnectionPool pool) {
        this.pool = pool;
    }

    @Override
    public Health health() {
        try {
            PoolStats stats = pool.stats();
            if (stats == null) {
                return Health.down()
                        .withDetail("pool", "db")
                        .withDetail("error", "stats unavailable")
                        .build();
            }
            Health.Builder builder = stats.getLeakDetected() > 0
                    ? Health.down().withDetail("leakDetected", stats.getLeakDetected())
                    : Health.up();
            return builder
                    .withDetail("pool", "db")
                    .withDetail("activeCount", stats.getActiveCount())
                    .withDetail("idleCount", stats.getIdleCount())
                    .withDetail("pendingCount", stats.getPendingCount())
                    .withDetail("poolSize", stats.getPoolSize())
                    .build();
        } catch (Exception e) {
            return Health.down()
                    .withDetail("pool", "db")
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
