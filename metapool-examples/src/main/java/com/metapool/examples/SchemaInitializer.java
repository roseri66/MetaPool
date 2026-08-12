package com.metapool.examples;

import com.metapool.common.capability.Pool;
import com.metapool.common.manager.ResourceManager;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.Statement;

/**
 * 启动时经被治理的连接池建表（demo 用）。演示：业务侧也能直接借用 MetaPool 治理下的连接。
 */
@Component
class SchemaInitializer implements CommandLineRunner {

    private final ResourceManager metaPool;

    SchemaInitializer(ResourceManager metaPool) {
        this.metaPool = metaPool;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void run(String... args) throws Exception {
        Pool<Connection> ds = (Pool<Connection>) metaPool.get("main");
        Connection conn = ds.borrow();
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS orders (id VARCHAR(64) PRIMARY KEY)");
            st.execute("CREATE TABLE IF NOT EXISTS order_audit ("
                    + "seq BIGINT AUTO_INCREMENT PRIMARY KEY, order_id VARCHAR(64), worker VARCHAR(64))");
        } finally {
            ds.release(conn);
        }
    }
}
