package com.smartpool.pool.thread;

/**
 * 由调用者线程直接执行被拒绝的任务。
 *
 * <p>此策略不会丢弃任务也不会抛异常，但会降低任务提交速率。
 *
 * @since 0.1.0
 */
public class CallerRunsPolicy implements RejectedPolicy {

    @Override
    public void rejectedExecution(Runnable task, SmartThreadPoolExecutor executor) {
        if (!executor.isShutdown()) {
            task.run();
        }
    }
}
