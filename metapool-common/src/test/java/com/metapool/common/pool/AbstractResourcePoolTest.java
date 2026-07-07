package com.metapool.common.pool;

import com.metapool.common.enums.PoolStatus;
import com.metapool.common.exception.PoolExhaustedException;
import com.metapool.common.exception.PoolInitializationException;
import com.metapool.common.lifecycle.PoolStats;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AbstractResourcePool")
class AbstractResourcePoolTest {

    // ========================================================================
    // 基本生命周期
    // ========================================================================

    @Nested
    @DisplayName("生命周期")
    class LifecycleTests {

        @Test
        @DisplayName("init 预创建 minIdle 个资源")
        void initCreatesMinIdleResources() {
            PoolConfig config = new PoolConfig();
            config.setMinIdle(3);
            config.setMaxPoolSize(10);

            TestPool pool = new TestPool(config);
            pool.init();

            assertEquals(3, pool.getTotalCreated());
            assertEquals(PoolStatus.RUNNING, pool.status);
            pool.destroy();
        }

        @Test
        @DisplayName("init 失败回滚并抛异常")
        void initRollsBackOnFailure() {
            PoolConfig config = new PoolConfig();
            config.setMinIdle(2);

            // 用匿名子类让第二次 createResource 失败
            TestPool pool = new TestPool(config) {
                private int callCount = 0;

                @Override
                protected TestResource createResource() throws Exception {
                    if (callCount++ == 1) {
                        throw new RuntimeException("simulated failure");
                    }
                    return super.createResource();
                }
            };

            assertThrows(PoolInitializationException.class, pool::init);
            assertEquals(PoolStatus.NEW, pool.status);
            // 第一个创建的资源应被回滚销毁
            assertEquals(1, pool.destroyed.size());
        }

        @Test
        @DisplayName("destroy 清理全部资源")
        void destroyCleansAllResources() {
            PoolConfig config = new PoolConfig();
            config.setMinIdle(2);
            config.setMaxPoolSize(5);

            TestPool pool = new TestPool(config);
            pool.init();
            assertEquals(PoolStatus.RUNNING, pool.status);

            pool.destroy();
            assertEquals(PoolStatus.DESTROYED, pool.status);
            // 所有创建的资源都应被销毁
            assertEquals(pool.created.size(), pool.destroyed.size());
        }

        @Test
        @DisplayName("重复 init 幂等")
        void initIsIdempotent() {
            PoolConfig config = new PoolConfig();
            config.setMinIdle(1);

            TestPool pool = new TestPool(config);
            pool.init();
            int createdAfterFirst = pool.getTotalCreated();
            pool.init(); // 第二次
            assertEquals(createdAfterFirst, pool.getTotalCreated());
            pool.destroy();
        }
    }

    // ========================================================================
    // acquire / release
    // ========================================================================

    @Nested
    @DisplayName("获取与归还")
    class AcquireReleaseTests {

        private PoolConfig config;
        private TestPool pool;

        @BeforeEach
        void setUp() {
            config = new PoolConfig();
            config.setMinIdle(2);
            config.setMaxPoolSize(5);
            pool = new TestPool(config);
            pool.init();
        }

        @AfterEach
        void tearDown() {
            if (pool != null) {
                pool.destroy();
            }
        }

        @Test
        @DisplayName("acquire 返回有效资源")
        void acquireReturnsValidResource() throws Exception {
            TestResource resource = pool.acquire();
            assertNotNull(resource);
            assertFalse(resource.isDestroyed());
        }

        @Test
        @DisplayName("release 归还后资源回到空闲池")
        void releaseReturnsToIdlePool() throws Exception {
            TestResource resource = pool.acquire();
            pool.release(resource);

            PoolStats stats = pool.stats();
            assertEquals(0, stats.getActiveCount());
            assertEquals(config.getMinIdle(), stats.getIdleCount());
        }

        @Test
        @DisplayName("release null 不抛异常")
        void releaseNullIsNoop() {
            assertDoesNotThrow(() -> pool.release(null));
        }

        @Test
        @DisplayName("acquire 耗尽池后阻塞等待")
        void acquireBlocksWhenPoolExhausted() throws Exception {
            // 借出所有资源
            List<TestResource> borrowed = new ArrayList<>();
            for (int i = 0; i < config.getMaxPoolSize(); i++) {
                borrowed.add(pool.acquire());
            }

            // 异步归还一个资源
            new Thread(() -> {
                sleepQuietly(200);
                pool.release(borrowed.get(0));
            }).start();

            // acquire 应被阻塞然后成功获取
            long start = System.currentTimeMillis();
            TestResource resource = pool.acquire();
            long elapsed = System.currentTimeMillis() - start;

            assertNotNull(resource);
            assertTrue(elapsed >= 150, "Expected blocking but got resource in " + elapsed + "ms");
        }

