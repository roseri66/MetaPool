package com.metapool.starter;

import com.metapool.common.manager.ResourceManager;
import com.metapool.common.stats.HealthStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3-B2 验收：YAML 声明式接入 → 控制面自动装配 → SPI 发现 hikari+bucket4j → 全链路。
 */
class MetaPoolAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MetaPoolAutoConfiguration.class))
            .withUserConfiguration(MeterRegistryConfig.class)
            .withPropertyValues(
                    "metapool.datasources.main.jdbc-url=jdbc:h2:mem:starterMain;DB_CLOSE_DELAY=-1",
                    "metapool.datasources.main.username=sa",
                    "metapool.datasources.main.maximum-pool-size=4",
                    "metapool.datasources.main.tunable=maximum-pool-size,connection-timeout",
                    "metapool.rate-limiters.order-api.limit-for-period=100",
                    "metapool.rate-limiters.order-api.refill-period=1s",
                    "metapool.rate-limiters.order-api.tunable=limit-for-period",
                    "metapool.executors.order-worker.core-pool-size=2",
                    "metapool.executors.order-worker.maximum-pool-size=4",
                    "metapool.executors.order-worker.queue-capacity=50",
                    "metapool.executors.order-worker.tunable=core-pool-size,maximum-pool-size");

    @Test
    void autoConfigures_registersAllThreeResources_andBindsUnifiedMetrics() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(ResourceManager.class);
            ResourceManager mgr = ctx.getBean(ResourceManager.class);

            assertThat(mgr.resources()).hasSize(3);
            assertThat(mgr.find("main")).isPresent();
            assertThat(mgr.find("order-api")).isPresent();
            assertThat(mgr.find("order-worker")).isPresent();
            assertThat(mgr.health().status()).isEqualTo(HealthStatus.Status.UP);

            // 头牌证据：一个 MeterRegistry 同时持有连接池 + 限流器 + 线程池的统一 tag 指标。
            // 三类底层库毫不相干（HikariCP / Bucket4j / JDK），指标却能在同一块看板上并列。
            MeterRegistry registry = ctx.getBean(MeterRegistry.class);
            assertThat(registry.find("metapool.datasource.connections.active")
                    .tag("metapool.resource", "main").gauge()).isNotNull();
            assertThat(registry.find("metapool.ratelimiter.available.tokens")
                    .tag("metapool.resource", "order-api").gauge()).isNotNull();
            assertThat(registry.find("metapool.executor.active")
                    .tag("metapool.resource", "order-worker")
                    .tag("metapool.type", "executor").gauge()).isNotNull();

            // 统一动态调参：同一个 tune 入口，路由到两种毫不相干的底层库
            assertThat(mgr.tune("main", java.util.Map.of("maximum-pool-size", "8")).success()).isTrue();
            assertThat(mgr.tune("order-worker",
                    java.util.Map.of("core-pool-size", "3", "maximum-pool-size", "6")).success()).isTrue();
        });
    }

    /**
     * 新类型经 SPI 自动发现即可纳管 —— 线程池走的是和另外两类完全相同的
     * 「YAML → ResourceDefinition → ServiceLoader 找 factory → 注册进控制面」路径，
     * 控制面对 executor 这个类型没有任何专门代码。
     */
    @Test
    void executor_isUsableThroughCapabilityInterface() {
        runner.run(ctx -> {
            ResourceManager mgr = ctx.getBean(ResourceManager.class);
            var executor = (com.metapool.common.capability.ManagedExecutor) mgr.get("order-worker");
            assertThat(executor.submit(() -> 6 * 7).get(5, java.util.concurrent.TimeUnit.SECONDS))
                    .isEqualTo(42);
            assertThat(executor.executorStats().corePoolSize()).isEqualTo(2);
        });
    }

    @Test
    void disabled_flag_skipsAutoConfiguration() {
        runner.withPropertyValues("metapool.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(ResourceManager.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class MeterRegistryConfig {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
