package com.smartpool.pool.rate.limit;

/**
 * 限流器配置。
 *
 * @since 0.1.0
 */
public class RateLimiterConfig {

    /** 每秒允许的请求数，默认 1000 */
    private double permitsPerSecond = 1000;

    /** 冷启动预热秒数，0 表示不预热，默认 10 */
    private long warmUpSeconds = 10;

    public double getPermitsPerSecond() {
        return permitsPerSecond;
    }

    public void setPermitsPerSecond(double permitsPerSecond) {
        if (permitsPerSecond <= 0) {
            throw new IllegalArgumentException("permitsPerSecond must be > 0");
        }
        this.permitsPerSecond = permitsPerSecond;
    }

    public long getWarmUpSeconds() {
        return warmUpSeconds;
    }

    public void setWarmUpSeconds(long warmUpSeconds) {
        if (warmUpSeconds < 0) {
            throw new IllegalArgumentException("warmUpSeconds must be >= 0");
        }
        this.warmUpSeconds = warmUpSeconds;
    }
}
