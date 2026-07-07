package com.metapool.agent.interceptor;

import com.metapool.agent.MetaPoolMetrics;
import com.metapool.agent.model.PoolType;

import net.bytebuddy.asm.Advice;

import java.lang.reflect.Method;

/**
 * ByteBuddy 拦截器 — 拦截 {@code AbstractResourcePool#acquire()} /
 * {@code acquire(long, TimeUnit)} / {@code release(Object)}，采集资源池
 * 运行指标。
 *
 * <p>覆盖所有继承 {@code AbstractResourcePool} 的池：DbConnectionPool、
 * RedisConnectionPool、GenericObjectPool、MemoryPool。
 *
 * <h3>设计说明</h3>
 * <p>本模块不依赖 {@code metapool-common}，通过反射调用 {@code stats()}
 * 获取指标快照。
 *
 * @since 0.1.0
 */
public class ResourcePoolInterceptor {

    private static final MetaPoolMetrics METRICS = MetaPoolMetrics.getInstance();

    private ResourcePoolInterceptor() {
    }

    // ==================== acquire() 拦截 ====================

    /**
     * 记录 acquire 开始时间。
     */
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static long onAcquireEnter(@Advice.This Object pool) {
        return System.nanoTime();
    }

    /**
     * acquire 成功后记录等待时间并更新指标快照。
     */
    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void onAcquireExit(
            @Advice.This Object pool,
            @Advice.Enter long startNanos,
            @Advice.Thrown(readOnly = true) Throwable throwable) {

        String poolId = buildPoolId(pool);
        PoolType poolType = detectPoolType(pool);
        METRICS.register(poolId, poolType);

        if (throwable == null) {
            long waitNanos = System.nanoTime() - startNanos;
            METRICS.addCounter(poolId, "acquireWaitNanos", waitNanos);
            METRICS.incrementCounter(poolId, "acquireCount");
        }

        collectStats(pool, poolId);
    }

    // ==================== release(Object) 拦截 ====================

    /**
     * release 成功后更新指标快照。
     */
    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void onReleaseExit(
            @Advice.This Object pool,
            @Advice.Thrown(readOnly = true) Throwable throwable) {

        String poolId = buildPoolId(pool);
        PoolType poolType = detectPoolType(pool);
        METRICS.register(poolId, poolType);

        if (throwable == null) {
            METRICS.incrementCounter(poolId, "releaseCount");
        }

        collectStats(pool, poolId);
    }

    // ==================== 指标采集 ====================

    /**
     * 通过反射调用 {@code stats()} 获取池指标快照。
     */
    private static void collectStats(Object pool, String poolId) {
        try {
            Method statsMethod = pool.getClass().getMethod("stats");
            Object stats = statsMethod.invoke(pool);
            if (stats == null) return;

            METRICS.setGauge(poolId, "activeCount", getIntField(stats, "getActiveCount"));
            METRICS.setGauge(poolId, "idleCount", getIntField(stats, "getIdleCount"));
            METRICS.setGauge(poolId, "pendingCount", getIntField(stats, "getPendingCount"));
            METRICS.setCounter(poolId, "totalAcquired", getLongField(stats, "getTotalAcquired"));
            METRICS.setCounter(poolId, "totalReleased", getLongField(stats, "getTotalReleased"));
            METRICS.setCounter(poolId, "leakDetected", getIntField(stats, "getLeakDetected"));
        } catch (Exception ignored) {
        }
    }

    // ==================== 池类型检测 ====================

    private static PoolType detectPoolType(Object pool) {
        String className = pool.getClass().getSimpleName().toLowerCase();
        if (className.contains("db")) return PoolType.DB;
        if (className.contains("redis")) return PoolType.REDIS;
        if (className.contains("object") || className.contains("generic")) return PoolType.OBJECT;
        if (className.contains("memory")) return PoolType.MEMORY;
        return PoolType.DB; // 默认
    }

    // ==================== 工具方法 ====================

    private static String buildPoolId(Object target) {
        return target.getClass().getSimpleName() + "@" + System.identityHashCode(target);
    }

    private static long getIntField(Object obj, String methodName) {
        try {
            Method m = obj.getClass().getMethod(methodName);
            Object result = m.invoke(obj);
            return result instanceof Number ? ((Number) result).longValue() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static long getLongField(Object obj, String methodName) {
        try {
            Method m = obj.getClass().getMethod(methodName);
            Object result = m.invoke(obj);
            return result instanceof Number ? ((Number) result).longValue() : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
