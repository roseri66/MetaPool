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
                    "metapool.rate-limiters.order-api.tunable=limit-for-period");

    @Test
    void autoConfigures_registersBothResources_andBindsUnifiedMetrics() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(ResourceManager.class);
            ResourceManager mgr = ctx.getBean(ResourceManager.class);

            assertThat(mgr.resources()).hasSize(2);
            assertThat(mgr.find("main")).isPresent();
            assertThat(mgr.find("order-api")).isPresent();
            assertThat(mgr.health().status()).isEqualTo(HealthStatus.Status.UP);

            // 头牌证据：一个 MeterRegistry 同时持有连接池与限流器的统一 tag 指标
            MeterRegistry registry = ctx.getBean(MeterRegistry.class);
            assertThat(registry.find("metapool.datasource.connections.active")
                    .tag("metapool.resource", "main").gauge()).isNotNull();
            assertThat(registry.find("metapool.ratelimiter.available.tokens")
                    .tag("metapool.resource", "order-api").gauge()).isNotNull();

            // 统一动态调参
            assertThat(mgr.tune("main", java.util.Map.of("maximum-pool-size", "8")).success()).isTrue();
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
