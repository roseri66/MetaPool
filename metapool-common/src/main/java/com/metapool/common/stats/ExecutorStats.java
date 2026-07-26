package com.metapool.common.stats;

/**
 * 线程池统计快照，不可变。由 {@link com.metapool.common.capability.ManagedExecutor#executorStats()} 返回。
 *
 * <p>各字段为调用时刻的瞬时值，彼此之间不保证原子一致。
 *
 * @param activeCount            正在执行任务的线程数
 * @param poolSize               当前线程数
 * @param corePoolSize           核心线程数（通常可经 {@link com.metapool.common.resource.Tunable} 热调）
 * @param maximumPoolSize        最大线程数（通常可经 {@link com.metapool.common.resource.Tunable} 热调）
 * @param queueSize              队列中等待执行的任务数
 * @param queueRemainingCapacity 队列剩余容量；<b>无界队列返回 {@link Integer#MAX_VALUE}</b>，
 *                               据此判断饱和度时需先识别这种情况
 * @param completedTaskCount     累计完成的任务数
 * @param rejectedCount          累计被拒绝的任务数（JDK 不提供该计数，由适配器自行统计）
 * @since 2.1.0
 */
public record ExecutorStats(int activeCount, int poolSize, int corePoolSize, int maximumPoolSize,
                            int queueSize, int queueRemainingCapacity,
                            long completedTaskCount, long rejectedCount) {
}
