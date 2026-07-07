package com.metapool.spi.ai;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 资源池运行时指标快照，作为 AI 诊断和调优建议的输入数据。
 *
 * <p>快照反映某一时刻的瞬时状态，各字段之间不保证原子一致性。
 * 不可变对象，通过 {@link Builder} 构造。
 *
 * <h3>与 {@code PoolStats} 的区别</h3>
 * <p>{@code PoolStats}（common 模块）仅包含 6 个核心字段，{@code PoolMetricsSnapshot}
 * 扩展了资源池标识（poolName / resourceType）、时间戳和可扩展的额外指标，满足 AI
 * 诊断对多维度数据的需求。
 *
 * @since 0.1.0
 */
public final class PoolMetricsSnapshot {

    /** 资源池名称，用于标识具体池实例 */
    private final String poolName;

    /** 资源类型，取值同 {@code com.metapool.common.enums.ResourceType} 枚举名 */
    private final String resourceType;

    /** 快照采集时间戳（epoch 毫秒） */
    private final long timestamp;

    /** 当前活跃（已借出）资源数 */
    private final int activeCount;

    /** 当前空闲（可借出）资源数 */
    private final int idleCount;

    /** 当前等待获取资源的请求数 */
    private final int pendingCount;

    /** 历史累计获取次数 */
    private final long totalAcquired;

    /** 历史累计归还次数 */
    private final long totalReleased;

    /** 当前检测到的泄露资源数 */
    private final int leakDetected;

    /**
     * 池特有扩展指标，如线程池的 {@code queueSize / rejectedTotal}、
     * 限流器的 {@code passTotal / rejectTotal / availablePermits} 等。
     * 不可变视图。
     */
    private final Map<String, Object> extraMetrics;

    private PoolMetricsSnapshot(Builder builder) {
        this.poolName = builder.poolName;
        this.resourceType = builder.resourceType;
        this.timestamp = builder.timestamp;
        this.activeCount = builder.activeCount;
        this.idleCount = builder.idleCount;
        this.pendingCount = builder.pendingCount;
        this.totalAcquired = builder.totalAcquired;
        this.totalReleased = builder.totalReleased;
        this.leakDetected = builder.leakDetected;
        this.extraMetrics = Collections.unmodifiableMap(new HashMap<>(builder.extraMetrics));
    }

    public String getPoolName() {
        return poolName;
    }

    public String getResourceType() {
        return resourceType;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public int getActiveCount() {
        return activeCount;
    }

    public int getIdleCount() {
        return idleCount;
    }

    public int getPendingCount() {
        return pendingCount;
    }

    public long getTotalAcquired() {
        return totalAcquired;
    }

    public long getTotalReleased() {
        return totalReleased;
    }

    public int getLeakDetected() {
        return leakDetected;
    }

    /** 返回池总大小（活跃 + 空闲） */
    public int getPoolSize() {
        return activeCount + idleCount;
    }

    /** 返回不可变扩展指标视图 */
    public Map<String, Object> getExtraMetrics() {
        return extraMetrics;
    }

    @Override
    public String toString() {
        return "PoolMetricsSnapshot{poolName='" + poolName
                + "', resourceType=" + resourceType
                + ", timestamp=" + timestamp
                + ", active=" + activeCount
                + ", idle=" + idleCount
                + ", pending=" + pendingCount
                + ", totalAcquired=" + totalAcquired
                + ", totalReleased=" + totalReleased
                + ", leakDetected=" + leakDetected
                + ", extraMetrics=" + extraMetrics + '}';
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String poolName;
        private String resourceType;
        private long timestamp;
        private int activeCount;
        private int idleCount;
        private int pendingCount;
        private long totalAcquired;
        private long totalReleased;
        private int leakDetected;
        private final Map<String, Object> extraMetrics = new HashMap<>();

        private Builder() {
        }

        public Builder poolName(String poolName) {
            this.poolName = poolName;
            return this;
        }

        public Builder resourceType(String resourceType) {
            this.resourceType = resourceType;
            return this;
        }

        public Builder timestamp(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder activeCount(int activeCount) {
            this.activeCount = activeCount;
            return this;
        }

        public Builder idleCount(int idleCount) {
            this.idleCount = idleCount;
            return this;
        }

        public Builder pendingCount(int pendingCount) {
            this.pendingCount = pendingCount;
            return this;
        }

        public Builder totalAcquired(long totalAcquired) {
            this.totalAcquired = totalAcquired;
            return this;
        }

        public Builder totalReleased(long totalReleased) {
            this.totalReleased = totalReleased;
            return this;
        }

        public Builder leakDetected(int leakDetected) {
            this.leakDetected = leakDetected;
            return this;
        }

        /** 添加一条扩展指标 */
        public Builder putExtraMetric(String key, Object value) {
            this.extraMetrics.put(key, value);
            return this;
        }

        /** 批量添加扩展指标 */
        public Builder putAllExtraMetrics(Map<String, Object> metrics) {
            this.extraMetrics.putAll(metrics);
            return this;
        }

        public PoolMetricsSnapshot build() {
            if (poolName == null || poolName.isEmpty()) {
                throw new IllegalArgumentException("poolName must not be null or empty");
            }
            if (resourceType == null || resourceType.isEmpty()) {
                throw new IllegalArgumentException("resourceType must not be null or empty");
            }
            return new PoolMetricsSnapshot(this);
        }
    }
}
