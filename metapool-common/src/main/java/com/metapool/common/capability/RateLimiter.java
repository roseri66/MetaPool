package com.metapool.common.capability;

import com.metapool.common.stats.RateLimiterStats;

import java.time.Duration;

/**
 * 限流能力 —— 仅限流类资源实现（如 Bucket4j 适配器）。
 *
 * <p>限流<b>不实现</b> {@link Pool}：令牌没有「归还」语义。这是能力隔离原则的直接体现。
 *
 * <h3>线程安全</h3>
 * <p>所有方法必须支持多线程并发调用。
 *
 * @since 2.0.0
 */
public interface RateLimiter {

    /**
     * 尝试获取 {@code permits} 个令牌，立即返回。
     *
     * @param permits 请求令牌数，须为正
     * @return true 放行，false 拒绝
     */
    boolean tryAcquire(int permits);

    /**
     * 尝试获取 {@code permits} 个令牌，最多等待 {@code timeout}。
     *
     * @param permits 请求令牌数，须为正
     * @param timeout 最大等待时间
     * @return true 放行，false 超时仍未获得
     */
    boolean tryAcquire(int permits, Duration timeout) throws InterruptedException;

    /** 当前限流器统计快照。 */
    RateLimiterStats limiterStats();
}
