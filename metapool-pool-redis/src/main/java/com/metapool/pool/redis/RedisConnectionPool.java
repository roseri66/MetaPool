package com.metapool.pool.redis;

import com.metapool.common.exception.PoolExhaustedException;
import com.metapool.common.pool.AbstractResourcePool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Redis 连接池，继承 {@link AbstractResourcePool}，提供 Redis 连接的全生命周期管理。
 *
 * <h3>核心能力</h3>
 * <ul>
 *   <li>连接生命周期：创建 → 借出 → 归还 → 空闲回收 → 销毁</li>
 *   <li>借出前 {@code PING} 有效性验证（通过 {@link RedisConnectionValidator}）</li>
 *   <li>连接泄露检测（借出超时触发告警）</li>
 *   <li>慢连接剔除（存活超过 maxLifetimeMinutes 强制回收）</li>
 *   <li>连接耗尽快速失败（抛 PoolExhaustedException）</li>
 *   <li>Redis 密码认证（由 {@link RedisConnectionFactory} 实现方处理）</li>
 * </ul>
 *
 * <h3>使用示例（Lettuce）</h3>
 * <pre>{@code
 * RedisClient client = RedisClient.create("redis://localhost:6379");
 * RedisConnectionPool pool = new RedisConnectionPool(
 *     new RedisPoolConfig(),
 *     () -> client.connect(),
 *     (conn, timeout) -> {
 *         try {
 *             return ((StatefulRedisConnection<?,?>) conn).sync().ping().equals("PONG");
 *         } catch (Exception e) { return false; }
 *     });
 * pool.init();
 * }</pre>
 *
 * @since 0.1.0
 */
public class RedisConnectionPool extends AbstractResourcePool<PooledRedisConnection> {

    private final RedisPoolConfig redisConfig;
    private final RedisConnectionFactory connectionFactory;
    private final RedisConnectionValidator validator;

    /**
     * @param config            连接池配置
     * @param connectionFactory 物理连接工厂
     * @param validator         连接有效性验证器（PING）
     */
    public RedisConnectionPool(RedisPoolConfig config,
                                RedisConnectionFactory connectionFactory,
                                RedisConnectionValidator validator) {
        super(config);
        this.redisConfig = config;
        this.connectionFactory = connectionFactory;
        this.validator = validator;
    }

    /**
     * 获取连接（使用配置中的 connectionTimeoutSeconds）。
     */
    @Override
    public PooledRedisConnection acquire() throws InterruptedException {
        try {
            return acquire(redisConfig.getConnectionTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (PoolExhaustedException e) {
            throw e;
        }
    }

    // ==================== 模板方法钩子 ====================

    @Override
    protected PooledRedisConnection createResource() throws Exception {
        Object conn = connectionFactory.create();
        return new PooledRedisConnection(conn);
    }

    @Override
    protected void destroyResource(PooledRedisConnection resource) {
        // 尝试关闭连接（反射调用 close() 或由工厂实现方在工厂层面关闭）
        try {
            Object conn = resource.getConnection();
            if (conn instanceof AutoCloseable) {
                ((AutoCloseable) conn).close();
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    protected boolean validateResource(PooledRedisConnection resource) {
        if (validator == null) {
            return true;
        }
        try {
            return validator.validate(resource.getConnection(), 5);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    protected void onResourceLeaked(PooledRedisConnection resource) {
        // 指标：由 Agent 采集 metapool_redis_connection_leak_total
    }

    // ==================== 生命周期 ====================

    @Override
    public void init() {
        super.init();

        // 追加最大存活时间扫描
        long maxLifetimeMs = redisConfig.getMaxLifetimeMinutes() * 60 * 1000;
        long scanIntervalSeconds = Math.max(1, redisConfig.getMaxLifetimeMinutes() * 60 / 2);

        scheduler.scheduleWithFixedDelay(
                this::evictExpiredByMaxLifetime,
                scanIntervalSeconds,
                scanIntervalSeconds,
                TimeUnit.SECONDS);
    }

    // ==================== 最大存活时间回收 ====================

    private void evictExpiredByMaxLifetime() {
        long maxLifetimeMs = redisConfig.getMaxLifetimeMinutes() * 60 * 1000;
        long now = System.currentTimeMillis();
        List<PooledRedisConnection> expired = new ArrayList<>();

        lock.lock();
        try {
            for (PooledRedisConnection pc : idleMap.keySet()) {
                if (now - pc.getCreatedAt() >= maxLifetimeMs) {
                    expired.add(pc);
                }
            }
            for (PooledRedisConnection pc : expired) {
                idleMap.remove(pc);
            }
        } finally {
            lock.unlock();
        }

        for (PooledRedisConnection pc : expired) {
            try {
                destroyResource(pc);
            } catch (Exception ignored) {
            }
        }
    }
}
