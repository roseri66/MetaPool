package com.smartpool.starter;

import com.smartpool.pool.memory.MemoryPool;
import com.smartpool.pool.memory.MemoryPoolConfig;
import com.smartpool.pool.rate.limit.RateLimiterConfig;
import com.smartpool.pool.rate.limit.TokenBucketRateLimiter;
import com.smartpool.pool.thread.ThreadPoolConfig;
import com.smartpool.pool.thread.ThreadResourcePool;
import com.smartpool.starter.config.MemoryPoolAutoConfiguration;
import com.smartpool.starter.config.RateLimitAutoConfiguration;
import com.smartpool.starter.config.ThreadPoolAutoConfiguration;

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
class SmartPoolAutoConfigurationTest {

    // ==================== 线程池自动装配 ====================

    @Nested
    @DisplayName("线程池自动装配")
    class ThreadPoolAutoConfig {

        private final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(
                        SmartPoolAutoConfiguration.class,
                        ThreadPoolAutoConfiguration.class)
                .withPropertyValues(
                        "smartpool.db.enabled=false",
                        "smartpool.redis.enabled=false",
                        "smartpool.lock.enabled=false");

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
            runner.withPropertyValues("smartpool.thread.enabled=false")
                    .run(ctx -> {
                        assertThat(ctx).doesNotHaveBean(ThreadPoolConfig.class);
                        assertThat(ctx).doesNotHaveBean(ThreadResourcePool.class);
                    });
        }

        @Test
        @DisplayName("应正确绑定线程池配置属性")
        void shouldBindThreadPoolProperties() {
            runner.withPropertyValues(
                            "smartpool.thread.pool-name=test-thread-pool",
                            "smartpool.thread.core-pool-size=8",
                            "smartpool.thread.max-pool-size=32",
                            "smartpool.thread.keep-alive-seconds=120",
                            "smartpool.thread.queue-capacity=500",
                            "smartpool.thread.thread-name-prefix=test-"
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
                        SmartPoolAutoConfiguration.class,
                        MemoryPoolAutoConfiguration.class)
                .withPropertyValues(
                        "smartpool.db.enabled=false",
                        "smartpool.redis.enabled=false",
                        "smartpool.lock.enabled=false");

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
                        SmartPoolAutoConfiguration.class,
                        RateLimitAutoConfiguration.class)
                .withPropertyValues(
                        "smartpool.db.enabled=false",
                        "smartpool.redis.enabled=false",
                        "smartpool.lock.enabled=false");

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
                            "smartpool.rate-limit.permits-per-second=500",
                            "smartpool.rate-limit.warm-up-seconds=5"
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
        @DisplayName("SmartPoolProperties 应作为 Bean 注册")
        void shouldRegisterPropertiesBean() {
            new ApplicationContextRunner()
                    .withUserConfiguration(SmartPoolAutoConfiguration.class)
                    .withPropertyValues(
                            "smartpool.db.enabled=false",
                            "smartpool.redis.enabled=false",
                            "smartpool.lock.enabled=false")
                    .run(ctx -> {
                        assertThat(ctx).hasSingleBean(SmartPoolProperties.class);
                    });
        }
    }
}
