package com.metapool.common.stats;

/**
 * 分布式锁统计快照，不可变。由 {@link com.metapool.common.capability.DistributedLock#lockStats()} 返回。
 *
 * <p>各字段为调用时刻的瞬时值，彼此之间不保证原子一致。
 *
 * <p>注意 {@code heldByThisProcess} 只统计<b>本进程</b>持有的锁——分布式锁的全局持有情况需要问后端，
 * 不是进程内控制面能回答的（{@code ResourceManager} 是进程内对象，见 RULES §2.7）。
 *
 * @param heldByThisProcess 本进程当前持有的锁数量
 * @param totalAcquired     累计获取成功次数
 * @param totalTimeout      累计因 {@code waitTime} 耗尽而未获得的次数
 * @param totalLeaseExpired 累计因租约到期被动释放的次数；该值持续偏高说明 {@code leaseTime} 配短了
 * @since 2.1.0
 */
public record LockStats(int heldByThisProcess, long totalAcquired,
                        long totalTimeout, long totalLeaseExpired) {
}
