package com.smartpool.pool.db;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 数据库连接包装，关联物理 JDBC {@link Connection} 及其元数据。
 *
 * @since 0.1.0
 */
public class PooledConnection {

    private final Connection connection;
    private final long createdAt;

    public PooledConnection(Connection connection) {
        this.connection = connection;
        this.createdAt = System.currentTimeMillis();
    }

    /** 获取底层 JDBC 连接。 */
    public Connection getConnection() {
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

    /** 关闭物理连接，忽略异常。 */
    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException ignored) {
        }
    }

    @Override
    public String toString() {
        return "PooledConnection{createdAt=" + createdAt + "}";
    }
}
