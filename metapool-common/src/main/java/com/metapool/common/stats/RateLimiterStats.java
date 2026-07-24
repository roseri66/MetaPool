package com.metapool.common.stats;

/**
 * 限流器统计快照，不可变。由 {@link com.metapool.common.capability.RateLimiter#limiterStats()} 返回。
 *
 * @param availablePermits 当前可用令牌数
 * @param totalAcquired    累计放行次数
 * @param totalRejected    累计拒绝次数
 * @since 2.0.0
 */
public record RateLimiterStats(long availablePermits, long totalAcquired, long totalRejected) {
}
