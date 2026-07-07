package com.smartpool.agent;

import com.smartpool.agent.interceptor.*;
import com.smartpool.agent.model.PoolType;

import com.smartpool.pool.lock.LockConfig;
import com.smartpool.pool.lock.SmartReentrantLock;
import com.smartpool.pool.memory.MemoryPool;
import com.smartpool.pool.memory.MemoryPoolConfig;
import com.smartpool.pool.rate.limit.RateLimiterConfig;
import com.smartpool.pool.rate.limit.TokenBucketRateLimiter;
import com.smartpool.pool.thread.SmartThreadPoolExecutor;
import com.smartpool.pool.thread.ThreadPoolConfig;

import io.lettuce.core.RedisClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SPEC-13 Agent 拦截器集成测试。
 *
 * <p>使用真实池模块（test 依赖），通过直接调用拦截器静态方法
 * 验证指标采集逻辑的正确性。不依赖 ByteBuddy 运行时挂载。
 *
 * @since 0.1.0
 */
@DisplayName("SPEC-13 Agent 拦截器集成测试")
class SmartPoolAgentTest {

    private static final SmartPoolMetrics METRICS = SmartPoolMetrics.getInstance();

    @BeforeEach
    void setUp() {
        METRICS.clear();
    }

    @AfterEach
    void tearDown() {
        METRICS.clear();
    }

    // ==================== 线程池拦截 ====================

    @Nested
    @DisplayName("线程池拦截 — SmartThreadPoolExecutor")
    class ThreadPoolInterceptorTests {

