package com.metapool.pool.db;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 连接工厂——将物理连接的创建与池解耦，便于测试 mock。
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface ConnectionFactory {

    /**
     * 创建新的物理 JDBC 连接。
     *
     * @return 新建的 Connection
     * @throws SQLException 创建失败
     */
    Connection create() throws SQLException;
}
