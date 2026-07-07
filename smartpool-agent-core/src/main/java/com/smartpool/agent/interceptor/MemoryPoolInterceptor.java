package com.smartpool.agent.interceptor;

import com.smartpool.agent.SmartPoolMetrics;
import com.smartpool.agent.model.PoolType;

import net.bytebuddy.asm.Advice;

import java.lang.reflect.Method;

/**
 * ByteBuddy 拦截器 — 拦截 {@code MemoryPool#allocateDirect(int)} /
 * {@code freeDirect(ByteBuffer)}，采集堆外内存使用指标。
 *
 * <p>注意：{@code acquire()/release()} 由 {@link ResourcePoolInterceptor}
 * 统一拦截。
 *
 * <h3>设计说明</h3>
 * <p>本模块不依赖 {@code smartpool-pool-memory}，通过反射调用
 * {@code getHeapMemoryUsed()} / {@code getDirectMemoryUsed()}。
 *
 * @since 0.1.0
 */
public class MemoryPoolInterceptor {

    private static final SmartPoolMetrics METRICS = SmartPoolMetrics.getInstance();

    private MemoryPoolInterceptor() {
    }

    // ==================== allocateDirect(int) 拦截 ====================

    /**
     * allocateDirect 退出时更新内存用量指标。
     */
    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void onAllocateDirectExit(
            @Advice.This Object pool,
            @Advice.Argument(0) int capacity,
            @Advice.Thrown(readOnly = true) Throwable throwable) {

        String poolId = buildPoolId(pool);
        METRICS.register(poolId, PoolType.MEMORY);

        if (throwable == null) {
            METRICS.incrementCounter(poolId, "allocateDirectCount");
            METRICS.addCounter(poolId, "directAllocatedBytes", capacity);
        }

        collectMemoryGauges(pool, poolId);
    }

    // ==================== freeDirect(ByteBuffer) 拦截 ====================

    /**
     * freeDirect 退出时更新内存用量指标。
     */
    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void onFreeDirectExit(@Advice.This Object pool) {
        String poolId = buildPoolId(pool);
        METRICS.register(poolId, PoolType.MEMORY);

        METRICS.incrementCounter(poolId, "freeDirectCount");
        collectMemoryGauges(pool, poolId);
    }

    // ==================== 指标采集 ====================

    private static void collectMemoryGauges(Object pool, String poolId) {
        METRICS.setGauge(poolId, "heapMemoryUsed", invokeGetter(pool, "getHeapMemoryUsed"));
        METRICS.setGauge(poolId, "directMemoryUsed", invokeGetter(pool, "getDirectMemoryUsed"));
        METRICS.setGauge(poolId, "totalMemoryUsed", invokeGetter(pool, "getTotalMemoryUsed"));
        METRICS.setGauge(poolId, "maxMemoryBytes", invokeGetter(pool, "getMaxMemoryBytes"));
    }

    // ==================== 工具方法 ====================

    private static String buildPoolId(Object target) {
        return target.getClass().getSimpleName() + "@" + System.identityHashCode(target);
    }

    private static long invokeGetter(Object target, String methodName) {
        try {
            Method m = target.getClass().getMethod(methodName);
            Object result = m.invoke(target);
            if (result instanceof Number) {
                return ((Number) result).longValue();
            }
            return 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
