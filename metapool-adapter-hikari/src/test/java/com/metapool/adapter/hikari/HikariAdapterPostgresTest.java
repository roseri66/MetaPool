package com.metapool.adapter.hikari;

import com.metapool.common.stats.HealthStatus;
import com.zaxxer.hikari.HikariConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用<b>真实 PostgreSQL</b>（Testcontainers）验证 HikariCP 适配器全链路，补齐 {@link HikariAdapterTest}
 * 用 H2 的取巧。
 *
 * <p>{@code disabledWithoutDocker = true}：无 Docker 环境（如某些 CI/本地）自动跳过，不使构建失败。
 * 在装有 Docker 的机器上 {@code mvn -pl metapool-adapter-hikari test} 即会真实拉起 PG 并执行。
 */
@Testcontainers(disabledWithoutDocker = true)
class HikariAdapterPostgresTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void governsRealPostgres_fullChain() throws Exception {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(POSTGRES.getJdbcUrl());
        cfg.setUsername(POSTGRES.getUsername());
        cfg.setPassword(POSTGRES.getPassword());
        cfg.setMaximumPoolSize(4);

        HikariAdapter ds = HikariAdapter.from(cfg).named("pg").build();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ds.bindTo(registry);
        ds.start();

        assertEquals(HealthStatus.Status.UP, ds.health().status());

        Connection c = ds.borrow();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
        ds.release(c);

        // 真实池上的动态调参
        assertTrue(ds.apply(Map.of("maximum-pool-size", 8)).success());
        // 统一 tag 指标
        assertNotNull(registry.find("metapool.datasource.connections.total")
                .tag("metapool.resource", "pg").gauge());

        ds.stop(Duration.ofSeconds(3));
        assertEquals(HealthStatus.Status.DOWN, ds.health().status());
    }
}
