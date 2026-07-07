package com.metapool.pool.redis;

import com.metapool.common.pool.PoolConfig;

/**
 * Redis 连接池配置，继承 {@link PoolConfig} 基类。
 *
 * @since 0.1.0
 */
public class RedisPoolConfig extends PoolConfig {

    /** 连接存活最大分钟数，默认 30 */
    private long maxLifetimeMinutes = 30;

    /** 获取连接超时秒数，默认 10 */
    private long connectionTimeoutSeconds = 10;

    public long getMaxLifetimeMinutes() {
        return maxLifetimeMinutes;
    }

    public void setMaxLifetimeMinutes(long maxLifetimeMinutes) {
        if (maxLifetimeMinutes <= 0) {
            throw new IllegalArgumentException("maxLifetimeMinutes must be > 0");
        }
        this.maxLifetimeMinutes = maxLifetimeMinutes;
    }

    public long getConnectionTimeoutSeconds() {
        return connectionTimeoutSeconds;
    }

    public void setConnectionTimeoutSeconds(long connectionTimeoutSeconds) {
        if (connectionTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("connectionTimeoutSeconds must be > 0");
        }
        this.connectionTimeoutSeconds = connectionTimeoutSeconds;
    }
}