        @Test
        @DisplayName("acquire(timeout) 超时抛 PoolExhaustedException")
        void acquireTimeoutThrows() throws Exception {
            // 借出所有资源
            for (int i = 0; i < config.getMaxPoolSize(); i++) {
                pool.acquire();
            }

            assertThrows(PoolExhaustedException.class, () ->
                    pool.acquire(100, TimeUnit.MILLISECONDS));
        }

        @Test
        @DisplayName("release 唤醒等待的 acquire 线程")
        void releaseWakesAcquire() throws Exception {
            // 借出所有资源
            List<TestResource> borrowed = new ArrayList<>();
            for (int i = 0; i < config.getMaxPoolSize(); i++) {
                borrowed.add(pool.acquire());
            }

            CountDownLatch acquired = new CountDownLatch(1);
            new Thread(() -> {
                try {
                    TestResource r = pool.acquire();
                    assertNotNull(r);
                    acquired.countDown();
                } catch (Exception e) {
                    fail(e);
                }
            }).start();

            Thread.sleep(100);
            pool.release(borrowed.get(0));

            assertTrue(acquired.await(5, TimeUnit.SECONDS));
        }
    }

    // ========================================================================
    // 并发测试
    // ========================================================================

    @Nested
    @DisplayName("并发安全")
    class ConcurrencyTests {

        @Test
        @DisplayName("20 线程 × 200 次 acquire/release 无死锁无泄露")
        void concurrentAcquireReleaseNoDeadlockNoLeak() throws Exception {
            PoolConfig config = new PoolConfig();
            config.setMinIdle(5);
            config.setMaxPoolSize(20);
            config.setIdleTimeoutSeconds(600);       // 测试期间不触发回收
            config.setLeakDetectionThresholdSeconds(600);

            TestPool pool = new TestPool(config);
            pool.init();

            int threads = 20;
            int opsPerThread = 200;
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            AtomicInteger totalAcquired = new AtomicInteger(0);
            AtomicInteger totalReleased = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(threads);

            for (int t = 0; t < threads; t++) {
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < opsPerThread; i++) {
                            TestResource r = pool.acquire();
                            assertNotNull(r);
                            totalAcquired.incrementAndGet();
                            // 模拟短暂使用
                            if (i % 10 == 0) {
                                Thread.sleep(0, 100); // ~0.1ms
                            }
                            pool.release(r);
                            totalReleased.incrementAndGet();
                        }
                    } catch (Exception e) {
                        fail("Unexpected exception: " + e.getMessage(), e);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(120, TimeUnit.SECONDS), "Test timed out");
            executor.shutdown();

            // 所有获取都归还了
            assertEquals(totalAcquired.get(), totalReleased.get(),
                    "All acquired should be released");

            // 最终无活跃资源
            PoolStats stats = pool.stats();
            assertEquals(0, stats.getActiveCount(), "No active resources at end");
            assertEquals(0, stats.getLeakDetected(), "No leaks detected");

            // 无死锁证明：所有线程都完成了
            assertEquals(0, latch.getCount());

