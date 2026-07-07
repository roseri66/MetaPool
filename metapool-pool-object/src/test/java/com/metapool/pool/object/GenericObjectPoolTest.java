package com.metapool.pool.object;

import com.metapool.common.exception.PoolExhaustedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GenericObjectPool} 单元测试。
 */
@DisplayName("GenericObjectPool")
class GenericObjectPoolTest {

    /** 记录创建/销毁/验证次数的测试工厂 */
    private static class CountingFactory implements ObjectFactory<String> {
        private final AtomicInteger createCount = new AtomicInteger(0);
        private final AtomicInteger destroyCount = new AtomicInteger(0);
        private final AtomicInteger validateCount = new AtomicInteger(0);
        private final AtomicBoolean failValidation = new AtomicBoolean(false);

        @Override
        public String create() {
            createCount.incrementAndGet();
            return "obj-" + createCount.get();
        }

        @Override
        public void destroy(String obj) {
            destroyCount.incrementAndGet();
        }

        @Override
        public boolean validate(String obj) {
            validateCount.incrementAndGet();
            return !failValidation.get();
        }

        int getCreateCount() { return createCount.get(); }
        int getDestroyCount() { return destroyCount.get(); }
        int getValidateCount() { return validateCount.get(); }
        void setFailValidation(boolean fail) { failValidation.set(fail); }
    }

    private ObjectPoolConfig config;
    private CountingFactory factory;
    private GenericObjectPool<String> pool;

    @BeforeEach
    void setUp() {
        factory = new CountingFactory();
        config = new ObjectPoolConfig();
        config.setMinIdle(2);
        config.setMaxPoolSize(8);
        config.setIdleTimeoutSeconds(300);
    }

    @AfterEach
    void tearDown() {
        try {
            pool.destroy();
        } catch (Exception ignored) {
        }
    }

    /** 创建池并返回 baseline 创建数量（init 预创建 minIdle 个）。 */
    private int createAndInit(boolean lifo) {
        config.setLifo(lifo);
        pool = new GenericObjectPool<>(config, factory);
        pool.init();
        return factory.getCreateCount(); // baseline = minIdle
    }

    // ==================== 工厂模式 ====================

    @Nested
    @DisplayName("工厂模式")
    class FactoryTests {

        @Test
        @DisplayName("init 应通过工厂预创建 minIdle 个对象")
        void shouldPreCreateMinIdleObjects() {
            int baseline = createAndInit(false);
            assertEquals(2, baseline, "应预创建 minIdle=2 个对象");
        }

        @Test
        @DisplayName("acquire 应调用工厂创建新对象")
        void shouldCreateViaFactoryOnAcquire() throws Exception {
            int baseline = createAndInit(false); // 2 created

            // 获取已预创建的两个对象
            pool.acquire();
            pool.acquire();

            // 第三次获取需创建新对象（池未满）
            pool.acquire();

            assertTrue(factory.getCreateCount() > baseline,
                    "应创建了比 baseline 更多的对象");
        }

        @Test
        @DisplayName("destroy 应调用工厂销毁对象")
        void shouldDestroyViaFactory() throws Exception {
            int baseline = createAndInit(false);
            int destroyBaseline = factory.getDestroyCount();

            pool.destroy();

            assertTrue(factory.getDestroyCount() > destroyBaseline,
                    "destroy 应触发工厂销毁");
        }

        @Test
        @DisplayName("自定义工厂类型应与池泛型匹配")
        void shouldSupportCustomFactory() throws Exception {
            ObjectFactory<Integer> intFactory = new ObjectFactory<>() {
                private int counter;

                public Integer create() { return ++counter; }
                public void destroy(Integer obj) { /* no-op */ }
            };
            ObjectPoolConfig cfg = new ObjectPoolConfig();
            cfg.setMinIdle(1);
            cfg.setMaxPoolSize(3);
            cfg.setLifo(false);
            GenericObjectPool<Integer> intPool = new GenericObjectPool<>(cfg, intFactory);
            intPool.init();

            Integer val = intPool.acquire();
            assertNotNull(val);
            assertEquals(1, val);

            intPool.destroy();
        }
    }

    // ==================== FIFO / LIFO ====================

    @Nested
    @DisplayName("FIFO / LIFO")
    class OrderingTests {

