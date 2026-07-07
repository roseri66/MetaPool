package com.metapool.pool.redis;

import com.metapool.common.exception.PoolExhaustedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RedisConnectionPool} 单元测试（使用 mock 连接）。
 */
@DisplayName("RedisConnectionPool")
class RedisConnectionPoolTest {

    private RedisPoolConfig config;
    private RedisConnectionPool pool;
    private AtomicInteger createCount;
    private AtomicInteger closeCount;
    private boolean validationResult;

    @BeforeEach
    void setUp() {
        createCount = new AtomicInteger(0);
        closeCount = new AtomicInteger(0);
        validationResult = true;

        config = new RedisPoolConfig();
        config.setMinIdle(2);
        config.setMaxPoolSize(4);
        config.setIdleTimeoutSeconds(2);
        config.setLeakDetectionThresholdSeconds(10);
        config.setConnectionTimeoutSeconds(3);
        config.setPoolName("test-redis");

        RedisConnectionFactory factory = () -> {
            createCount.incrementAndGet();
            return new Object() {
                boolean closed;

                void close() {
                    closed = true;
                    closeCount.incrementAndGet();
                }

                @Override
                public String toString() {
                    return "MockRedisConnection{closed=" + closed + "}";
                }
            };
        };

        RedisConnectionValidator validator = (conn, timeout) -> validationResult;

        pool = new RedisConnectionPool(config, factory, validator);
    }

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.destroy();
        }
    }

    @Nested
    @DisplayName("生命周期")
    class LifecycleTests {

        @Test
        @DisplayName("init 应预创建 minIdle 个连接")
        void shouldCreateMinIdleOnInit() {
            pool.init();
            assertEquals(2, createCount.get());
            assertEquals(2, pool.stats().getIdleCount());
        }

        @Test
        @DisplayName("destroy 应清空所有连接")
        void shouldClearOnDestroy() throws Exception {
            pool.init();
            PooledRedisConnection pc = pool.acquire();
            pool.release(pc);
            pool.destroy();
            assertEquals(0, pool.stats().getPoolSize());
        }

        @Test
        @DisplayName("重复 init 应幂等")
        void shouldBeIdempotentInit() {
            pool.init();
            int count = createCount.get();
            pool.init();
            assertEquals(count, createCount.get());
        }
    }

    @Nested
    @DisplayName("借出与归还")
    class AcquireReleaseTests {

        @Test
        @DisplayName("应成功借出和归还连接")
        void shouldAcquireAndRelease() throws Exception {
            pool.init();
            PooledRedisConnection pc = pool.acquire();

            assertNotNull(pc);
            assertNotNull(pc.getConnection());
            assertEquals(1, pool.stats().getActiveCount());

            pool.release(pc);
            assertEquals(0, pool.stats().getActiveCount());
        }

        @Test
        @DisplayName("连接耗尽应抛 PoolExhaustedException")
        void shouldThrowWhenPoolExhausted() throws Exception {
            config.setMinIdle(1);
            config.setMaxPoolSize(1);
            config.setConnectionTimeoutSeconds(1);
            pool = new RedisConnectionPool(config,
                    () -> { createCount.incrementAndGet(); return new Object(); },
                    (c, t) -> true);
            pool.init();

            pool.acquire(); // 占用唯一连接
            assertThrows(PoolExhaustedException.class,
                    () -> pool.acquire());
        }

        @Test
        @DisplayName("release null 不应抛异常")
        void shouldIgnoreNullRelease() {
            pool.init();
            pool.release(null);
        }
    }

    @Nested
    @DisplayName("连接验证")
    class ValidationTests {

        @Test
        @DisplayName("有效连接应通过验证")
        void shouldPassValidation() throws Exception {
            pool.init();
            PooledRedisConnection pc = pool.acquire();
            assertNotNull(pc);
            pool.release(pc);
        }

        @Test
        @DisplayName("无效连接应验证失败")
        void shouldFailValidation() {
            validationResult = false;
            pool.init();

            // 初始化阶段的连接验证不应影响 init（只在 borrow 时验证）
            boolean valid = pool.validateResource(
                    new PooledRedisConnection(new Object()));
            assertFalse(valid);
        }
    }

    @Nested
    @DisplayName("空闲回收 + 泄露检测")
    class EvictionAndLeakTests {

        @Test
        @DisplayName("空闲超时应回收连接")
        void shouldEvictIdleConnections() throws Exception {
            config.setMinIdle(1);
            config.setMaxPoolSize(2);
            config.setIdleTimeoutSeconds(1);
            config.setLeakDetectionThresholdSeconds(60);
            pool = new RedisConnectionPool(config,
                    () -> { createCount.incrementAndGet(); return new Object(); },
                    (c, t) -> true);
            pool.init();

            assertEquals(1, pool.stats().getPoolSize());
            Thread.sleep(2500);
            assertEquals(0, pool.stats().getIdleCount(),
                    "空闲超时后应被回收");
        }

        @Test
        @DisplayName("借出超时应检测泄露")
        void shouldDetectLeak() throws Exception {
            config.setMinIdle(1);
            config.setMaxPoolSize(2);
            config.setLeakDetectionThresholdSeconds(1);
            config.setIdleTimeoutSeconds(60);
            pool = new RedisConnectionPool(config,
                    () -> { createCount.incrementAndGet(); return new Object(); },
                    (c, t) -> true);
            pool.init();

            pool.acquire(); // 借出不归还
            Thread.sleep(2500);

            assertTrue(pool.stats().getLeakDetected() > 0,
                    "应检测到泄露");
        }
    }

    @Nested
    @DisplayName("并发安全")
    class ConcurrencyTests {

        @Test
        @DisplayName("多线程并发借出归还应无死锁")
        void shouldBeThreadSafe() throws Exception {
            config.setMinIdle(2);
            config.setMaxPoolSize(10);
            config.setConnectionTimeoutSeconds(30);
            pool = new RedisConnectionPool(config,
                    () -> { createCount.incrementAndGet(); return new Object(); },
                    (c, t) -> true);
            pool.init();

            int threads = 10;
            int iterations = 100;
            CountDownLatch latch = new CountDownLatch(threads);
            AtomicInteger errors = new AtomicInteger(0);

            for (int i = 0; i < threads; i++) {
                new Thread(() -> {
                    for (int j = 0; j < iterations; j++) {
                        try {
                            PooledRedisConnection pc = pool.acquire();
                            Thread.sleep(1);
                            pool.release(pc);
                        } catch (Exception e) {
                            errors.incrementAndGet();
                        }
                    }
                    latch.countDown();
                }).start();
            }

            assertTrue(latch.await(30, TimeUnit.SECONDS));
            assertEquals(0, errors.get());
            assertEquals(threads * iterations, pool.stats().getTotalAcquired());
        }

        @Test
        @DisplayName("50 线程 × 200 次高并发借出归还应无死锁无泄露")
        void shouldHandleHighConcurrency() throws Exception {
            config.setMinIdle(4);
            config.setMaxPoolSize(20);
            config.setConnectionTimeoutSeconds(30);
            pool = new RedisConnectionPool(config,
                    () -> { createCount.incrementAndGet(); return new Object(); },
                    (c, t) -> true);
            pool.init();

            int threads = 50;
            int iterations = 200;
            CountDownLatch latch = new CountDownLatch(threads);
            AtomicInteger errors = new AtomicInteger(0);

            for (int i = 0; i < threads; i++) {
                new Thread(() -> {
                    for (int j = 0; j < iterations; j++) {
                        try {
                            PooledRedisConnection pc = pool.acquire();
                            pool.release(pc);
                        } catch (Exception e) {
                            errors.incrementAndGet();
                        }
                    }
                    latch.countDown();
                }).start();
            }

            assertTrue(latch.await(60, TimeUnit.SECONDS),
                    "50 线程 × 200 次操作应在 60s 内完成");
            assertEquals(0, errors.get(), "不应有错误");

            long totalAcquired = pool.stats().getTotalAcquired();
            assertEquals(threads * iterations, totalAcquired,
                    "每次 acquire 都应成功");

            // 验证无泄露：所有连接已归还
            assertEquals(0, pool.stats().getActiveCount(),
                    "所有连接应已归还，activeCount 应为 0");
        }
    }
}
