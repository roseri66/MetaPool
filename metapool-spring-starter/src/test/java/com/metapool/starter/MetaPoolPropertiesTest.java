package com.metapool.starter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-16 MetaPoolProperties 配置绑定测试。
 *
 * @since 0.1.0
 */
@DisplayName("SPEC-16 MetaPoolProperties 配置绑定测试")
class MetaPoolPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesTestConfig.class);

    @EnableConfigurationProperties(MetaPoolProperties.class)
    static class PropertiesTestConfig {
    }

    @Nested
    @DisplayName("默认值")
    class DefaultValues {

        @Test
        @DisplayName("无配置时应使用默认值")
        void shouldUseDefaultsWhenNoConfig() {
            contextRunner.run(ctx -> {
                MetaPoolProperties props = ctx.getBean(MetaPoolProperties.class);
                assertThat(props.isEnabled()).isTrue();
                assertThat(props.getThread().getCorePoolSize()).isEqualTo(10);
                assertThat(props.getThread().getMaxPoolSize()).isEqualTo(50);
                assertThat(props.getThread().getRejectedPolicy()).isEqualTo("CALLER_RUNS");
                assertThat(props.getDb().getMinIdle()).isEqualTo(5);
                assertThat(props.getDb().getMaxPoolSize()).isEqualTo(20);
                assertThat(props.getRateLimit().getPermitsPerSecond()).isEqualTo(1000);
                assertThat(props.getLock().getDefaultTtlSeconds()).isEqualTo(30);
            });
        }
    }

    @Nested
    @DisplayName("自定义配置")
    class CustomConfig {

        @Test
        @DisplayName("应正确绑定自定义线程池参数")
        void shouldBindCustomThreadPoolConfig() {
            contextRunner
                    .withPropertyValues(
                            "metapool.thread.core-pool-size=20",
                            "metapool.thread.max-pool-size=100",
                            "metapool.thread.keep-alive-seconds=120",
                            "metapool.thread.queue-capacity=2000",
                            "metapool.thread.rejected-policy=ABORT",
                            "metapool.thread.thread-name-prefix=custom-worker-"
                    )
                    .run(ctx -> {
                        MetaPoolProperties props = ctx.getBean(MetaPoolProperties.class);
                        assertThat(props.getThread().getCorePoolSize()).isEqualTo(20);
                        assertThat(props.getThread().getMaxPoolSize()).isEqualTo(100);
                        assertThat(props.getThread().getKeepAliveSeconds()).isEqualTo(120);
                        assertThat(props.getThread().getQueueCapacity()).isEqualTo(2000);
                        assertThat(props.getThread().getRejectedPolicy()).isEqualTo("ABORT");
                        assertThat(props.getThread().getThreadNamePrefix()).isEqualTo("custom-worker-");
                    });
        }

        @Test
        @DisplayName("应正确绑定自定义 DB 连接池参数")
        void shouldBindCustomDbPoolConfig() {
            contextRunner
                    .withPropertyValues(
                            "metapool.db.min-idle=10",
                            "metapool.db.max-pool-size=50",
                            "metapool.db.max-lifetime-minutes=60",
                            "metapool.db.connection-timeout-seconds=15"
                    )
                    .run(ctx -> {
                        MetaPoolProperties props = ctx.getBean(MetaPoolProperties.class);
                        assertThat(props.getDb().getMinIdle()).isEqualTo(10);
                        assertThat(props.getDb().getMaxPoolSize()).isEqualTo(50);
                        assertThat(props.getDb().getMaxLifetimeMinutes()).isEqualTo(60);
                        assertThat(props.getDb().getConnectionTimeoutSeconds()).isEqualTo(15);
                    });
        }

        @Test
        @DisplayName("应正确绑定限流器参数")
        void shouldBindRateLimitConfig() {
            contextRunner
                    .withPropertyValues(
                            "metapool.rate-limit.permits-per-second=5000",
                            "metapool.rate-limit.warm-up-seconds=30"
                    )
                    .run(ctx -> {
                        MetaPoolProperties props = ctx.getBean(MetaPoolProperties.class);
                        assertThat(props.getRateLimit().getPermitsPerSecond()).isEqualTo(5000);
                        assertThat(props.getRateLimit().getWarmUpSeconds()).isEqualTo(30);
                    });
        }

        @Test
        @DisplayName("应正确绑定分布式锁参数")
        void shouldBindLockConfig() {
            contextRunner
                    .withPropertyValues(
                            "metapool.lock.default-ttl-seconds=60",
                            "metapool.lock.renewal-interval-seconds=20",
                            "metapool.lock.max-retry-count=5",
                            "metapool.lock.retry-interval-millis=200"
                    )
                    .run(ctx -> {
                        MetaPoolProperties props = ctx.getBean(MetaPoolProperties.class);
                        assertThat(props.getLock().getDefaultTtlSeconds()).isEqualTo(60);
                        assertThat(props.getLock().getRenewalIntervalSeconds()).isEqualTo(20);
                        assertThat(props.getLock().getMaxRetryCount()).isEqualTo(5);
                        assertThat(props.getLock().getRetryIntervalMillis()).isEqualTo(200);
                    });
        }
    }

    @Nested
    @DisplayName("总开关")
    class GlobalToggle {

        @Test
        @DisplayName("设为 false 时应禁用总开关")
        void shouldDisableWhenSetToFalse() {
            contextRunner
                    .withPropertyValues("metapool.enabled=false")
                    .run(ctx -> {
                        MetaPoolProperties props = ctx.getBean(MetaPoolProperties.class);
                        assertThat(props.isEnabled()).isFalse();
                    });
        }
    }
}
