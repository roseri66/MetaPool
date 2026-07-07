package com.metapool.pool.thread;

/**
 * 抛异常拒绝策略——直接抛出运行时异常。
 *
 * @since 0.1.0
 */
public class AbortPolicy implements RejectedPolicy {

    @Override
    public void rejectedExecution(Runnable task, SmartThreadPoolExecutor executor) {
        throw new RejectedExecutionException(
                "Task " + task + " rejected from " + executor);
    }
}
