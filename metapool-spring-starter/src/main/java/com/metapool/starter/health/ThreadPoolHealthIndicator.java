package com.metapool.starter.health;

import com.metapool.common.lifecycle.PoolStats;
import com.metapool.pool.thread.ThreadResourcePool;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

/**
 * 线程池健康检查。
 *
 * <p>检查线程池运行状态和泄露检测，结果合入 {@code /actuator/health}。
 *
 * @since 0.1.0
 */
@Component
@ConditionalOnClass(ThreadResourcePool.class)
@ConditionalOnBean(ThreadResourcePool.class)
public class ThreadPoolHealthIndicator implements HealthIndicator {

    private final ThreadResourcePool pool;

    public ThreadPoolHealthIndicator(ThreadResourcePool pool) {
        this.pool = pool;
    }

    @Override
    public Health health() {
        try {
            PoolStats stats = pool.stats();
            if (stats == null) {
                return Health.down()
                        .withDetail("pool", "thread")
                        .withDetail("error", "stats unavailable")
                        .build();
            }
            Health.Builder builder = stats.getLeakDetected() > 0
                    ? Health.down().withDetail("leakDetected", stats.getLeakDetected())
                    : Health.up();
            return builder
                    .withDetail("pool", "thread")
                    .withDetail("activeCount", stats.getActiveCount())
                    .withDetail("idleCount", stats.getIdleCount())
                    .withDetail("pendingCount", stats.getPendingCount())
                    .withDetail("totalAcquired", stats.getTotalAcquired())
                    .withDetail("totalReleased", stats.getTotalReleased())
                    .build();
        } catch (Exception e) {
            return Health.down()
                    .withDetail("pool", "thread")
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