        @Test
        @DisplayName("FIFO 模式应按借出顺序归还")
        void shouldBeFifo() throws Exception {
            int baseline = createAndInit(false); // FIFO

            String a = pool.acquire(); // baseline+1
            String b = pool.acquire(); // baseline+2
            pool.release(a);
            pool.release(b);

            String first = pool.acquire();
            String second = pool.acquire();

            // FIFO: a released first → a acquired first
            assertEquals(a, first, "FIFO: 先归还的应先获取");
            assertEquals(b, second);

            pool.release(first);
            pool.release(second);
        }

        @Test
        @DisplayName("LIFO 模式应按后进先出")
        void shouldBeLifo() throws Exception {
            int baseline = createAndInit(true); // LIFO

            // 耗尽预创建对象
            String p1 = pool.acquire();
            String p2 = pool.acquire();

            // 获取第三个（新创建，baseline+1）
            String a = pool.acquire();
            // 获取第四个（新创建，baseline+2）
            String b = pool.acquire();

            pool.release(a);
            pool.release(b);

            // LIFO: b released last → b acquired first
            String first = pool.acquire();
            String second = pool.acquire();

            assertEquals(b, first, "LIFO: 后归还的应先获取");
            assertEquals(a, second);

            pool.release(p1);
            pool.release(p2);
            pool.release(first);
            pool.release(second);
        }

        @Test
        @DisplayName("同一对象归还后立即获取应相同（热对象复用）")
        void shouldReturnSameObjectOnImmediateAcquire() throws Exception {
            createAndInit(true); // LIFO

            String obj = pool.acquire();
            pool.release(obj);

            String same = pool.acquire();
            assertEquals(obj, same, "LIFO 下归还后立即获取应拿回同一个对象");

            pool.release(same);
        }
    }

    // ==================== 验证 ====================

    @Nested
    @DisplayName("对象验证")
    class ValidationTests {

        @Test
        @DisplayName("无效对象归还时应被销毁")
        void shouldDestroyInvalidOnRelease() throws Exception {
            createAndInit(false);
            int destroyBaseline = factory.getDestroyCount();

            String obj = pool.acquire();
            factory.setFailValidation(true);
            pool.release(obj);

            assertTrue(factory.getDestroyCount() > destroyBaseline,
                    "无效对象归还时应被销毁");

            factory.setFailValidation(false);
        }

        @Test
        @DisplayName("无效对象借出前应被剔除")
        void shouldDiscardInvalidOnAcquire() throws Exception {
            createAndInit(true);

            // 获取所有预创建对象
            String a = pool.acquire();
            String b = pool.acquire();
            // 归还它们
            pool.release(a);
            pool.release(b);

            // 标记为无效
            factory.setFailValidation(true);

            // 获取时应验证失败 → 销毁 → 创建新对象
            int destroyBefore = factory.getDestroyCount();
            int createBefore = factory.getCreateCount();

            String c = pool.acquire();
            assertTrue(factory.getDestroyCount() > destroyBefore,
                    "无效对象应被销毁");
            assertFalse(a.equals(c) || b.equals(c),
                    "新对象不应是被销毁的无效对象");

            factory.setFailValidation(false);
            pool.release(c);
            // 归还 b 已无效被销毁（已在 acquire 中处理）
        }
    }

    // ==================== 空闲回收 ====================

    @Nested
    @DisplayName("空闲回收")
    class IdleEvictionTests {

        @Test
        @DisplayName("空闲超时对象应被自动回收")
        void shouldEvictIdleObjects() throws Exception {
            config.setIdleTimeoutSeconds(1); // 1 秒超时
            createAndInit(false);
            int destroyBaseline = factory.getDestroyCount();

            // 归还所有预创建对象（变为空闲）
            String a = pool.acquire();
            String b = pool.acquire();
            pool.release(a);
            pool.release(b);

            // 等待空闲回收触发（扫描间隔 ≤ idleTimeout/2 = 0.5s）
            Thread.sleep(1500);

            int destroyed = factory.getDestroyCount() - destroyBaseline;
            assertTrue(destroyed >= 1,
                    "空闲超时后应至少回收 1 个对象，实际=" + destroyed);
        }
    }

    // ==================== 生命周期 ====================

    @Nested
    @DisplayName("生命周期")
    class LifecycleTests {

        @Test
        @DisplayName("destroy 后 acquire 应抛异常")
        void shouldRejectAcquireAfterDestroy() {
            createAndInit(false);
            pool.destroy();

            assertThrows(PoolExhaustedException.class, () -> pool.acquire(),
                    "destroy 后 acquire 应抛 PoolExhaustedException");
        }