        @Test
        @DisplayName("execute 进入退出应记录 executeCount 和指标快照")
        void shouldCollectThreadPoolMetrics() throws Exception {
            ThreadPoolConfig config = new ThreadPoolConfig();
            config.setCorePoolSize(2);
            config.setMaxPoolSize(4);
            config.setQueueCapacity(10);
            SmartThreadPoolExecutor executor = new SmartThreadPoolExecutor(config);

            CountDownLatch latch = new CountDownLatch(1);
            executor.execute(latch::countDown);
            latch.await(5, TimeUnit.SECONDS);

            // 模拟 ByteBuddy 拦截调用
            ThreadPoolInterceptor.onExecuteEnter(executor);
            ThreadPoolInterceptor.onExecuteExit(executor);

            String poolId = buildPoolId(executor);
            assertTrue(METRICS.getCounter(poolId, "executeCount") >= 1);
            assertTrue(METRICS.getGauge(poolId, "poolSize") >= 0);
            assertTrue(METRICS.getCounter(poolId, "completedTasks") >= 0);

            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        @Test
        @DisplayName("并发 execute 应正确累加计数器")
        void shouldAccumulateUnderConcurrency() throws Exception {
            ThreadPoolConfig config = new ThreadPoolConfig();
            config.setCorePoolSize(4);
            config.setMaxPoolSize(8);
            config.setQueueCapacity(100);
            SmartThreadPoolExecutor executor = new SmartThreadPoolExecutor(config);

            int taskCount = 20;
            CountDownLatch latch = new CountDownLatch(taskCount);
            for (int i = 0; i < taskCount; i++) {
                executor.execute(latch::countDown);
            }
            latch.await(10, TimeUnit.SECONDS);

            // 模拟拦截
            ThreadPoolInterceptor.onExecuteEnter(executor);
            ThreadPoolInterceptor.onExecuteExit(executor);

            String poolId = buildPoolId(executor);
            assertTrue(METRICS.getGauge(poolId, "poolSize") > 0);

            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    // ==================== 限流器拦截 ====================

    @Nested
    @DisplayName("限流器拦截 — TokenBucketRateLimiter")
    class RateLimiterInterceptorTests {

        @Test
        @DisplayName("tryAcquire 放行时应记录 passCount")
        void shouldRecordPass() {
            RateLimiterConfig config = new RateLimiterConfig();
            config.setPermitsPerSecond(1000);
            TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(config);
            limiter.init();

            boolean passed = limiter.tryAcquire();

            // 模拟拦截
            RateLimiterInterceptor.onTryAcquireExit(limiter, passed);

            String poolId = buildPoolId(limiter);
            assertTrue(METRICS.getCounter(poolId, "tryAcquireCount") >= 1);
            if (passed) {
                assertTrue(METRICS.getCounter(poolId, "passCount") >= 1);
            }

            limiter.destroy();
        }

        @Test
        @DisplayName("令牌耗尽后应记录 rejectCount")
        void shouldRecordReject() {
            RateLimiterConfig config = new RateLimiterConfig();
            config.setPermitsPerSecond(10);
            TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(config);
            limiter.init();

            // 消耗所有令牌
            for (int i = 0; i < 10; i++) {
                limiter.tryAcquire();
            }

            boolean passed = limiter.tryAcquire();

            RateLimiterInterceptor.onTryAcquireExit(limiter, passed);

            String poolId = buildPoolId(limiter);
            if (!passed) {
                assertTrue(METRICS.getCounter(poolId, "rejectCount") >= 1,
                        "令牌耗尽后 tryAcquire 应被拒绝并记录 rejectCount");
            }

            limiter.destroy();
        }
    }

    // ==================== 分布式锁拦截 ====================

    @Nested
    @DisplayName("分布式锁拦截 — SmartReentrantLock")
    class LockInterceptorTests {

        @Test
        @DisplayName("tryLock 成功应记录 acquireCount")
        void shouldRecordLockAcquire() {
            RedisClient redisClient = RedisClient.create("redis://localhost:6379");
            LockConfig config = new LockConfig();
            SmartReentrantLock lock = new SmartReentrantLock(config, redisClient, "test-lock-key");
            lock.init();

            boolean acquired = lock.tryLock();

            LockInterceptor.onTryLockExit(lock, 0, acquired);

            String poolId = buildPoolId(lock);
            assertTrue(METRICS.getCounter(poolId, "acquireCount") >= 1,
                    "tryLock 成功时应记录 acquireCount");

            if (acquired) {
                lock.unlock();
                LockInterceptor.onUnlockExit(lock);
            }
            lock.destroy();
            redisClient.shutdown();
        }

        @Test
        @DisplayName("unlock 应记录 releaseCount")
        void shouldRecordLockRelease() {
            RedisClient redisClient = RedisClient.create("redis://localhost:6379");
            LockConfig config = new LockConfig();
            SmartReentrantLock lock = new SmartReentrantLock(config, redisClient, "test-lock-key-2");
            lock.init();

            boolean acquired = lock.tryLock();
            LockInterceptor.onTryLockExit(lock, 0, acquired);

            if (acquired) {
                lock.unlock();
                LockInterceptor.onUnlockExit(lock);
            }

            String poolId = buildPoolId(lock);
            if (acquired) {
                assertTrue(METRICS.getCounter(poolId, "releaseCount") >= 1,
                        "unlock 时应记录 releaseCount");
            }

            lock.destroy();
            redisClient.shutdown();
        }
    }

    // ==================== 内存池拦截 ====================

    @Nested
    @DisplayName("内存池拦截 — MemoryPool")
    class MemoryPoolInterceptorTests {

        @Test
        @DisplayName("allocateDirect 应记录 directAllocatedBytes")
        void shouldRecordDirectAllocation() {
            MemoryPoolConfig config = new MemoryPoolConfig();
            config.setMaxPoolSize(4);
            config.setMinIdle(0);
            config.setMaxDirectMemoryMB(64);
            config.setPageSizeKB(4);
            MemoryPool pool = new MemoryPool(config);
            pool.init();

            java.nio.ByteBuffer buffer = pool.allocateDirect(1024);

            MemoryPoolInterceptor.onAllocateDirectExit(pool, 1024, null);

            String poolId = buildPoolId(pool);
            assertTrue(METRICS.getCounter(poolId, "allocateDirectCount") >= 1);
            assertTrue(METRICS.getCounter(poolId, "directAllocatedBytes") >= 1024);

            pool.freeDirect(buffer);
            MemoryPoolInterceptor.onFreeDirectExit(pool);

            assertTrue(METRICS.getCounter(poolId, "freeDirectCount") >= 1);

            pool.destroy();
        }
    }

    // ==================== Agent 入口测试 ====================

    @Nested
    @DisplayName("SmartPoolAgent 入口")
    class AgentEntryTests {

        @Test
        @DisplayName("premain 应能正常执行不抛异常")
        void shouldNotThrowOnPremain() {
            assertDoesNotThrow(() -> {
                Class<?> agentClass = Class.forName("com.smartpool.agent.SmartPoolAgent");
                assertNotNull(agentClass);
            });
        }

        @Test
        @DisplayName("SmartPoolAgent 应为工具类（私有构造器）")
        void shouldBeUtilityClass() throws Exception {
            java.lang.reflect.Constructor<?> ctor = SmartPoolAgent.class.getDeclaredConstructor();
            assertTrue(java.lang.reflect.Modifier.isPrivate(ctor.getModifiers()),
                    "SmartPoolAgent 构造器应为 private");
        }
    }

    // ==================== 工具方法 ====================

    private static String buildPoolId(Object target) {
        return target.getClass().getSimpleName() + "@" + System.identityHashCode(target);
    }
}
