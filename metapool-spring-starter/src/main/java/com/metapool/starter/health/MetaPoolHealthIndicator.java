package com.metapool.starter.health;

import com.metapool.common.manager.ResourceManager;
import com.metapool.common.stats.HealthStatus;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;

/**
 * 把控制面聚合健康暴露到 Spring Actuator {@code /actuator/health}。
 *
 * <p>映射：UP → {@link Status#UP}；DEGRADED → 自定义 {@code "DEGRADED"}；DOWN → {@link Status#DOWN}。
 *
 * @since 2.0.0
 */
public class MetaPoolHealthIndicator implements HealthIndicator {

    private static final Status DEGRADED = new Status("DEGRADED");

    private final ResourceManager manager;

    public MetaPoolHealthIndicator(ResourceManager manager) {
        this.manager = manager;
    }

    @Override
    public Health health() {
        HealthStatus h = manager.health();
        Status status = switch (h.status()) {
            case UP -> Status.UP;
            case DEGRADED -> DEGRADED;
            case DOWN -> Status.DOWN;
        };
        Health.Builder builder = new Health.Builder(status);
        if (!h.detail().isEmpty()) {
            builder.withDetail("problems", h.detail());
        }
        builder.withDetail("resources", manager.resources().size());
        return builder.build();
    }
}
