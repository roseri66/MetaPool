package com.smartpool.agent.interceptor;

import com.smartpool.agent.SmartPoolMetrics;
import com.smartpool.agent.model.PoolType;

import net.bytebuddy.asm.Advice;

import java.lang.reflect.Method;

/**
 * ByteBuddy 拦截器 — 拦截 {@code SmartThreadPoolExecutor#execute(Runnable)}，
 * 采集线程池运行指标。
 *
 * <p>同时拦截内部类 {@code Worker#run()} 的进入/退出以测量任务执行耗时。
 *
 * <h3>设计说明</h3>
 * <p>本模块不依赖 {@code smartpool-pool-thread}，所有状态访问通过反射。
 *
 * @since 0.1.0
 */
public class ThreadPoolInterceptor {

    private static final SmartPoolMetrics METRICS = SmartPoolMetrics.getInstance();

    private ThreadPoolInterceptor() {
    }

    // ==================== execute(Runnable) 拦截 ====================

    /**
     * {@code execute(Runnable)} 进入时记录指标。
     */
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onExecuteEnter(@Advice.This Object executor) {
        String poolId = buildPoolId(executor);
        METRICS.register(poolId, PoolType.THREAD);
        METRICS.incrementCounter(poolId, "executeCount");
    }

    /**
     * {@code execute(Runnable)} 退出时更新指标快照。
     */
    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void onExecuteExit(@Advice.This Object executor) {
        String poolId = buildPoolId(executor);
        collectThreadPoolGauges(executor, poolId);
    }

    // ==================== 指标采集 ====================

    private static void collectThreadPoolGauges(Object executor, String poolId) {
        METRICS.setGauge(poolId, "activeCount", invokeGetter(executor, "getActiveCount"));
        METRICS.setGauge(poolId, "poolSize", invokeGetter(executor, "getPoolSize"));
        METRICS.setGauge(poolId, "queueSize", invokeGetter(executor, "getQueueSize"));
        METRICS.setCounter(poolId, "completedTasks", invokeGetter(executor, "getCompletedTasks"));
        METRICS.setCounter(poolId, "rejectedTasks", invokeGetter(executor, "getRejectedTasks"));
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
