package com.metapool.agent.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 资源池指标快照，不可变 DTO。
 *
 * <p>由 {@link com.metapool.agent.MetaPoolMetrics} 的 {@code snapshot()} 方法生成，
 * 供 SPEC-14 Prometheus 暴露使用。
 *
 * <h3>线程安全</h3>
 * <p>不可变对象，线程安全。
 *
 * @since 0.1.0
 */
public final class PoolMetricsSnapshot {

    private final String poolId;
    private final PoolType poolType;
    private final Map<String, Long> counters;
    private final Map<String, Double> gauges;

    private PoolMetricsSnapshot(Builder builder) {
        this.poolId = builder.poolId;
        this.poolType = builder.poolType;
        this.counters = Collections.unmodifiableMap(new HashMap<>(builder.counters));
        this.gauges = Collections.unmodifiableMap(new HashMap<>(builder.gauges));
    }

    public String getPoolId() {
        return poolId;
    }

    public PoolType getPoolType() {
        return poolType;
    }

    /**
     * 返回只读计数器映射（如 totalAcquired、rejectCount）。
     */
    public Map<String, Long> getCounters() {
        return counters;
    }

    /**
     * 返回只读瞬时值映射（如 activeCount、queueSize）。
     */
    public Map<String, Double> getGauges() {
        return gauges;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return "PoolMetricsSnapshot{poolId='" + poolId + "', type=" + poolType
                + ", counters=" + counters.size() + ", gauges=" + gauges.size() + '}';
    }

    /**
     * {@link PoolMetricsSnapshot} 构造器。
     */
    public static final class Builder {

        private String poolId;
        private PoolType poolType;
        private final Map<String, Long> counters = new HashMap<>();
        private final Map<String, Double> gauges = new HashMap<>();

        private Builder() {
        }

        public Builder poolId(String poolId) {
            this.poolId = poolId;
            return this;
        }

        public Builder poolType(PoolType poolType) {
            this.poolType = poolType;
            return this;
        }

        public Builder counter(String name, long value) {
            this.counters.put(name, value);
            return this;
        }

        public Builder gauge(String name, double value) {
            this.gauges.put(name, value);
            return this;
        }

        public Builder counters(Map<String, Long> counters) {
            this.counters.putAll(counters);
            return this;
        }

        public Builder gauges(Map<String, Double> gauges) {
            this.gauges.putAll(gauges);
            return this;
        }

        /**
         * 构建不可变快照。
         *
         * @return 新的 {@link PoolMetricsSnapshot}
         * @throws IllegalArgumentException 如果 poolId 为 null 或 poolType 为 null
         */
        public PoolMetricsSnapshot build() {
            if (poolId == null || poolId.trim().isEmpty()) {
                throw new IllegalArgumentException("poolId must not be null or empty");
            }
            if (poolType == null) {
                throw new IllegalArgumentException("poolType must not be null");
            }
            return new PoolMetricsSnapshot(this);
        }
    }
}
