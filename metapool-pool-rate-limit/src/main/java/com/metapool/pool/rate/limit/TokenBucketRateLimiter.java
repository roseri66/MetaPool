package com.metapool.pool.rate.limit;

import com.metapool.common.lifecycle.PoolStats;
import com.metapool.common.lifecycle.ResourceLifecycle;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 令牌桶限流器，实现 {@link ResourceLifecycle} 接口。
 *
 * <h3>算法</h3>
 * <p>以固定速率生成令牌存入桶中（桶容量 = permitsPerSecond，即 1 秒突发）。
 * 每次请求消费 1 个令牌，无令牌时拒绝。
 *
 * <h3>预热</h3>
 * <p>冷启动期间（warmUpSeconds 秒内），令牌生成速率从 0 线性递增至 permitsPerSecond。
 *
 * <h3>动态调参</h3>
 * <p>运行时修改 permitsPerSecond，下一次令牌补充时生效。
 *
 * <h3>线程安全</h3>
 * <p>{@link #tryAcquire()} 使用 synchronized，保证令牌补充和消费的原子性。
 *
 * @since 0.1.0
 */
public class TokenBucketRateLimiter implements ResourceLifecycle<Boolean> {

    private final RateLimiterConfig config;

    /** 当前可用令牌数 */
    private double availableTokens;

    /** 上次补充令牌的时间（纳秒） */
    private long lastRefillTimeNanos;

    /** 初始化时间（纳秒），用于预热计算 */
    private long initTimeNanos;

    /** 运行时可变速率 */
    private volatile double permitsPerSecond;

    /** 预热秒数 */
    private final long warmUpSeconds;

    /** 桶容量 = permitsPerSecond（1 秒突发） */
    private double maxTokens;

    /** 指标 */
    private final AtomicLong passCount = new AtomicLong(0);
    private final AtomicLong rejectCount = new AtomicLong(0);

    /** 运行状态 */
    private volatile boolean running;

    public TokenBucketRateLimiter(RateLimiterConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.config = config;
        this.permitsPerSecond = config.getPermitsPerSecond();
        this.warmUpSeconds = config.getWarmUpSeconds();
        this.maxTokens = config.getPermitsPerSecond();
    }

    // ==================== ResourceLifecycle ====================

    @Override
    public void init() {
        this.availableTokens = warmUpSeconds > 0 ? 0 : maxTokens;
        this.lastRefillTimeNanos = System.nanoTime();
        this.initTimeNanos = System.nanoTime();
        this.maxTokens = permitsPerSecond;
        this.running = true;
    }

    /**
     * 尝试获取 1 个令牌（非阻塞）。
     *
     * @return true 表示获取成功（放行），false 表示被限流
     */
    @Override
    public Boolean acquire() {
        return tryAcquire();
    }

    @Override
    public Boolean acquire(long timeout, TimeUnit unit) {
        return tryAcquire();
    }

    @Override
    public void release(Boolean resource) {
        // 限流器不支持释放令牌
    }

    @Override
    public void destroy() {
        running = false;
    }

    @Override
    public PoolStats stats() {
        return PoolStats.builder()
                .activeCount((int) passCount.get())
                .idleCount(0)
                .pendingCount((int) availableTokens)
                .totalAcquired(passCount.get())
                .totalReleased(rejectCount.get())
                .leakDetected(0)
                .build();
    }

    // ==================== 核心算法 ====================

    /**
     * 尝试获取 1 个令牌。
     *
     * @return true 放行，false 限流
     */
    public synchronized boolean tryAcquire() {
        if (!running) {
            rejectCount.incrementAndGet();
            return false;
        }

        refillTokens();

        if (availableTokens >= 1.0) {
            availableTokens -= 1.0;
            passCount.incrementAndGet();
            return true;
        }

        rejectCount.incrementAndGet();
        return false;
    }

    /**
     * 补充令牌（基于流逝时间）。
     */
    private void refillTokens() {
        long now = System.nanoTime();
        double elapsedSeconds = (now - lastRefillTimeNanos) / 1_000_000_000.0;

        if (elapsedSeconds <= 0) {
            return;
        }

        // 计算有效速率（含预热）
        double effectiveRate = permitsPerSecond;
        if (warmUpSeconds > 0) {
            double elapsedSinceInit = (now - initTimeNanos) / 1_000_000_000.0;
            if (elapsedSinceInit < warmUpSeconds) {
                // 线性递增：0 → permitsPerSecond
                effectiveRate = permitsPerSecond * elapsedSinceInit / warmUpSeconds;
            }
        }

        double newTokens = effectiveRate * elapsedSeconds;
        availableTokens = Math.min(maxTokens, availableTokens + newTokens);
        lastRefillTimeNanos = now;

        // 动态更新桶容量（permitsPerSecond 可能被运行时修改）
        maxTokens = permitsPerSecond;
    }

    // ==================== 动态调参 ====================

    /**
     * 动态修改每秒令牌数，下次补充时生效。
     */
    public void setPermitsPerSecond(double permitsPerSecond) {
        if (permitsPerSecond <= 0) {
            throw new IllegalArgumentException("permitsPerSecond must be > 0");
        }
        this.permitsPerSecond = permitsPerSecond;
    }

    // ==================== 指标访问 ====================

    public long getPassCount() {
        return passCount.get();
    }

    public long getRejectCount() {
        return rejectCount.get();
    }

    public double getAvailableTokens() {
        return availableTokens;
    }

    public boolean isRunning() {
        return running;
    }
}
