package com.metapool.core;

import com.metapool.common.manager.ResourceManager;
import com.metapool.common.resource.ManagedResource;
import com.metapool.common.spi.ResourceDefinition;
import com.metapool.common.stats.HealthStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3 集成验收：SPI 发现真实适配器 → 控制面同构纳管「一个池 + 一个非池」→ 全链路。
 * 这是头牌故事的技术证据：<b>一个 MeterRegistry 里，连接池与限流器的指标共存</b>。
 */
class ControlPlaneIntegrationTest {

    @Test
    void datasourceAndRateLimiter_governedUniformly_throughSpi() {
        ResourceAdapterLoader loader = new ResourceAdapterLoader();
        // SPI 应发现两种类型
        assertTrue(loader.supportedTypes().containsAll(Set.of("datasource", "rate-limiter")),
                "ServiceLoader 应发现 hikari + bucket4j 两个工厂，实得: " + loader.supportedTypes());

        ManagedResource ds = loader.create(new ResourceDefinition(
                "main", "datasource",
                Map.of("jdbc-url", "jdbc:h2:mem:mpCore;DB_CLOSE_DELAY=-1", "username", "sa",
                        "maximum-pool-size", 4),
                Set.of("maximum-pool-size")));
        ManagedResource rl = loader.create(new ResourceDefinition(
                "order-api", "rate-limiter",
                Map.of("limit-for-period", 50, "refill-period", "1s"),
                Set.of("limit-for-period")));

        ResourceManager metaPool = MetaPool.create(Duration.ofSeconds(2));
        metaPool.register(ds);
        metaPool.register(rl);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        metaPool.bindMetrics(registry);   // 绑定在 start 之前，验证顺序无关
        metaPool.start();

        // 聚合健康
        assertEquals(HealthStatus.Status.UP, metaPool.health().status());

        // 一个 registry 里两类异构资源的指标共存 —— 头牌证据
        assertNotNull(registry.find("metapool.datasource.connections.active")
                .tag("metapool.resource", "main").gauge());
        assertNotNull(registry.find("metapool.ratelimiter.available.tokens")
                .tag("metapool.resource", "order-api").gauge());

        // 统一调参门面
        assertTrue(metaPool.tune("main", Map.of("maximum-pool-size", 8)).success());
        assertTrue(metaPool.tune("order-api", Map.of("limit-for-period", 100)).success());

        metaPool.close();   // 逆序优雅停机
        assertEquals(HealthStatus.Status.DOWN, metaPool.get("main").health().status());
    }
}
