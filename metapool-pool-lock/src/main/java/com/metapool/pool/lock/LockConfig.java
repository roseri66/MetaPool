package com.metapool.pool.lock;

/**
 * 分布式锁配置。
 *
 * <h3>配置参数</h3>
 * <ul>
 *   <li>{@code defaultTtlSeconds} — 锁默认 TTL（秒），防止死锁，默认 30</li>
 *   <li>{@code renewalIntervalSeconds} — 自动续期间隔（秒），应小于 TTL 的一半，默认 10</li>
 *   <li>{@code maxRetryCount} — 获取锁失败最大重试次数，默认 3</li>
 *   <li>{@code retryIntervalMillis} — 重试间隔（毫秒），默认 100</li>
 * </ul>
 *
 * @since 0.1.0
 */
public class LockConfig {

    /** 锁默认 TTL 秒数，过期自动释放，默认 30 */
    private long defaultTtlSeconds = 30;

    /** 自动续期间隔秒数，默认 10 */
    private long renewalIntervalSeconds = 10;

    /** 获取锁最大重试次数，默认 3 */
    private int maxRetryCount = 3;

    /** 重试间隔毫秒数，默认 100 */
    private long retryIntervalMillis = 100;

    // ==================== Getters ====================

    public long getDefaultTtlSeconds() {
        return defaultTtlSeconds;
    }

    public long getRenewalIntervalSeconds() {
        return renewalIntervalSeconds;
    }

    public int getMaxRetryCount() {
        return maxRetryCount;
    }

    public long getRetryIntervalMillis() {
        return retryIntervalMillis;
    }

    // ==================== Setters (with validation) ====================

    public void setDefaultTtlSeconds(long defaultTtlSeconds) {
        if (defaultTtlSeconds <= 0) {
            throw new IllegalArgumentException("defaultTtlSeconds must be > 0");
        }
        this.defaultTtlSeconds = defaultTtlSeconds;
    }

    public void setRenewalIntervalSeconds(long renewalIntervalSeconds) {
        if (renewalIntervalSeconds <= 0) {
            throw new IllegalArgumentException("renewalIntervalSeconds must be > 0");
        }
        this.renewalIntervalSeconds = renewalIntervalSeconds;
    }

    public void setMaxRetryCount(int maxRetryCount) {
        if (maxRetryCount < 0) {
            throw new IllegalArgumentException("maxRetryCount must be >= 0");
        }
        this.maxRetryCount = maxRetryCount;
    }

    public void setRetryIntervalMillis(long retryIntervalMillis) {
        if (retryIntervalMillis < 0) {
            throw new IllegalArgumentException("retryIntervalMillis must be >= 0");
        }
        this.retryIntervalMillis = retryIntervalMillis;
    }
}