        @Test
        @DisplayName("stats 应反映正确状态")
        void shouldReturnCorrectStats() throws Exception {
            createAndInit(false);

            // baseline=2 pre-created, all idle
            assertEquals(0, pool.stats().getActiveCount());
            assertEquals(2, pool.stats().getIdleCount());

            String obj = pool.acquire();
            assertEquals(1, pool.stats().getActiveCount());
            assertEquals(1, pool.stats().getIdleCount());

            pool.release(obj);
            assertEquals(0, pool.stats().getActiveCount());
            assertEquals(2, pool.stats().getIdleCount());
        }
    }

    // ==================== 并发 ====================

    @Nested
    @DisplayName("并发安全")
    class ConcurrencyTests {

        @Test
        @DisplayName("多线程 acquire/release 应线程安全")
        void shouldBeThreadSafe() throws Exception {
            config.setMaxPoolSize(10);
            createAndInit(false);

            int threads = 8;
            int iterations = 100;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threads);
            AtomicInteger errors = new AtomicInteger(0);

            for (int i = 0; i < threads; i++) {
                new Thread(() -> {
                    try {
                        startLatch.await();
                        for (int j = 0; j < iterations; j++) {
                            String obj = pool.acquire();
                            Thread.sleep(1);
                            pool.release(obj);
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                    doneLatch.countDown();
                }).start();
            }

            startLatch.countDown();
            doneLatch.await(30, TimeUnit.SECONDS);

            assertEquals(0, errors.get(), "不应有错误");
            // 所有对象应已归还
            assertEquals(0, pool.stats().getActiveCount(),
                    "最终 activeCount 应为 0");
        }

        @Test
        @DisplayName("池满时等待线程应在有归还后获取成功")
        void shouldWaitWhenPoolExhausted() throws Exception {
            config.setMaxPoolSize(2);
            config.setMinIdle(0);
            createAndInit(false);

            // 主线程持有所有对象
            String a = pool.acquire();
            String b = pool.acquire();

            AtomicReference<String> result = new AtomicReference<>();
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(1);

            Thread waiter = new Thread(() -> {
                try {
                    started.countDown();
                    result.set(pool.acquire(3, TimeUnit.SECONDS));
                } catch (Exception e) {
                    result.set("ERROR");
                }
                done.countDown();
            });
            waiter.start();

            started.await();
            Thread.sleep(50); // 确保 waiter 进入等待

            // 归还一个
            pool.release(a);
            done.await(5, TimeUnit.SECONDS);

            assertNotNull(result.get(), "应获取到对象");
            assertNotEquals("ERROR", result.get());

            // 清理
            if (result.get() != null && !result.get().equals("ERROR")) {
                pool.release(result.get());
            }
            pool.release(b);
        }
    }

    // ==================== 构造器校验 ====================

    @Nested
    @DisplayName("构造器校验")
    class ConstructorTests {

        @Test
        @DisplayName("null config 应抛异常")
        void shouldRejectNullConfig() {
            assertThrows(IllegalArgumentException.class,
                    () -> new GenericObjectPool<>(null, factory));
        }

        @Test
        @DisplayName("null factory 应抛异常")
        void shouldRejectNullFactory() {
            assertThrows(IllegalArgumentException.class,
                    () -> new GenericObjectPool<>(new ObjectPoolConfig(), null));
        }
    }

    // ==================== LIFO 互斥 ====================

    @Nested
    @DisplayName("LIFO 并发互斥")
    class LifoConcurrencyTests {

        @Test
        @DisplayName("LIFO 多线程应保持互斥")
        void shouldMaintainExclusiveAccessInLifo() throws Exception {
            config.setMaxPoolSize(2);
            createAndInit(true);

            AtomicInteger concurrentCount = new AtomicInteger(0);
            AtomicInteger maxConcurrent = new AtomicInteger(0);
            int threads = 4;
            CountDownLatch done = new CountDownLatch(threads);

            for (int i = 0; i < threads; i++) {
                new Thread(() -> {
                    try {
                        for (int j = 0; j < 50; j++) {
                            String obj = pool.acquire();
                            int c = concurrentCount.incrementAndGet();
                            maxConcurrent.updateAndGet(v -> Math.max(v, c));
                            Thread.sleep(1);
                            concurrentCount.decrementAndGet();
                            pool.release(obj);
                        }
                    } catch (Exception e) {
                        // ignore
                    }
                    done.countDown();
                }).start();
            }

            done.await(30, TimeUnit.SECONDS);

            assertTrue(maxConcurrent.get() <= 2,
                    "最大并发获取数不应超过 maxPoolSize（2），实际=" + maxConcurrent.get());
        }
    }
}
