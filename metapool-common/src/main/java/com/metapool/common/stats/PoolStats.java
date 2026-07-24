package com.metapool.common.stats;

/**
 * 池类资源统计快照，不可变。由 {@link com.metapool.common.capability.Pool#poolStats()} 返回。
 *
 * <p>各字段为调用时刻的瞬时值，彼此之间不保证原子一致。
 *
 * @param active         已借出未归还的资源数
 * @param idle           空闲可借的资源数
 * @param pending        正在等待借用的线程数
 * @param totalBorrowed  累计借出次数
 * @param totalReleased  累计归还次数
 * @since 2.0.0
 */
public record PoolStats(int active, int idle, int pending, long totalBorrowed, long totalReleased) {
}
