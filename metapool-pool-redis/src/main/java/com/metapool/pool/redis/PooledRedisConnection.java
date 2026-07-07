package com.metapool.pool.redis;

/**
 * Redis 连接包装，关联物理连接及其元数据。
 *
 * @since 0.1.0
 */
public class PooledRedisConnection {

    private final Object connection;
    private final long createdAt;

    public PooledRedisConnection(Object connection) {
        this.connection = connection;
        this.createdAt = System.currentTimeMillis();
    }

    /** 获取底层连接对象。 */
    public Object getConnection() {
        return connection;
    }

    /** 连接创建时间戳（毫秒）。 */
    public long getCreatedAt() {
        return createdAt;
    }

    /** 连接已存活毫秒数。 */
    public long getAgeMs() {
        return System.currentTimeMillis() - createdAt;
    }

    @Override
    public String toString() {
        return "PooledRedisConnection{createdAt=" + createdAt + "}";
    }
}
