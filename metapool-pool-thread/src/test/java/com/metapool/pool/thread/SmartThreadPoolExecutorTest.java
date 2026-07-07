package com.metapool.pool.thread;

import com.metapool.common.lifecycle.PoolStats;
import org.junit.jupiter.api.AfterEach;
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
 * {@link SmartThreadPoolExecutor} 单元测试。
 */
@DisplayName("SmartThreadPoolExecutor")
class SmartThreadPoolExecutorTest {

    private SmartThreadPoolExecutor executor;

    @AfterEach
    void tearDown() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }

    /** 轮询等待条件成立，避免 finally 块时序竞争 */
    private static void waitFor(BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.yield();
        }
    }

    @FunctionalInterface
    private interface BooleanSupplier {
        boolean getAsBoolean();
    }

    // ==================== 基本调度 ====================

    @Nested
    @DisplayName("基本调度逻辑")
    class BasicSchedulingTests {

        @Test
        @DisplayName("应正确执行单个任务")
        void shouldExecuteSingleTask() throws Exception {
            ThreadPoolConfig config = new ThreadPoolConfig();
            config.setCorePoolSize(2);
            config.setMaxPoolSize(4);
            executor = new SmartThreadPoolExecutor(config);

            AtomicInteger result = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(1);

            executor.execute(() -> {
                result.set(42);
                latch.countDown();
            });

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertEquals(42, result.get());
            // 等待 Worker 的 finally 块更新 completedTasks
            waitFor(() -> executor.getCompletedTasks() >= 1, 2000);
            assertEquals(1, executor.getCompletedTasks());
        }

        @Test
        @DisplayName("应正确执行多个并发任务")
        void shouldExecuteMultipleConcurrentTasks() throws Exception {
            ThreadPoolConfig config = new ThreadPoolConfig();
            config.setCorePoolSize(4);
            config.setMaxPoolSize(8);
            config.setQueueCapacity(100);
            executor = new SmartThreadPoolExecutor(config);

            int taskCount = 100;
            CountDownLatch latch = new CountDownLatch(taskCount);
            AtomicInteger counter = new AtomicInteger(0);

            for (int i = 0; i < taskCount; i++) {
                executor.execute(() -> {
                    counter.incrementAndGet();
                    latch.countDown();
                });
            }

            assertTrue(latch.await(10, TimeUnit.SECONDS));
            assertEquals(taskCount, counter.get());
            waitFor(() -> executor.getCompletedTasks() >= taskCount, 3000);
            assertEquals(taskCount, executor.getCompletedTasks());
        }

        @Test
        @DisplayName("任务数超过核心线程数时应使用队列")
        void shouldUseQueueWhenExceedingCoreSize() throws Exception {
            ThreadPoolConfig config = new ThreadPoolConfig();
            config.setCorePoolSize(2);
            config.setMaxPoolSize(4);
            config.setQueueCapacity(50);
            executor = new SmartThreadPoolExecutor(config);

            // 提交阻塞任务占用核心线程
            CountDownLatch blockingLatch = new CountDownLatch(1);
            CountDownLatch taskStarted = new CountDownLatch(2);

            for (int i = 0; i < 2; i++) {
                executor.execute(() -> {
                    taskStarted.countDown();
                    try {
                        blockingLatch.await();
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            assertTrue(taskStarted.await(2, TimeUnit.SECONDS));

            // 此时核心线程已满，新任务应入队
            CountDownLatch queuedLatch = new CountDownLatch(1);
            executor.execute(() -> queuedLatch.countDown());

            assertEquals(1, executor.getQueueSize(), "任务应进入队列等待");
            assertEquals(2, executor.getPoolSize(), "线程数应等于 corePoolSize");

            // 释放阻塞任务
            blockingLatch.countDown();
            assertTrue(queuedLatch.await(2, TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("队列满后应扩容到最大线程数")
        void shouldScaleToMaxWhenQueueFull() throws Exception {
            ThreadPoolConfig config = new ThreadPoolConfig();
            config.setCorePoolSize(1);
            config.setMaxPoolSize(3);
            config.setQueueCapacity(2);
            config.setKeepAliveSeconds(60);
            executor = new SmartThreadPoolExecutor(config);

            CountDownLatch blockingLatch = new CountDownLatch(1);
            CountDownLatch started = new CountDownLatch(1);

            // 占满核心线程
            executor.execute(() -> {
                started.countDown();
                try {
                    blockingLatch.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(started.await(1, TimeUnit.SECONDS));

            // 占满队列（capacity=2）
            for (int i = 0; i < 2; i++) {
                executor.execute(() -> {
                    try {
                        blockingLatch.await();
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            assertEquals(2, executor.getQueueSize());

            // 下一任务应触发扩容
            CountDownLatch expandedLatch = new CountDownLatch(1);
            executor.execute(() -> expandedLatch.countDown());

            // 等待扩容 worker 启动并执行任务
            assertTrue(expandedLatch.await(3, TimeUnit.SECONDS),
                    "队列满后应扩容到 maxPoolSize 直接执行任务");
            assertTrue(executor.getPoolSize() >= 2,
                    "应创建了新 worker");

            blockingLatch.countDown();
        }
    }

    // ==================== 拒绝策略 ====================

    @Nested
    @DisplayName("拒绝策略")
    class RejectionPolicyTests {

        @Test
        @DisplayName("AbortPolicy 应抛出 RejectedExecutionException")
        void shouldThrowOnAbortPolicy() {
            ThreadPoolConfig config = new ThreadPoolConfig();
            config.setCorePoolSize(1);
            config.setMaxPoolSize(1);
            config.setQueueCapacity(1);
            config.setRejectedPolicy(RejectedPolicyEnum.ABORT);
            executor = new SmartThreadPoolExecutor(config);

            // 占满 core + queue
            executor.execute(() -> sleepMs(500));
            executor.execute(() -> sleepMs(500));

            // 第三个任务应被拒绝
            assertThrows(RejectedExecutionException.class,
                    () -> executor.execute(() -> {}),
                    "AbortPolicy 应抛异常");
        }

        @Test
        @DisplayName("CallerRunsPolicy 应由调用者线程执行")
        void shouldRunInCallerOnCallerRunsPolicy() throws Exception {
            ThreadPoolConfig config = new ThreadPoolConfig();
            config.setCorePoolSize(1);
            config.setMaxPoolSize(1);
            config.setQueueCapacity(1);
            config.setRejectedPolicy(RejectedPolicyEnum.CALLER_RUNS);
            executor = new SmartThreadPoolExecutor(config);

            // 占满 core + queue
            CountDownLatch blocker = new CountDownLatch(1);
            executor.execute(() -> {
                try {
                    blocker.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
            executor.execute(() -> sleepMs(100));

            // CallerRuns: 应由当前线程执行
            String callerThread = Thread.currentThread().getName();
            String[] executedThread = new String[1];
            CountDownLatch done = new CountDownLatch(1);

            executor.execute(() -> {
                executedThread[0] = Thread.currentThread().getName();
                done.countDown();
            });

            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertEquals(callerThread, executedThread[0],
                    "CallerRunsPolicy 应由调用者线程执行");

            blocker.countDown();
        }

        @Test
        @DisplayName("DiscardPolicy 应静默丢弃")
        void shouldDiscardSilently() throws Exception {
            ThreadPoolConfig config = new ThreadPoolConfig();
            config.setCorePoolSize(1);
            config.setMaxPoolSize(1);
            config.setQueueCapacity(1);
            config.setRejectedPolicy(RejectedPolicyEnum.DISCARD);
            executor = new SmartThreadPoolExecutor(config);

            // 占满
            CountDownLatch blocker = new CountDownLatch(1);
            executor.execute(() -> {
                try {
                    blocker.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
            executor.execute(() -> sleepMs(100));

            // Discard: 直接丢弃，不抛异常
            AtomicInteger executed = new AtomicInteger(0);
            executor.execute(() -> executed.incrementAndGet());

            assertEquals(1, executor.getRejectedTasks(),
                    "DiscardPolicy 应记录为拒绝");
            assertEquals(0, executed.get(),
                    "被 Discard 的任务不应执行");

            blocker.countDown();
        }
    }

    // ==================== 动态调参 ====================

    @Nested
    @DisplayName("运行时动态调参")
    class DynamicConfigTests {

        @Test
        @DisplayName("增大 corePoolSize 应即时生效")
        void shouldIncreaseCorePoolSizeDynamically() throws Exception {
            ThreadPoolConfig config = new ThreadPoolConfig();
            config.setCorePoolSize(1);
            config.setMaxPoolSize(10);
            config.setKeepAliveSeconds(60);
            executor = new SmartThreadPoolExecutor(config);

            executor.prestartCoreThreads();
            assertEquals(1, executor.getPoolSize());

            executor.setCorePoolSize(4);
            // 等待新核心线程创建
            Thread.sleep(200);

            assertEquals(4, executor.getPoolSize(),
                    "增大 corePoolSize 应创建新线程");
        }

        @Test
        @DisplayName("修改 maxPoolSize 应即时生效")
        void shouldApplyMaxPoolSizeChangeImmediately() {
            ThreadPoolConfig config = new ThreadPoolConfig();
            config.setCorePoolSize(1);
            config.setMaxPoolSize(3);
            executor = new SmartThreadPoolExecutor(config);

            assertEquals(3, executor.getMaxPoolSize());

            executor.setMaxPoolSize(5);
            assertEquals(5, executor.getMaxPoolSize());
        }
    }

    // ==================== 预热与关闭 ====================

    @Nested
    @DisplayName("预热与优雅关闭")
    class LifecycleTests {

        @Test
        @DisplayName("prestartCoreThreads 应预创建所有核心线程")
        void shouldPrestartAllCoreThreads() {
            ThreadPoolConfig config = new ThreadPoolConfig();
            config.setCorePoolSize(4);
            config.setMaxPoolSize(8);
            config.setKeepAliveSeconds(60);
            executor = new SmartThreadPoolExecutor(config);

            executor.prestartCoreThreads();

            assertEquals(4, executor.getPoolSize(),
                    "应预创建等于 corePoolSize 的线程");
        }

        @Test
        @DisplayName("shutdown 后不应接受新任务")
        void shouldRejectAfterShutdown() {
            ThreadPoolConfig config = new ThreadPoolConfig();
            config.setRejectedPolicy(RejectedPolicyEnum.ABORT);
            executor = new SmartThreadPoolExecutor(config);

            executor.shutdown();

            assertThrows(RejectedExecutionException.class,
                    () -> executor.execute(() -> {}),
                    "shutdown 后应拒绝新任务");
        }

        @Test
        @DisplayName("awaitTermination 应等待所有任务完成")
        void shouldAwaitTermination() throws Exception {
            ThreadPoolConfig config = new ThreadPoolConfig();
            config.setCorePoolSize(2);
            config.setMaxPoolSize(2);
            config.setKeepAliveSeconds(1);
            executor = new SmartThreadPoolExecutor(config);

            AtomicInteger counter = new AtomicInteger(0);
            int taskCount = 10;
            CountDownLatch latch = new CountDownLatch(taskCount);

            for (int i = 0; i < taskCount; i++) {
                executor.execute(() -> {
                    counter.incrementAndGet();
                    latch.countDown();
                });
            }

            // 等待所有任务执行
            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertEquals(taskCount, counter.get());

            executor.shutdown();
            boolean terminated = executor.awaitTermination(10, TimeUnit.SECONDS);

            assertTrue(terminated, "应在超时前完成所有任务");
            assertTrue(executor.isTerminated());
        }
    }

    // ==================== 指标 ====================

    @Nested
    @DisplayName("指标统计")
    class StatsTests {

        @Test
        @DisplayName("应正确统计各项指标")
        void shouldTrackAllMetrics() throws Exception {
            ThreadPoolConfig config = new ThreadPoolConfig();
            config.setCorePoolSize(2);
            config.setMaxPoolSize(4);
            config.setQueueCapacity(100);
            executor = new SmartThreadPoolExecutor(config);

            // 提交 10 个任务
            CountDownLatch latch = new CountDownLatch(10);
            for (int i = 0; i < 10; i++) {
                executor.execute(() -> {
                    latch.countDown();
                });
            }

            assertTrue(latch.await(5, TimeUnit.SECONDS));

            waitFor(() -> executor.getCompletedTasks() >= 10, 2000);
            assertEquals(10, executor.getCompletedTasks(),
                    "completedTasks 应等于已执行任务数");
            assertEquals(0, executor.getActiveCount(),
                    "所有任务完成后 activeCount 应为 0");
            assertEquals(0, executor.getQueueSize(),
                    "所有任务完成后队列应为空");
            assertEquals(2, executor.getPoolSize(),
                    "应保持 corePoolSize 个线程");
        }

        @Test
        @DisplayName("rejectedTasks 应正确统计")
        void shouldTrackRejectedCount() {
            ThreadPoolConfig config = new ThreadPoolConfig();
            config.setCorePoolSize(1);
            config.setMaxPoolSize(1);
            config.setQueueCapacity(1);
            config.setRejectedPolicy(RejectedPolicyEnum.DISCARD);
            executor = new SmartThreadPoolExecutor(config);

            executor.execute(() -> sleepMs(300));
            executor.execute(() -> sleepMs(100));
            executor.execute(() -> {}); // 被拒绝
            executor.execute(() -> {}); // 被拒绝

            assertEquals(2, executor.getRejectedTasks(),
                    "应统计被拒绝的任务数");
        }
    }

    // ==================== ThreadResourcePool ====================

    @Nested
    @DisplayName("ThreadResourcePool 包装")
    class ThreadResourcePoolTests {

        @Test
        @DisplayName("init 应预创建核心线程")
        void shouldInitWithCoreThreads() {
            ThreadPoolConfig config = new ThreadPoolConfig();
            config.setCorePoolSize(3);
            config.setMaxPoolSize(6);

            ThreadResourcePool pool = new ThreadResourcePool(config);
            pool.init();

            SmartThreadPoolExecutor exec = pool.getExecutor();
            assertEquals(3, exec.getPoolSize());
            assertNotNull(pool.stats());
            assertEquals(3, pool.stats().getIdleCount());

            pool.destroy();
        }

        @Test
        @DisplayName("stats 应返回正确的指标快照")
        void shouldReturnCorrectStats() throws Exception {
            ThreadPoolConfig config = new ThreadPoolConfig();
            config.setCorePoolSize(2);
            config.setMaxPoolSize(4);
            config.setQueueCapacity(50);

            ThreadResourcePool pool = new ThreadResourcePool(config);
            pool.init();

            // 提交阻塞任务
            CountDownLatch blocker = new CountDownLatch(1);
            CountDownLatch started = new CountDownLatch(2);

            for (int i = 0; i < 2; i++) {
                pool.getExecutor().execute(() -> {
                    started.countDown();
                    try {
                        blocker.await();
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            assertTrue(started.await(2, TimeUnit.SECONDS));

            PoolStats stats = pool.stats();
            assertEquals(2, stats.getActiveCount());
            assertEquals(2, stats.getPoolSize());
            assertEquals(0, stats.getPendingCount());

            blocker.countDown();
            pool.destroy();
        }

        @Test
        @DisplayName("acquire/release 应抛 UnsupportedOperationException")
        void shouldThrowOnAcquireRelease() throws Exception {
            ThreadPoolConfig config = new ThreadPoolConfig();
            ThreadResourcePool pool = new ThreadResourcePool(config);
            pool.init();

            assertThrows(UnsupportedOperationException.class,
                    () -> pool.acquire());

            assertThrows(UnsupportedOperationException.class,
                    () -> pool.release(new ThreadResource(Thread.currentThread())));

            pool.destroy();
        }
    }

    private static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
