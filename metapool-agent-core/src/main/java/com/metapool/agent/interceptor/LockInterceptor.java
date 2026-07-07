package com.metapool.agent.interceptor;

import com.metapool.agent.MetaPoolMetrics;
import com.metapool.agent.model.PoolType;

import net.bytebuddy.asm.Advice;

import java.lang.reflect.Method;

/**
 * ByteBuddy 拦截器 — 拦截 {@code SmartReentrantLock#tryLock()} /
 * {@code tryLock(long, TimeUnit)} / {@code unlock()}，采集分布式锁指标。
 *
 * <h3>设计说明</h3>
 * <p>本模块不依赖 {@code metapool-pool-lock}，通过反射访问锁状态。
 *
 * @since 0.1.0
 */
public class LockInterceptor {

    private static final MetaPoolMetrics METRICS = MetaPoolMetrics.getInstance();

    private LockInterceptor() {
    }

    // ==================== tryLock() 拦截 ====================

    /**
     * 记录 tryLock 开始时间。
     */
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static long onTryLockEnter() {
        return System.nanoTime();
    }

    /**
     * tryLock 退出时记录获取结果和等待时间。
     */
    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void onTryLockExit(
            @Advice.This Object lock,
            @Advice.Enter long startNanos,
            @Advice.Return boolean acquired) {

        String poolId = buildPoolId(lock);
        METRICS.register(poolId, PoolType.LOCK);

        long waitNanos = System.nanoTime() - startNanos;
        METRICS.addCounter(poolId, "lockWaitNanos", waitNanos);

        if (acquired) {
            METRICS.incrementCounter(poolId, "acquireCount");
        } else {
            METRICS.incrementCounter(poolId, "timeoutCount");
        }

        collectLockGauges(lock, poolId);
    }

    // ==================== unlock() 拦截 ====================

    /**
     * unlock 退出时记录持有时长。
     */
    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void onUnlockExit(@Advice.This Object lock) {
        String poolId = buildPoolId(lock);
        METRICS.register(poolId, PoolType.LOCK);

        long holdMs = invokeGetter(lock, "getHoldDurationMillis");
        if (holdMs > 0) {
            METRICS.addCounter(poolId, "holdDurationMillis", holdMs);
        }
        METRICS.incrementCounter(poolId, "releaseCount");
    }

    // ==================== 指标采集 ====================

    private static void collectLockGauges(Object lock, String poolId) {
        boolean isLocked = invokeBooleanGetter(lock, "isLocked");
        METRICS.setGauge(poolId, "isLocked", isLocked ? 1 : 0);

        boolean fallback = invokeBooleanGetter(lock, "isInFallbackMode");
        METRICS.setGauge(poolId, "fallbackMode", fallback ? 1 : 0);

        long holdMs = invokeGetter(lock, "getHoldDurationMillis");
        METRICS.setGauge(poolId, "holdDurationMillis", holdMs);
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

    private static boolean invokeBooleanGetter(Object target, String methodName) {
        try {
            Method m = target.getClass().getMethod(methodName);
            Object result = m.invoke(target);
            return result instanceof Boolean && (Boolean) result;
        } catch (Exception e) {
            return false;
        }
    }
}
