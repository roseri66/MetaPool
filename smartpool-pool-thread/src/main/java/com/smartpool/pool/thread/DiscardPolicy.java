package com.smartpool.pool.thread;

/**
 * 静默丢弃策略——被拒绝的任务直接丢弃，无任何通知。
 *
 * @since 0.1.0
 */
public class DiscardPolicy implements RejectedPolicy {

    @Override
    public void rejectedExecution(Runnable task, SmartThreadPoolExecutor executor) {
        // 静默丢弃
    }
}
