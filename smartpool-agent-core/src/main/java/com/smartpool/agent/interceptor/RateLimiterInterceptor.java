package com.smartpool.agent.interceptor;

import com.smartpool.agent.SmartPoolMetrics;
import com.smartpool.agent.model.PoolType;

import net.bytebuddy.asm.Advice;

import java.lang.reflect.Method;

/**
 * ByteBuddy 拦截器 — 拦截 {@code TokenBucketRateLimiter#tryAcquire()}，
 * 采集限流器通过/拒绝指标。
 *
 * <h3>设计说明</h3>
 * <p>本模块不依赖 {@code smartpool-pool-rate-limit}，通过反射调用
 * {@code getPassCount()} / {@code getRejectCount()}。
 *
 * @since 0.1.0
 */
public class RateLimiterInterceptor {

    private static final SmartPoolMetrics METRICS = SmartPoolMetrics.getInstance();

    private RateLimiterInterceptor() {
    }

    /**
     * {@code tryAcquire()} 退出时记录放行/限流结果。
     */
    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void onTryAcquireExit(
            @Advice.This Object limiter,
            @Advice.Return boolean passed) {

        String poolId = buildPoolId(limiter);
        METRICS.register(poolId, PoolType.RATE_LIMIT);

        METRICS.incrementCounter(poolId, "tryAcquireCount");
        if (passed) {
            METRICS.incrementCounter(poolId, "passCount");
        } else {
            METRICS.incrementCounter(poolId, "rejectCount");
        }

        // 更新瞬时值
        METRICS.setCounter(poolId, "totalPass", invokeGetter(limiter, "getPassCount"));
        METRICS.setCounter(poolId, "totalReject", invokeGetter(limiter, "getRejectCount"));
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
