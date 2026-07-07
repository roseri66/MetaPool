package com.metapool.pool.redis;

/**
 * Redis 连接工厂——将物理连接创建与池解耦，便于测试 mock。
 *
 * <p>生产环境可对接 Lettuce {@code RedisClient} 或 Jedis {@code JedisPool}。
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface RedisConnectionFactory {

    /**
     * 创建新的 Redis 物理连接。
     *
     * @return 新建的连接对象
     * @throws Exception 创建失败
     */
    Object create() throws Exception;
}
