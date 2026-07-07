package com.metapool.pool.redis;

/**
 * Redis 连接验证器——通过 {@code PING} 命令探测连接是否存活。
 *
 * <p>不同客户端实现 PING 的方式不同：
 * <ul>
 *   <li>Lettuce: {@code connection.sync().ping()}</li>
 *   <li>Jedis: {@code jedis.ping()}</li>
 * </ul>
 * 业务方实现本接口适配各自客户端。
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface RedisConnectionValidator {

    /**
     * 验证连接是否有效。
     *
     * @param connection 连接对象（由 {@link RedisConnectionFactory} 创建）
     * @param timeoutSeconds 验证超时秒数
     * @return true 表示连接有效
     */
    boolean validate(Object connection, int timeoutSeconds);
}