            pool.destroy();
        }
    }

    // ========================================================================
    // 空闲回收
    // ========================================================================

    @Nested
    @DisplayName("空闲回收")
    class IdleEvictionTests {

        @Test
        @DisplayName("空闲超时后资源被自动回收")
        void idleResourcesAreEvicted() throws Exception {
            PoolConfig config = new PoolConfig();
            config.setMinIdle(0);
            config.setMaxPoolSize(5);
            config.setIdleTimeoutSeconds(2);           // 2 秒超时
            config.setLeakDetectionThresholdSeconds(300); // 不触发泄露

            TestPool pool = new TestPool(config);
            pool.init();

            // 借出再归还，资源进入空闲队列
            TestResource r = pool.acquire();
            pool.release(r);

            assertEquals(1, pool.stats().getIdleCount());

            // 等待空闲回收
            Thread.sleep(3500);

            // 空闲资源应被回收
            PoolStats stats = pool.stats();
            assertEquals(0, stats.getIdleCount(), "Idle resources should be evicted");
            assertTrue(r.isDestroyed(), "Evicted resource should be destroyed");

            pool.destroy();
        }

        @Test
        @DisplayName("空闲时间未到不回收")
        void freshIdleResourcesNotEvicted() throws Exception {
            PoolConfig config = new PoolConfig();
            config.setMinIdle(2);
            config.setMaxPoolSize(5);
            config.setIdleTimeoutSeconds(10);          // 10 秒超时

            TestPool pool = new TestPool(config);
            pool.init();

            Thread.sleep(500); // 远不到 10 秒

            PoolStats stats = pool.stats();
            assertEquals(2, stats.getIdleCount(), "Fresh idle resources should not be evicted");

            pool.destroy();
        }
    }

    // ========================================================================
    // 泄露检测
    // ========================================================================

    @Nested
    @DisplayName("泄露检测")
    class LeakDetectionTests {

        @Test
        @DisplayName("借出超时触发 onResourceLeaked")
        void borrowTimeoutTriggersLeakCallback() throws Exception {
            PoolConfig config = new PoolConfig();
            config.setMinIdle(0);
            config.setMaxPoolSize(5);
            config.setIdleTimeoutSeconds(300);
            config.setLeakDetectionThresholdSeconds(2); // 2 秒泄露阈值

            TestPool pool = new TestPool(config);
            pool.init();

            // 借出不归还
            TestResource r = pool.acquire();

            // 等待泄露检测触发
            Thread.sleep(3500);

            assertTrue(pool.leakNotified.contains(r), "Leak callback should be triggered");
            assertTrue(pool.stats().getLeakDetected() > 0, "Leak counter should increment");

            pool.release(r);
            pool.destroy();
        }

        @Test
        @DisplayName("正常归还不触发泄露")
        void normalReturnDoesNotTriggerLeak() throws Exception {
            PoolConfig config = new PoolConfig();
            config.setMinIdle(0);
            config.setMaxPoolSize(5);
            config.setLeakDetectionThresholdSeconds(5);

            TestPool pool = new TestPool(config);
            pool.init();

            TestResource r = pool.acquire();
            Thread.sleep(100); // 远小于阈值
            pool.release(r);

            Thread.sleep(1000);
            assertFalse(pool.leakNotified.contains(r));
            assertEquals(0, pool.stats().getLeakDetected());

            pool.destroy();
        }
    }

    // ========================================================================
    // 资源验证
    // ========================================================================

    @Nested
    @DisplayName("资源验证")
    class ValidationTests {

        @Test
        @DisplayName("无效资源在借出前被销毁并创建新资源")
        void invalidResourceIsDestroyedAndReplaced() throws Exception {
            PoolConfig config = new PoolConfig();
            config.setMinIdle(1);
            config.setMaxPoolSize(5);

            TestPool pool = new TestPool(config);
            pool.init();

            // 将 init 创建的空闲资源标记为无效，确保 acquire 时销毁并创建新资源
            TestResource initResource = pool.getCreated().iterator().next();
            pool.validator = r -> r != initResource; // init 资源无效，其他有效

            // acquire 时 init 资源验证失败被销毁，创建新资源
            TestResource r1 = pool.acquire();
            assertNotSame(initResource, r1, "Invalid init resource should be replaced");
            assertTrue(initResource.isDestroyed(), "Invalid resource should be destroyed");

            pool.release(r1);
            pool.destroy();
        }
    }

    // ========================================================================
    // 状态检查
    // ========================================================================

    @Nested
    @DisplayName("状态保护")
    class StateGuardTests {

        @Test
        @DisplayName("destroy 后 acquire 抛异常")
        void acquireAfterDestroyThrows() {
            PoolConfig config = new PoolConfig();
            config.setMinIdle(0);

            TestPool pool = new TestPool(config);
            pool.init();
            pool.destroy();

            assertThrows(PoolExhaustedException.class, () -> pool.acquire());
        }

        @Test
        @DisplayName("destroy 后 release 不抛异常")
        void releaseAfterDestroyDoesNotThrow() throws Exception {
            PoolConfig config = new PoolConfig();
            config.setMinIdle(1);

            TestPool pool = new TestPool(config);
            pool.init();
            TestResource r = pool.acquire();
            pool.destroy();

            // release 不应抛异常，资源应被销毁
            assertDoesNotThrow(() -> pool.release(r));
            assertTrue(r.isDestroyed());
        }

        @Test
        @DisplayName("未 init 就 acquire 抛异常")
        void acquireBeforeInitThrows() {
            PoolConfig config = new PoolConfig();
            TestPool pool = new TestPool(config);

            assertThrows(PoolExhaustedException.class, () -> pool.acquire());
        }
    }

    // ========================================================================
    // 统计
    // ========================================================================

    @Nested
    @DisplayName("统计快照")
    class StatsTests {

        @Test
        @DisplayName("stats 反映当前池状态")
        void statsReflectPoolState() throws Exception {
            PoolConfig config = new PoolConfig();
            config.setMinIdle(2);
            config.setMaxPoolSize(5);

            TestPool pool = new TestPool(config);
            pool.init();

            PoolStats s1 = pool.stats();
            assertEquals(0, s1.getActiveCount());
            assertEquals(2, s1.getIdleCount());
            assertEquals(2, s1.getPoolSize());

            TestResource r = pool.acquire();
            PoolStats s2 = pool.stats();
            assertEquals(1, s2.getActiveCount());
            assertEquals(1, s2.getIdleCount());

            pool.release(r);
            PoolStats s3 = pool.stats();
            assertEquals(0, s3.getActiveCount());
            assertEquals(2, s3.getIdleCount());

            pool.destroy();
        }
    }

    // ========================================================================
    // 工具方法
    // ========================================================================

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
