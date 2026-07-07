package com.metapool.pool.thread;

import com.metapool.common.lifecycle.PoolStats;
import com.metapool.common.lifecycle.ResourceLifecycle;
import com.metapool.common.pool.PoolConfig;

import java.util.concurrent.TimeUnit;

/**
 * 线程资源池，继承 {@link com.metapool.common.pool.AbstractResourcePool}，
 * 将线程池执行器包装为统一资源池接口。
 *
 * <p>内部委托 {@link SmartThreadPoolExecutor} 执行实际调度。
 * {@link #acquire()} / {@link #release(Object)} 语义上等同于保留/释放线程执行槽位。
 *
 * <h3>使用建议</h3>
 * <p>日常使用推荐直接使用 {@link SmartThreadPoolExecutor#execute(Runnable)}；
 * 本类主要用于统一生命周期管理（{@link #init()} / {@link #destroy()} / {@link #stats()}）。
 *
 * @since 0.1.0
 */
public class ThreadResourcePool implements ResourceLifecycle<ThreadResource> {

    private final SmartThreadPoolExecutor executor;
    private final PoolConfig config;

    public ThreadResourcePool(ThreadPoolConfig config) {
        this.config = config;
        this.executor = new SmartThreadPoolExecutor(config);
    }

    /**
     * 获取内部执行器，用于提交任务。
     */
    public SmartThreadPoolExecutor getExecutor() {
        return executor;
    }

    @Override
    public void init() {
        executor.prestartCoreThreads();
    }

    @Override
    public ThreadResource acquire() throws InterruptedException {
        // 线程池使用 execute() 提交任务，而非 acquire/release
        throw new UnsupportedOperationException(
                "Thread pool does not support acquire(); use getExecutor().execute(task) instead");
    }

    @Override
    public ThreadResource acquire(long timeout, TimeUnit unit) {
        throw new UnsupportedOperationException(
                "Thread pool does not support acquire(); use getExecutor().execute(task) instead");
    }

    @Override
    public void release(ThreadResource resource) {
        throw new UnsupportedOperationException(
                "Thread pool does not support release(); workers are auto-managed");
    }

    @Override
    public void destroy() {
        executor.shutdown();
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public PoolStats stats() {
        return PoolStats.builder()
                .activeCount(executor.getActiveCount())
                .idleCount(executor.getPoolSize() - executor.getActiveCount())
                .pendingCount(executor.getQueueSize())
                .totalAcquired(executor.getCompletedTasks())
                .totalReleased(executor.getCompletedTasks())
                .leakDetected(0)
                .build();
    }
}
