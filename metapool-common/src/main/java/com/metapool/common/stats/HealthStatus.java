package com.metapool.common.stats;

import java.util.Objects;

/**
 * 资源健康快照，不可变。由 {@link com.metapool.common.resource.ManagedLifecycle#health()} 返回，
 * 也用于控制面的聚合健康。
 *
 * @param status 健康级别
 * @param detail 人读描述（可为空字符串，不为 null）
 * @since 2.0.0
 */
public record HealthStatus(Status status, String detail) {

    /** 健康级别。 */
    public enum Status {
        /** 完全可用。 */
        UP,
        /** 降级可用（如底层连接部分失败、走 fallback）。 */
        DEGRADED,
        /** 不可用。 */
        DOWN
    }

    public HealthStatus {
        Objects.requireNonNull(status, "status must not be null");
        detail = detail == null ? "" : detail;
    }

    public static HealthStatus up() {
        return new HealthStatus(Status.UP, "");
    }

    public static HealthStatus down(String detail) {
        return new HealthStatus(Status.DOWN, detail);
    }

    public static HealthStatus degraded(String detail) {
        return new HealthStatus(Status.DEGRADED, detail);
    }
}
