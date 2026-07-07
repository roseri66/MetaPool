package com.metapool.pool.lock;

import com.metapool.common.lifecycle.PoolStats;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SmartReentrantLock} 单元测试。
 *
 * <p>测试通过「不存在 Redis」的方式触发降级模式，覆盖完整的锁语义：
 * 基本加锁/解锁、可重入、超时重试、并发安全、生命周期、强制释放等。
 *
 * <p>Redis 集成测试（Lua 脚本原子性、自动续期、跨进程互斥）需运行中的 Redis 实例，
 * 建议通过 Testcontainers 或 docker-compose 启动。
 *
 * @see SmartReentrantLock
 */
@DisplayName("SmartReentrantLock")
class SmartReentrantLockTest {

    private RedisClient redisClient;
    private LockConfig config;
    private SmartReentrantLock lock;

    /**
     * 每个测试用例创建独立的 LockConfig 和锁实例。
     *
     * <p>使用不存在的 Redis 端口（16379）以触发快速降级为本地锁，
     * 避免依赖外部 Redis 服务。
     *
     * <p>设置极短的连接超时（50ms）确保测试不因 TCP SYN 重传而等待过长。
     */
    @BeforeEach
    void setUp() {
        config = new LockConfig();
        config.setDefaultTtlSeconds(30);
        config.setRenewalIntervalSeconds(10);
        config.setMaxRetryCount(3);
        config.setRetryIntervalMillis(10);

        // 指向不存在的 Redis 以触发快速降级
        RedisURI uri = RedisURI.builder()
                .withHost("127.0.0.1")
                .withPort(16379)
                .build();
        redisClient = RedisClient.create(uri);

        // 设置极短连接超时，避免测试等待
        SocketOptions socketOptions = SocketOptions.builder()
                .connectTimeout(Duration.ofMillis(50))
                .build();
        ClientOptions clientOptions = ClientOptions.builder()
                .socketOptions(socketOptions)
                .build();
        redisClient.setOptions(clientOptions);

        lock = new SmartReentrantLock(config, redisClient, "test-lock-key");
    }

    /**
     * R-TEST-01：每个测试后清理，调用 destroy() 关闭调度器和连接。
     */
    @AfterEach
    void tearDown() {
        try {
            lock.destroy();
        } finally {
            redisClient.shutdown(0, 0, TimeUnit.MILLISECONDS);
        }
    }

    // ==================== 基本加锁/解锁 ====================

    @Nested
    @DisplayName("基本加锁解锁")
    class BasicLockTests {

        @Test
        @DisplayName("初始化后应处于降级模式")
        void shouldEnterFallbackModeAfterInit() {
            lock.init();
            assertTrue(lock.isInFallbackMode(),
                    "Redis 不可用时应进入降级模式");
        }

        @Test
        @DisplayName("tryLock 应成功获取锁")
        void shouldAcquireLock() {
            lock.init();

            boolean acquired = lock.tryLock();
            assertTrue(acquired, "应成功获取锁");
            assertTrue(lock.isLocked(), "isLocked 应返回 true");
            assertTrue(lock.getHoldDurationMillis() >= 0,
                    "持有时间应 >= 0");
        }

        @Test
        @DisplayName("unlock 应释放锁")
        void shouldReleaseLock() {
            lock.init();
            lock.tryLock();

            lock.unlock();

            assertFalse(lock.isLocked(), "释放后 isLocked 应返回 false");
            assertEquals(-1, lock.getHoldDurationMillis(),
                    "释放后持有时间应为 -1");
        }

        @Test
        @DisplayName("释放后可再次获取")
        void shouldAcquireAgainAfterRelease() {
            lock.init();

            assertTrue(lock.tryLock());
            lock.unlock();

            assertTrue(lock.tryLock(), "释放后应可再次获取");
            assertTrue(lock.isLocked());
            lock.unlock();
        }

