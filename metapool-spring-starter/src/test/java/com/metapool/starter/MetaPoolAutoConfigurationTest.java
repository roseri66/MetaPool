package com.metapool.starter;

import com.metapool.pool.memory.MemoryPool;
import com.metapool.pool.memory.MemoryPoolConfig;
import com.metapool.pool.rate.limit.RateLimiterConfig;
import com.metapool.pool.rate.limit.TokenBucketRateLimiter;
import com.metapool.pool.thread.ThreadPoolConfig;
import com.metapool.pool.thread.ThreadResourcePool;
import com.metapool.starter.config.MemoryPoolAutoConfiguration;
import com.metapool.starter.config.RateLimitAutoConfiguration;
import com.metapool.starter.config.ThreadPoolAutoConfiguration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-16 自动装配条件测试。
 *
 * @since 0.1.0
 */
@DisplayName("SPEC-16 自动装配条件测试")
class MetaPoolAutoConfigurationTest {

    // ==================== 线程池自动装配 ====================

    @Nested
    @DisplayName("线程池自动装配")
    class ThreadPoolAutoConfig {

        private final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(
                        MetaPoolAutoConfiguration.class,
                        ThreadPoolAutoConfiguration.class)
                .withPropertyValues(
                        "metapool.db.enabled=false",
                        "metapool.redis.enabled=false",
                        "metapool.lock.enabled=false");

        @Test
        @DisplayName("默认启用时应创建线程池 Bean")
        void shouldCreateThreadPoolBeansByDefault() {
            runner.run(ctx -> {
                assertThat(ctx).hasSingleBean(ThreadPoolConfig.class);
                assertThat(ctx).hasSingleBean(ThreadResourcePool.class);
            });
        }

        @Test
        @DisplayName("设为禁用时不应创建线程池 Bean")
        void shouldNotCreateBeansWhenDisabled() {
            runner.withPropertyValues("metapool.thread.enabled=false")
                    .run(ctx -> {
                        assertThat(ctx).doesNotHaveBean(ThreadPoolConfig.class);
                        assertThat(ctx).doesNotHaveBean(ThreadResourcePool.class);
                    });
        }

        @Test
        @DisplayName("应正确绑定线程池配置属性")
        void shouldBindThreadPoolProperties() {
            runner.withPropertyValues(
                            "metapool.thread.pool-name=test-thread-pool",
                            "metapool.thread.core-pool-size=8",
                            "metapool.thread.max-pool-size=32",
                            "metapool.thread.keep-alive-seconds=120",
                            "metapool.thread.queue-capacity=500",
                            "metapool.thread.thread-name-prefix=test-"
                    )
                    .run(ctx -> {
                        ThreadPoolConfig config = ctx.getBean(ThreadPoolConfig.class);
                        assertThat(config.getPoolName()).isEqualTo("test-thread-pool");
                        assertThat(config.getCorePoolSize()).isEqualTo(8);
                        assertThat(config.getMaxPoolSize()).isEqualTo(32);
                        assertThat(config.getKeepAliveSeconds()).isEqualTo(120);
                        assertThat(config.getQueueCapacity()).isEqualTo(500);
                        assertThat(config.getThreadNamePrefix()).isEqualTo("test-");
                    });
        }
    }

    // ==================== 内存池自动装配 ====================

    @Nested
    @DisplayName("内存池自动装配")
    class MemoryPoolAutoConfig {

        private final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(
                        MetaPoolAutoConfiguration.class,
                        MemoryPoolAutoConfiguration.class)
                .withPropertyValues(
                        "metapool.db.enabled=false",
                        "metapool.redis.enabled=false",
                        "metapool.lock.enabled=false");

        @Test
        @DisplayName("默认启用时应创建内存池 Bean")
        void shouldCreateMemoryPoolBeansByDefault() {
            runner.run(ctx -> {
                assertThat(ctx).hasSingleBean(MemoryPoolConfig.class);
                assertThat(ctx).hasSingleBean(MemoryPool.class);
            });
        }
    }

    // ==================== 限流器自动装配 ====================

    @Nested
    @DisplayName("限流器自动装配")
    class RateLimitAutoConfig {

        private final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(
                        MetaPoolAutoConfiguration.class,
                        RateLimitAutoConfiguration.class)
                .withPropertyValues(
                        "metapool.db.enabled=false",
                        "metapool.redis.enabled=false",
                        "metapool.lock.enabled=false");

        @Test
        @DisplayName("默认启用时应创建限流器 Bean")
        void shouldCreateRateLimiterBeansByDefault() {
            runner.run(ctx -> {
                assertThat(ctx).hasSingleBean(RateLimiterConfig.class);
                assertThat(ctx).hasSingleBean(TokenBucketRateLimiter.class);
            });
        }

        @Test
        @DisplayName("应正确绑定限流器配置属性")
        void shouldBindRateLimitProperties() {
            runner.withPropertyValues(
                            "metapool.rate-limit.permits-per-second=500",
                            "metapool.rate-limit.warm-up-seconds=5"
                    )
                    .run(ctx -> {
                        RateLimiterConfig config = ctx.getBean(RateLimiterConfig.class);
                        assertThat(config.getPermitsPerSecond()).isEqualTo(500.0);
                        assertThat(config.getWarmUpSeconds()).isEqualTo(5);
                    });
        }
    }

    // ==================== 组件扫描 ====================

    @Nested
    @DisplayName("组件扫描")
    class ComponentScan {

        @Test
        @DisplayName("MetaPoolProperties 应作为 Bean 注册")
        void shouldRegisterPropertiesBean() {
            new ApplicationContextRunner()
                    .withUserConfiguration(MetaPoolAutoConfiguration.class)
                    .withPropertyValues(
                            "metapool.db.enabled=false",
                            "metapool.redis.enabled=false",
                            "metapool.lock.enabled=false")
                    .run(ctx -> {
                        assertThat(ctx).hasSingleBean(MetaPoolProperties.class);
                    });
        }
    }
}
