package com.metapool.pool.thread;

/**
 * 线程池拒绝策略接口。
 *
 * <p>当任务队列已满且线程数已达最大值时，新提交的任务将被拒绝，
 * 由实现类决定如何处理。
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface RejectedPolicy {

    /**
     * 处理被拒绝的任务。
     *
     * @param task     被拒绝的任务
     * @param executor 当前线程池执行器
     */
    void rejectedExecution(Runnable task, SmartThreadPoolExecutor executor);
}