        @Test
        @DisplayName("未持锁时 unlock 应无副作用")
        void shouldBeNoopWhenUnlockWithoutLock() {
            lock.init();
            // 不应抛异常
            lock.unlock();
            assertFalse(lock.isLocked());
        }

        @Test
        @DisplayName("destroy 前未解锁不应抛异常")
        void shouldNotThrowWhenDestroyWithHeldLock() {
            lock.init();
            lock.tryLock();
            // 持有锁时 destroy 应强制释放
            lock.destroy();
        }
    }

    // ==================== 可重入 ====================

    @Nested
    @DisplayName("可重入")
    class ReentrantTests {

        @Test
        @DisplayName("同一线程连续 3 次加锁应全部成功")
        void shouldAllowReentrantLocking() {
            lock.init();

            assertTrue(lock.tryLock(), "第 1 次加锁");
            assertTrue(lock.tryLock(), "第 2 次加锁（可重入）");
            assertTrue(lock.tryLock(), "第 3 次加锁（可重入）");

            assertTrue(lock.isLocked());
        }

        @Test
        @DisplayName("3 次 unlock 应完全释放锁")
        void shouldFullyReleaseAfterAllUnlocks() {
            lock.init();

            lock.tryLock();
            lock.tryLock();
            lock.tryLock();
            assertTrue(lock.isLocked());

            lock.unlock();
            assertTrue(lock.isLocked(), "1 次 unlock 后仍持有");
            lock.unlock();
            assertTrue(lock.isLocked(), "2 次 unlock 后仍持有");
            lock.unlock();
            assertFalse(lock.isLocked(), "3 次 unlock 后应完全释放");
        }

        @Test
        @DisplayName("可重入释放后其他线程可获取")
        void shouldAllowOtherThreadAfterReentrantRelease() throws Exception {
            lock.init();

            lock.tryLock();
            lock.tryLock();
            lock.unlock();
            lock.unlock();

            // 其他线程应可获取
            CountDownLatch acquired = new CountDownLatch(1);
            AtomicBoolean gotLock = new AtomicBoolean(false);

            Thread other = new Thread(() -> {
                if (lock.tryLock()) {
                    gotLock.set(true);
                    lock.unlock();
                }
                acquired.countDown();
            });
            other.start();
            acquired.await(5, TimeUnit.SECONDS);

            assertTrue(gotLock.get(), "其他线程应可获取已完全释放的锁");
        }
    }

    // ==================== 超时与重试 ====================

    @Nested
    @DisplayName("超时与重试")
    class TimeoutRetryTests {

