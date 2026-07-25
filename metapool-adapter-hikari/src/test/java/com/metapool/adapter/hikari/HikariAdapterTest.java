package com.metapool.adapter.hikari;

import com.metapool.common.stats.HealthStatus;
import com.metapool.common.stats.PoolStats;
import com.metapool.common.stats.TuneResult;
import com.zaxxer.hikari.HikariConfig;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2 验收：HikariCP 适配器打通「注册→启动→取连接→指标→调参→优雅停机」全链路。
 * 用 H2 内存库，无需 Docker。
 */
class HikariAdapterTest {

    private static final AtomicInteger DB_SEQ = new AtomicInteger();

    private HikariConfig h2Config() {
        HikariConfig cfg = new HikariConfig();
        // 每个测试独立库，避免相互影响
        cfg.setJdbcUrl("jdbc:h2:mem:mp" + DB_SEQ.incrementAndGet() + ";DB_CLOSE_DELAY=-1");
        cfg.setUsername("sa");
        cfg.setPassword("");
        cfg.setMaximumPoolSize(4);
        return cfg;
    }

    @Test
    void lifecycle_borrow_release_and_stats() throws Exception {
        HikariAdapter ds = HikariAdapter.from(h2Config()).named("main").build();
        assertEquals("datasource", ds.type());
        assertEquals(HealthStatus.Status.DOWN, ds.health().status()); // 未启动

        ds.start();
        assertEquals(HealthStatus.Status.UP, ds.health().status());

        Connection c = ds.borrow();
        assertNotNull(c);
        try (Statement st = c.createStatement()) {
            assertTrue(st.execute("SELECT 1"));
        }
        PoolStats active = ds.poolStats();
        assertEquals(1, active.active());

        ds.release(c);
        assertEquals(1, ds.poolStats().totalReleased());
        assertEquals(0, ds.poolStats().active());

        ds.stop(Duration.ofSeconds(2));
        assertEquals(HealthStatus.Status.DOWN, ds.health().status());
    }

    @Test
    void restart_afterStop_yieldsUsablePool() throws Exception {
        HikariAdapter ds = HikariAdapter.from(h2Config()).named("main").build();
        ds.start();
        ds.release(ds.borrow());
        ds.stop(Duration.ofSeconds(2));
        assertEquals(HealthStatus.Status.DOWN, ds.health().status());

        ds.start();   // 重启：修复前会因 dataSource!=null 静默跳过，留下已关闭的池
        assertEquals(HealthStatus.Status.UP, ds.health().status());
        Connection c = ds.borrow();   // 必须可用
        assertNotNull(c);
        ds.release(c);
        ds.stop(Duration.ZERO);
    }

    @Test
    void metrics_boundBeforeStart_areOrderIndependent() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HikariAdapter ds = HikariAdapter.from(h2Config()).named("orders").build();

        ds.bindTo(registry);   // 绑定在 start 之前
        ds.start();

        Gauge active = registry.find("metapool.datasource.connections.active")
                .tag("metapool.resource", "orders")
                .tag("metapool.type", "datasource")
                .gauge();
        assertNotNull(active, "统一 tag 的 active 指标应已注册");

        Connection c = ds.borrow();
        assertEquals(1.0, registry.get("metapool.datasource.connections.active")
                .tag("metapool.resource", "orders").gauge().value());
        ds.release(c);
        ds.stop(Duration.ZERO);
    }

    @Test
    void tunable_whitelist_appliesAndRejects() {
        HikariAdapter ds = HikariAdapter.from(h2Config()).named("main").build();
        ds.start();
        assertEquals(Set.of("maximum-pool-size", "connection-timeout"), ds.tunableKeys());

        TuneResult ok = ds.apply(Map.of("maximum-pool-size", 8));
        assertTrue(ok.success());
        assertTrue(ok.applied().contains("maximum-pool-size"));

        TuneResult rejected = ds.apply(Map.of("jdbc-url", "x"));
        assertFalse(rejected.success());
        assertTrue(rejected.rejected().containsKey("jdbc-url"));

        TuneResult badValue = ds.apply(Map.of("maximum-pool-size", -1));
        assertFalse(badValue.success());

        ds.stop(Duration.ZERO);
    }

    @Test
    void factory_viaResourceDefinition_buildsWorkingAdapter() throws Exception {
        HikariAdapterFactory factory = new HikariAdapterFactory();
        assertEquals("datasource", factory.supportedType());

        var def = new com.metapool.common.spi.ResourceDefinition(
                "reporting", "datasource",
                Map.of("jdbc-url", "jdbc:h2:mem:mpFactory;DB_CLOSE_DELAY=-1",
                        "username", "sa",
                        "maximum-pool-size", 3),
                Set.of("maximum-pool-size"));

        var resource = factory.create(def);
        assertEquals("reporting", resource.name());
        resource.start();
        assertEquals(HealthStatus.Status.UP, resource.health().status());
        resource.stop(Duration.ZERO);
    }

    @Test
    void kebabToCamel_mapping() {
        assertEquals("maximumPoolSize", HikariAdapterFactory.kebabToCamel("maximum-pool-size"));
        assertEquals("jdbcUrl", HikariAdapterFactory.kebabToCamel("jdbc-url"));
        assertEquals("username", HikariAdapterFactory.kebabToCamel("username"));
    }
}
