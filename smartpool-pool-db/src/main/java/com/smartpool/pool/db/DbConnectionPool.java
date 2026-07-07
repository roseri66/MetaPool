package com.smartpool.pool.db;

import com.smartpool.common.exception.PoolExhaustedException;
import com.smartpool.common.pool.AbstractResourcePool;
import com.smartpool.common.pool.PoolConfig;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 数据库连接池，继承 {@link AbstractResourcePool}，提供 JDBC 连接的全生命周期管理。
 *
 * <h3>核心能力</h3>
 * <ul>
 *   <li>连接生命周期：创建 → 借出 → 归还 → 空闲回收 → 销毁</li>
 *   <li>借出前 {@code SELECT 1} 有效性验证</li>
 *   <li>连接泄露检测（借出超时触发告警）</li>
 *   <li>慢连接剔除（存活超过 maxLifetimeMinutes 强制回收）</li>
 *   <li>连接耗尽快速失败（抛 PoolExhaustedException）</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * DbPoolConfig config = new DbPoolConfig();
 * config.setMaxPoolSize(20);
 * config.setMinIdle(5);
 *
 * DbConnectionPool pool = new DbConnectionPool(config,
 *     () -> DriverManager.getConnection("jdbc:postgresql://...", "user", "pass"));
 * pool.init();
 *
 * PooledConnection pc = pool.acquire();
 * Connection conn = pc.getConnection();
 * // ... use conn ...
 * pool.release(pc);
 * }</pre>
 *
 * @since 0.1.0
 */
public class DbConnectionPool extends AbstractResourcePool<PooledConnection> {

    private final DbPoolConfig dbConfig;
    private final ConnectionFactory connectionFactory;

    /**
     * @param config            数据库连接池配置
     * @param connectionFactory 物理连接工厂
     */
    public DbConnectionPool(DbPoolConfig config, ConnectionFactory connectionFactory) {
        super(config);
        this.dbConfig = config;
        this.connectionFactory = connectionFactory;
    }

    /**
     * 获取连接（使用配置中的 connectionTimeoutSeconds）。
     */
    @Override
    public PooledConnection acquire() throws InterruptedException {
        try {
            return acquire(dbConfig.getConnectionTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (PoolExhaustedException e) {
            throw e;
        }
    }

    // ==================== 模板方法钩子 ====================

    /**
     * 创建物理 JDBC 连接并包装为 PooledConnection。
     */
    @Override
    protected PooledConnection createResource() throws Exception {
        Connection conn = connectionFactory.create();
        return new PooledConnection(conn);
    }

    /**
     * 关闭物理连接。
     */
    @Override
    protected void destroyResource(PooledConnection resource) {
        resource.close();
    }

    /**
     * {@code SELECT 1} 验证连接是否存活。
     */
    @Override
    protected boolean validateResource(PooledConnection resource) {
        Connection conn = resource.getConnection();
        try {
            if (conn == null || conn.isClosed()) {
                return false;
            }
            try (Statement stmt = conn.createStatement()) {
                stmt.setQueryTimeout(dbConfig.getValidationTimeoutSeconds());
                try (ResultSet rs = stmt.executeQuery("SELECT 1")) {
                    return rs.next();
                }
            }
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * 检测到泄露时记录回调。
     */
    @Override
    protected void onResourceLeaked(PooledConnection resource) {
        // 指标：由 Agent 采集 smartpool_db_connection_leak_total
    }

    // ==================== 生命周期 ====================

    /**
     * 初始化连接池，预创建最小空闲连接，启动后台扫描（含最大存活时间回收）。
     */
    @Override
    public void init() {
        super.init();

        // 追加最大存活时间扫描任务
        long maxLifetimeMs = dbConfig.getMaxLifetimeMinutes() * 60 * 1000;
        long scanIntervalSeconds = Math.max(1, dbConfig.getMaxLifetimeMinutes() * 60 / 2);

        scheduler.scheduleWithFixedDelay(
                this::evictExpiredByMaxLifetime,
                scanIntervalSeconds,
                scanIntervalSeconds,
                TimeUnit.SECONDS);
    }

    // ==================== 最大存活时间回收 ====================

    /**
     * 扫描并回收存活超过 maxLifetimeMinutes 的空闲连接。
     *
     * <p>与基类 idle eviction 互补：idle eviction 回收"空闲太久"的连接，
     * 此方法回收"存活太久"的连接（即使刚归还不久）。
     */
    private void evictExpiredByMaxLifetime() {
        long maxLifetimeMs = dbConfig.getMaxLifetimeMinutes() * 60 * 1000;
        long now = System.currentTimeMillis();
        List<PooledConnection> expired = new ArrayList<>();

        lock.lock();
        try {
            for (PooledConnection pc : idleMap.keySet()) {
                if (now - pc.getCreatedAt() >= maxLifetimeMs) {
                    expired.add(pc);
                }
            }
            for (PooledConnection pc : expired) {
                idleMap.remove(pc);
            }
        } finally {
            lock.unlock();
        }

        for (PooledConnection pc : expired) {
            destroyResourceSafely(pc);
        }
    }

    // 基类的 destroyResourceSafely 是 private，在此重新定义
    private void destroyResourceSafely(PooledConnection pc) {
        try {
            destroyResource(pc);
        } catch (Exception ignored) {
        }
    }

    // ==================== 便捷构造 ====================

    /**
     * 便捷构造——通过 JDBC URL 直接创建。
     */
    public static DbConnectionPool create(DbPoolConfig config,
                                           String jdbcUrl, String username, String password) {
        return new DbConnectionPool(config, () -> {
            try {
                return java.sql.DriverManager.getConnection(jdbcUrl, username, password);
            } catch (SQLException e) {
                throw new RuntimeException("Failed to create connection", e);
            }
        });
    }
}
