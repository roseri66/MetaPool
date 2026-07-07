package com.metapool.agent;

import com.metapool.agent.model.PoolMetricsSnapshot;
import com.metapool.agent.model.PoolType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 全局指标注册表，线程安全单例。
 *
 * <p>所有拦截器通过此注册表写入指标。每个池实例由 {@code poolId}（格式：
 * {@code "className@identityHashCode"}）唯一标识。
 *
 * <h3>线程安全</h3>
 * <p>所有读写操作线程安全。计数器使用 {@link AtomicLong}，注册表使用
 * {@link ConcurrentHashMap}。
 *
 * @since 0.1.0
 */
public final class MetaPoolMetrics {

    private static final MetaPoolMetrics INSTANCE = new MetaPoolMetrics();

    // ==================== 指标存储 ====================

    /**
     * 每个池实例的计数器（如 totalAcquired、rejectCount）。
     * Key: poolId, Value: metricName → AtomicLong
     */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, AtomicLong>> counters = new ConcurrentHashMap<>();

    /**
     * 每个池实例的瞬时值（如 activeCount、queueSize）。
     * Key: poolId, Value: metricName → AtomicLong（存储为 long，读取时转 double）
     */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, AtomicLong>> gauges = new ConcurrentHashMap<>();

    /**
     * 池 ID → 池类型映射。
     */
    private final ConcurrentHashMap<String, PoolType> poolTypes = new ConcurrentHashMap<>();

    private MetaPoolMetrics() {
    }

    public static MetaPoolMetrics getInstance() {
        return INSTANCE;
    }

    // ==================== 注册 ====================

    /**
     * 注册一个池实例（首次拦截时调用，幂等）。
     *
     * @param poolId   池唯一标识
     * @param poolType 池类型
     */
    public void register(String poolId, PoolType poolType) {
        poolTypes.putIfAbsent(poolId, poolType);
        counters.computeIfAbsent(poolId, k -> new ConcurrentHashMap<>());
        gauges.computeIfAbsent(poolId, k -> new ConcurrentHashMap<>());
    }

    // ==================== 计数器操作 ====================

    /**
     * 递增计数器。
     */
    public void incrementCounter(String poolId, String metricName) {
        ensurePoolRegistered(poolId);
        Map<String, AtomicLong> poolCounters = counters.get(poolId);
        if (poolCounters != null) {
            poolCounters.computeIfAbsent(metricName, k -> new AtomicLong(0)).incrementAndGet();
        }
    }

    /**
     * 按指定数值递增计数器。
     */
    public void addCounter(String poolId, String metricName, long delta) {
        ensurePoolRegistered(poolId);
        Map<String, AtomicLong> poolCounters = counters.get(poolId);
        if (poolCounters != null) {
            poolCounters.computeIfAbsent(metricName, k -> new AtomicLong(0)).addAndGet(delta);
        }
    }

    /**
     * 设置计数器绝对值。
     */
    public void setCounter(String poolId, String metricName, long value) {
        ensurePoolRegistered(poolId);
        Map<String, AtomicLong> poolCounters = counters.get(poolId);
        if (poolCounters != null) {
            AtomicLong counter = poolCounters.get(metricName);
            if (counter == null) {
                AtomicLong existing = poolCounters.putIfAbsent(metricName, new AtomicLong(value));
                if (existing != null) {
                    existing.set(value);
                }
            } else {
                counter.set(value);
            }
        }
    }

    // ==================== 瞬时值操作 ====================

    /**
     * 设置瞬时值（gauge）。
     */
    public void setGauge(String poolId, String metricName, long value) {
        ensurePoolRegistered(poolId);
        Map<String, AtomicLong> poolGauges = gauges.get(poolId);
        if (poolGauges != null) {
            AtomicLong gauge = poolGauges.get(metricName);
            if (gauge == null) {
                AtomicLong existing = poolGauges.putIfAbsent(metricName, new AtomicLong(value));
                if (existing != null) {
                    existing.set(value);
                }
            } else {
                gauge.set(value);
            }
        }
    }

    // ==================== 查询 ====================

    /**
     * 获取指定池的计数器值。
     */
    public long getCounter(String poolId, String metricName) {
        Map<String, AtomicLong> poolCounters = counters.get(poolId);
        if (poolCounters == null) return 0;
        AtomicLong counter = poolCounters.get(metricName);
        return counter != null ? counter.get() : 0;
    }

    /**
     * 获取指定池的瞬时值。
     */
    public long getGauge(String poolId, String metricName) {
        Map<String, AtomicLong> poolGauges = gauges.get(poolId);
        if (poolGauges == null) return 0;
        AtomicLong gauge = poolGauges.get(metricName);
        return gauge != null ? gauge.get() : 0;
    }

    /**
     * 获取池类型。
     */
    public PoolType getPoolType(String poolId) {
        return poolTypes.get(poolId);
    }

    // ==================== 快照 ====================

    /**
     * 获取所有已注册池的快照列表。
     *
     * @return 不可变快照列表
     */
    public List<PoolMetricsSnapshot> snapshot() {
        List<PoolMetricsSnapshot> result = new ArrayList<>();
        for (String poolId : poolTypes.keySet()) {
            PoolType type = poolTypes.get(poolId);
            if (type == null) continue;

            PoolMetricsSnapshot.Builder builder = PoolMetricsSnapshot.builder()
                    .poolId(poolId)
                    .poolType(type);

            Map<String, AtomicLong> poolCounters = counters.get(poolId);
            if (poolCounters != null) {
                for (Map.Entry<String, AtomicLong> entry : poolCounters.entrySet()) {
                    builder.counter(entry.getKey(), entry.getValue().get());
                }
            }

            Map<String, AtomicLong> poolGauges = gauges.get(poolId);
            if (poolGauges != null) {
                for (Map.Entry<String, AtomicLong> entry : poolGauges.entrySet()) {
                    builder.gauge(entry.getKey(), (double) entry.getValue().get());
                }
            }

            result.add(builder.build());
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 获取已注册的池数量。
     */
    public int poolCount() {
        return poolTypes.size();
    }

    /**
     * 清除所有指标（仅测试用）。
     */
    public void clear() {
        counters.clear();
        gauges.clear();
        poolTypes.clear();
    }

    // ==================== 内部方法 ====================

    private void ensurePoolRegistered(String poolId) {
        counters.computeIfAbsent(poolId, k -> new ConcurrentHashMap<>());
        gauges.computeIfAbsent(poolId, k -> new ConcurrentHashMap<>());
    }
}