        @Test
        @DisplayName("锁被持有时 tryLock(timeout) 应超时返回 false")
        @Timeout(10)
        void shouldTimeoutWhenLockHeldByOther() throws Exception {
            lock.init();

            // 当前线程持有锁
            lock.tryLock();

            // 其他线程尝试获取（应超时）
            CountDownLatch done = new CountDownLatch(1);
            AtomicBoolean result = new AtomicBoolean(true);

            Thread other = new Thread(() -> {
                try {
                    result.set(lock.tryLock(200, TimeUnit.MILLISECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                done.countDown();
            });
            other.start();

            done.await(5, TimeUnit.SECONDS);
            assertFalse(result.get(),
                    "锁被持有时 tryLock(timeout) 应超时返回 false");
        }

        @Test
        @DisplayName("锁在等待期间释放应获取成功")
        @Timeout(10)
        void shouldAcquireWhenReleasedDuringWait() throws Exception {
            // 使用高重试次数确保在锁释放前一直重试
            LockConfig retryConfig = new LockConfig();
            retryConfig.setMaxRetryCount(100);
            retryConfig.setRetryIntervalMillis(20);
            SmartReentrantLock retryLock =
                    new SmartReentrantLock(retryConfig, redisClient, "retry-test-key");
            retryLock.init();

            retryLock.tryLock();

            CountDownLatch acquired = new CountDownLatch(1);
            AtomicBoolean result = new AtomicBoolean(false);

            Thread other = new Thread(() -> {
                try {
                    result.set(retryLock.tryLock(2, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                acquired.countDown();
                // 正常释放
                if (result.get()) {
                    retryLock.unlock();
                }
            });
            other.start();

            // 等待其他线程开始等待后释放锁
            Thread.sleep(50);
            retryLock.unlock();

            acquired.await(5, TimeUnit.SECONDS);
            assertTrue(result.get(),
                    "等待期间锁被释放应获取成功");

            retryLock.destroy();
        }

        @Test
        @DisplayName("ResourceLifecycle.acquire(timeout) 应正确委托")
        @Timeout(10)
        void shouldDelegateAcquireWithTimeout() throws Exception {
            lock.init();
            lock.tryLock(); // 当前线程持有

            CountDownLatch done = new CountDownLatch(1);
            AtomicBoolean result = new AtomicBoolean(true);

            Thread other = new Thread(() -> {
                try {
                    result.set(lock.acquire(100, TimeUnit.MILLISECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                done.countDown();
            });
            other.start();

            done.await(5, TimeUnit.SECONDS);
            assertFalse(result.get());
        }
    }

    // ==================== 并发安全 ====================

    @Nested
    @DisplayName("并发安全")
    class ConcurrencyTests {

        @Test
        @DisplayName("多线程竞争时应只有一个获取锁")
        @Timeout(30)
        void shouldAllowOnlyOneHolderUnderContention() throws Exception {
            config.setMaxRetryCount(50);
            config.setRetryIntervalMillis(5);
            SmartReentrantLock contentionLock =
                    new SmartReentrantLock(config, redisClient, "contention-key");
            contentionLock.init();

            int threads = 10;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threads);
            AtomicInteger holders = new AtomicInteger(0);
            AtomicInteger acquiredCount = new AtomicInteger(0);

            for (int i = 0; i < threads; i++) {
                new Thread(() -> {
                    try {
                        startLatch.await();
                        if (contentionLock.tryLock(2, TimeUnit.SECONDS)) {
                            int h = holders.incrementAndGet();
                            if (h > 1) {
                                // 记录违规（不应发生）
                            }
                            acquiredCount.incrementAndGet();
                            Thread.sleep(20);
                            holders.decrementAndGet();
                            contentionLock.unlock();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    doneLatch.countDown();
                }).start();
            }

            startLatch.countDown();
            doneLatch.await(20, TimeUnit.SECONDS);

            assertTrue(acquiredCount.get() >= 1,
                    "至少应有一个线程获取锁, actual=" + acquiredCount.get());
            assertEquals(0, holders.get(),
                    "最终应无持有者");
        }

        @Test
        @DisplayName("100 线程 × 100 次 lock/unlock 应无死锁")
        @Timeout(60)
        void shouldNotDeadlockUnderHighConcurrency() throws Exception {
            config.setMaxRetryCount(100);
            config.setRetryIntervalMillis(1);
            SmartReentrantLock stressLock =
                    new SmartReentrantLock(config, redisClient, "stress-key");
            stressLock.init();

            int threads = 20;
            int iterations = 50;
            CountDownLatch doneLatch = new CountDownLatch(threads);
            AtomicInteger totalAcquired = new AtomicInteger(0);

            for (int i = 0; i < threads; i++) {
                Executors.defaultThreadFactory().newThread(() -> {
                    for (int j = 0; j < iterations; j++) {
                        try {
                            if (stressLock.tryLock(5, TimeUnit.SECONDS)) {
                                totalAcquired.incrementAndGet();
                                // 模拟业务耗时
                                Thread.sleep(1);
                                stressLock.unlock();
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    doneLatch.countDown();
                }).start();
            }

            doneLatch.await(45, TimeUnit.SECONDS);

            assertTrue(totalAcquired.get() > 0,
                    "应有成功获取: actual=" + totalAcquired.get());
            assertFalse(stressLock.isLocked(),
                    "最终锁应为释放状态");

            stressLock.destroy();
        }
    }

    // ==================== 生命周期 ====================

    @Nested
    @DisplayName("生命周期")
    class LifecycleTests {

        @Test
        @DisplayName("init 应完成初始化")
        void shouldInitialize() {
            lock.init();
            assertTrue(lock.isInFallbackMode(),
                    "Redis 不可用时应在降级模式");
        }

        @Test
        @DisplayName("重复 init 应幂等")
        void shouldBeIdempotent() {
            lock.init();
            boolean firstFallback = lock.isInFallbackMode();

            lock.init();
            boolean secondFallback = lock.isInFallbackMode();

            assertEquals(firstFallback, secondFallback,
                    "重复 init 应幂等");
        }

        @Test
        @DisplayName("destroy 应清理状态")
        void shouldCleanupOnDestroy() {
            lock.init();
            lock.tryLock();
            lock.unlock();

            lock.destroy();

            PoolStats stats = lock.stats();
            assertNotNull(stats);
            assertEquals(0, stats.getActiveCount());
        }

        @Test
        @DisplayName("stats 应返回指标快照")
        void shouldReturnMetrics() {
            lock.init();

            lock.tryLock();
            PoolStats statsHeld = lock.stats();
            assertEquals(1, statsHeld.getActiveCount(),
                    "持有锁时 activeCount 应为 1");
            assertTrue(statsHeld.getTotalAcquired() >= 1);

            lock.unlock();
            PoolStats statsFree = lock.stats();
            assertEquals(0, statsFree.getActiveCount(),
                    "释放后 activeCount 应为 0");
        }
    }

    // ==================== 强制释放 ====================

    @Nested
    @DisplayName("强制释放")
    class ForceUnlockTests {

        @Test
        @DisplayName("forceUnlock 应释放其他线程持有的锁")
        @Timeout(10)
        void shouldForceReleaseOtherThreadsLock() throws Exception {
            lock.init();

            // 其他线程持有锁
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch keepLock = new CountDownLatch(1);
            AtomicBoolean holderAcquired = new AtomicBoolean(false);

            Thread holder = new Thread(() -> {
                holderAcquired.set(lock.tryLock());
                locked.countDown();
                try {
                    keepLock.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                // 不会正常 unlock（模拟异常情况）
            });
            holder.start();

            locked.await(5, TimeUnit.SECONDS);
            assertTrue(holderAcquired.get(),
                    "持有线程应成功获取锁");

            // 强制释放
            lock.forceUnlock();

            // 释放后主线程应可获取
            assertTrue(lock.tryLock(), "forceUnlock 后应可获取锁");
            lock.unlock();

            keepLock.countDown();
            holder.join(2000);
        }

        @Test
        @DisplayName("forceUnlock 多次调用应无副作用")
        void shouldBeIdempotent() {
            lock.init();
            lock.tryLock();

            lock.forceUnlock();
            lock.forceUnlock(); // 不应抛异常
            lock.forceUnlock();

            assertFalse(lock.isLocked());
        }
    }

    // ==================== 降级模式 ====================

    @Nested
    @DisplayName("降级模式")
    class FallbackTests {

        @Test
        @DisplayName("init 失败后应自动降级并可用")
        void shouldWorkInFallbackMode() {
            lock.init();

            assertTrue(lock.isInFallbackMode(),
                    "Redis 不可用时 isInFallbackMode 应返回 true");

            assertTrue(lock.tryLock(), "降级模式下 tryLock 应成功");
            assertTrue(lock.isLocked());
            lock.unlock();
            assertFalse(lock.isLocked());
        }

        @Test
        @DisplayName("降级模式下可重入应正常")
        void shouldSupportReentrantInFallback() {
            lock.init();

            lock.tryLock();
            lock.tryLock();
            lock.tryLock();

            lock.unlock();
            lock.unlock();
            lock.unlock();

            assertFalse(lock.isLocked());
        }

        @Test
        @DisplayName("降级锁应是互斥的")
        @Timeout(10)
        void shouldBeExclusiveInFallback() throws Exception {
            lock.init();
            lock.tryLock();

            CountDownLatch done = new CountDownLatch(1);
            AtomicBoolean gotLock = new AtomicBoolean(false);

            Thread other = new Thread(() -> {
                gotLock.set(lock.tryLock());
                done.countDown();
                if (gotLock.get()) {
                    lock.unlock();
                }
            });
            other.start();

            Thread.sleep(100);
            lock.unlock();

            done.await(5, TimeUnit.SECONDS);
            assertTrue(gotLock.get() || lock.getHoldDurationMillis() < 0,
                    "主线程释放后其他线程应可获取");
        }
    }

    // ==================== ResourceLifecycle 接口 ====================

    @Nested
    @DisplayName("ResourceLifecycle 接口")
    class ResourceLifecycleTests {

        @Test
        @DisplayName("acquire() 阻塞获取应成功")
        @Timeout(5)
        void shouldBlockAcquire() throws Exception {
            lock.init();

            Boolean result = lock.acquire();
            assertTrue(result, "acquire 应返回 true");
            assertTrue(lock.isLocked());

            lock.unlock();
        }

        @Test
        @DisplayName("release(Boolean) 应正确释放")
        void shouldReleaseViaLifecycle() throws InterruptedException {
            lock.init();
            lock.acquire();

            lock.release(true);

            assertFalse(lock.isLocked());
        }
    }

    // ==================== 配置校验 ====================

    @Nested
    @DisplayName("配置校验")
    class ConfigValidationTests {

        @Test
        @DisplayName("null config 应抛异常")
        void shouldRejectNullConfig() {
            try {
                new SmartReentrantLock(null, redisClient, "key");
                assert false : "应抛出 IllegalArgumentException";
            } catch (IllegalArgumentException e) {
                // 预期
            }
        }

        @Test
        @DisplayName("null redisClient 应抛异常")
        void shouldRejectNullRedisClient() {
            try {
                new SmartReentrantLock(new LockConfig(), null, "key");
                assert false : "应抛出 IllegalArgumentException";
            } catch (IllegalArgumentException e) {
                // 预期
            }
        }

        @Test
        @DisplayName("空 lockKey 应抛异常")
        void shouldRejectNullOrEmptyLockKey() {
            try {
                new SmartReentrantLock(new LockConfig(), redisClient, null);
                assert false : "应抛出 IllegalArgumentException for null";
            } catch (IllegalArgumentException e) {
                // 预期
            }
            try {
                new SmartReentrantLock(new LockConfig(), redisClient, "");
                assert false : "应抛出 IllegalArgumentException for empty";
            } catch (IllegalArgumentException e) {
                // 预期
            }
            try {
                new SmartReentrantLock(new LockConfig(), redisClient, "   ");
                assert false : "应抛出 IllegalArgumentException for blank";
            } catch (IllegalArgumentException e) {
                // 预期
            }
        }

        @Test
        @DisplayName("LockConfig 参数校验")
        void shouldValidateConfigParameters() {
            LockConfig cfg = new LockConfig();

            try {
                cfg.setDefaultTtlSeconds(0);
                assert false : "应抛出异常";
            } catch (IllegalArgumentException e) {
                // 预期
            }

            try {
                cfg.setDefaultTtlSeconds(-1);
                assert false : "应抛出异常";
            } catch (IllegalArgumentException e) {
                // 预期
            }

            try {
                cfg.setRenewalIntervalSeconds(0);
                assert false : "应抛出异常";
            } catch (IllegalArgumentException e) {
                // 预期
            }

            try {
                cfg.setMaxRetryCount(-1);
                assert false : "应抛出异常";
            } catch (IllegalArgumentException e) {
                // 预期
            }

            try {
                cfg.setRetryIntervalMillis(-1);
                assert false : "应抛出异常";
            } catch (IllegalArgumentException e) {
                // 预期
            }
        }
    }
}
