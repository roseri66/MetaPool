package com.smartpool.pool.memory;

import com.smartpool.common.exception.PoolExhaustedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MemoryPool} 单元测试。
 */
@DisplayName("MemoryPool")
class MemoryPoolTest {

    private MemoryPoolConfig config;
    private MemoryPool pool;

    @BeforeEach
    void setUp() {
        config = new MemoryPoolConfig();
        config.setPoolName("test-memory");
        config.setMinIdle(2);
        config.setMaxPoolSize(8);
        config.setMaxDirectMemoryMB(32);
        config.setPageSizeKB(64);
    }

    @AfterEach
    void tearDown() {
        if (pool != null) {
            try {
                pool.destroy();
            } catch (Exception ignored) {
            }
        }
    }

    /** 创建池并初始化。 */
    private void createAndInit() {
        pool = new MemoryPool(config);
        pool.init();
    }

    /** 快速借出（200ms 超时），池满无空闲页则抛 PoolExhaustedException。 */
    private MemoryPage quickAcquire() throws Exception {
        return pool.acquire(200, TimeUnit.MILLISECONDS);
    }

    // ==================== 配置校验 ====================

    @Nested
    @DisplayName("配置校验")
    class ConfigValidationTests {

        @Test
        @DisplayName("maxPoolSize 在内存上限内应正常创建")
        void shouldAcceptValidMaxPoolSize() {
            config.setMaxPoolSize(16);
            config.setPageSizeKB(64);
            config.setMaxDirectMemoryMB(32);

            pool = new MemoryPool(config);
            pool.init();
            assertNotNull(pool);
        }

        @Test
        @DisplayName("pageSizeKB 为 0 或负数应抛 IllegalArgumentException")
        void shouldRejectNonPositivePageSize() {
            assertThrows(IllegalArgumentException.class, () -> config.setPageSizeKB(0));
            assertThrows(IllegalArgumentException.class, () -> config.setPageSizeKB(-1));
        }

        @Test
        @DisplayName("maxDirectMemoryMB 为 0 或负数应抛 IllegalArgumentException")
        void shouldRejectNonPositiveMaxMemory() {
            assertThrows(IllegalArgumentException.class, () -> config.setMaxDirectMemoryMB(0));
            assertThrows(IllegalArgumentException.class, () -> config.setMaxDirectMemoryMB(-1));
        }
    }

    // ==================== 基本生命周期 ====================

    @Nested
    @DisplayName("基本生命周期")
    class LifecycleTests {

        @Test
        @DisplayName("init 应预创建 minIdle 个内存页")
        void shouldPreCreateMinIdlePages() {
            createAndInit();

            assertEquals(2 * 64 * 1024, pool.getHeapMemoryUsed(),
                    "堆内存用量 = minIdle=2 × 64KB = 131072 字节");
            assertEquals(0, pool.getDirectMemoryUsed());
        }

        @Test
        @DisplayName("acquire 从空闲页获取，不创建新页")
        void shouldAcquireIdlePage() throws Exception {
            createAndInit();
            long heapBefore = pool.getHeapMemoryUsed();

            MemoryPage page = pool.acquire();
            assertNotNull(page);
            assertNotNull(page.getData());
            assertEquals(64, page.getPageSizeKB());
            assertEquals(64 * 1024, page.getPageSizeBytes());

            // 从空闲获取不创建新页，堆内存不变
            assertEquals(heapBefore, pool.getHeapMemoryUsed());
        }

        @Test
        @DisplayName("acquire 池未满时创建新页")
        void shouldCreateNewPageWhenIdleEmpty() throws Exception {
            createAndInit();

            // 耗尽所有 minIdle 页
            MemoryPage p1 = pool.acquire();
            MemoryPage p2 = pool.acquire();

            long heapBefore = pool.getHeapMemoryUsed();

            // 第三次获取应创建新页
            MemoryPage p3 = pool.acquire();
            assertNotNull(p3);

            assertEquals(heapBefore + 64 * 1024, pool.getHeapMemoryUsed(),
                    "创建新页后堆内存应增加一个 pageSize");
        }

        @Test
        @DisplayName("release 归还页后堆内存不变")
        void shouldReleaseAndKeepMemory() throws Exception {
            createAndInit();
            MemoryPage page = pool.acquire();
            long heapBefore = pool.getHeapMemoryUsed();

            pool.release(page);
            assertEquals(heapBefore, pool.getHeapMemoryUsed(),
                    "归还仅将页放回 idleMap，堆内存不变");
        }

        @Test
        @DisplayName("release null 应静默处理")
        void shouldHandleNullRelease() {
            createAndInit();
            pool.release(null); // 不抛异常
        }
    }

    // ==================== 内存上限 ====================

    @Nested
    @DisplayName("内存上限")
    class MemoryLimitTests {

        @Test
        @DisplayName("堆内页总内存超 maxDirectMemoryMB 时 acquire 抛 PoolExhaustedException")
        void shouldThrowWhenHeapMemoryExceeded() throws Exception {
            // maxDirectMemoryMB=1MB = 1024KB, pageSizeKB=64KB → 最多 16 页
            // maxPoolSize=17 > 16（让基类允许创建，由内存检查拦截）
            config.setMaxDirectMemoryMB(1);
            config.setPageSizeKB(64);
            config.setMaxPoolSize(17);
            config.setMinIdle(0);
            createAndInit();

            // 填满 16 页 = 1MB，刚好达到上限
            List<MemoryPage> pages = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                pages.add(pool.acquire());
            }
            assertEquals(1024 * 1024, pool.getHeapMemoryUsed());

            // 第 17 页应因内存超限抛 PoolExhaustedException
            assertThrows(PoolExhaustedException.class, () -> pool.acquire());
        }

        @Test
        @DisplayName("超上限应抛 PoolExhaustedException，不应 OOM")
        void shouldThrowNotOom() throws Exception {
            config.setMaxDirectMemoryMB(1);
            config.setPageSizeKB(64);
            config.setMaxPoolSize(30);
            config.setMinIdle(0);
            createAndInit();

            // 填满直至超限
            List<MemoryPage> pages = new ArrayList<>();
            PoolExhaustedException ex = null;
            for (int i = 0; i < 30; i++) {
                try {
                    pages.add(pool.acquire());
                } catch (PoolExhaustedException e) {
                    ex = e;
                    break;
                }
            }
            assertNotNull(ex, "应在达到上限时抛 PoolExhaustedException");
        }

        @Test
        @DisplayName("归还后释放内存槽位，可再次借出")
        void shouldAllowReacquireAfterRelease() throws Exception {
            config.setMaxDirectMemoryMB(1);
            config.setPageSizeKB(64);
            config.setMaxPoolSize(17);
            config.setMinIdle(0);
            createAndInit();

            // 填满 16 页
            List<MemoryPage> pages = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                pages.add(pool.acquire());
            }

            // 归还一页
            MemoryPage returned = pages.remove(0);
            pool.release(returned);

            // 应能再借出一页
            MemoryPage page = pool.acquire();
            assertNotNull(page);
        }
    }

    // ==================== 堆外直接内存 ====================

    @Nested
    @DisplayName("堆外直接内存")
    class DirectMemoryTests {

        @Test
        @DisplayName("allocateDirect 应分配指定容量的 DirectByteBuffer")
        void shouldAllocateDirectBuffer() {
            createAndInit();

            ByteBuffer buffer = pool.allocateDirect(4096);
            assertNotNull(buffer);
            assertTrue(buffer.isDirect());
            assertEquals(4096, buffer.capacity());
            assertEquals(4096, pool.getDirectMemoryUsed());
        }

        @Test
        @DisplayName("freeDirect 应扣减直接内存计数器")
        void shouldFreeDirectBuffer() {
            createAndInit();

            ByteBuffer buffer = pool.allocateDirect(8192);
            assertEquals(8192, pool.getDirectMemoryUsed());

            pool.freeDirect(buffer);
            assertEquals(0, pool.getDirectMemoryUsed());
        }

        @Test
        @DisplayName("freeDirect null 应静默处理")
        void shouldHandleNullFreeDirect() {
            createAndInit();
            pool.freeDirect(null); // 不抛异常
        }

        @Test
        @DisplayName("freeDirect 非 direct buffer 应静默处理")
        void shouldHandleNonDirectFreeDirect() {
            createAndInit();
            ByteBuffer heapBuffer = ByteBuffer.allocate(1024);
            pool.freeDirect(heapBuffer); // 不抛异常
        }

        @Test
        @DisplayName("allocateDirect 超总内存上限应抛 PoolExhaustedException")
        void shouldThrowWhenDirectMemoryExceeded() {
            config.setMaxDirectMemoryMB(1); // 1MB total
            createAndInit();

            // 堆内 128KB + 800KB direct = 928KB < 1MB，应该成功
            ByteBuffer buf1 = pool.allocateDirect(800 * 1024);

            // 再分配 200KB：928KB + 200KB = 1128KB > 1MB，应超限
            assertThrows(PoolExhaustedException.class, () -> pool.allocateDirect(200 * 1024));
        }

        @Test
        @DisplayName("allocateDirect 与堆内页共享内存预算")
        void shouldShareBudgetBetweenHeapAndDirect() throws Exception {
            config.setMaxDirectMemoryMB(1);
            config.setPageSizeKB(64);
            config.setMaxPoolSize(20);
            config.setMinIdle(0);
            createAndInit();

            // 分配 8 个堆内页 = 512KB
            List<MemoryPage> pages = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                pages.add(pool.acquire());
            }
            assertEquals(512 * 1024, pool.getHeapMemoryUsed());

            // 还能分配 512KB 的直接内存
            ByteBuffer buf = pool.allocateDirect(512 * 1024);
            assertEquals(512 * 1024, pool.getDirectMemoryUsed());
            assertEquals(1024 * 1024, pool.getTotalMemoryUsed());

            // 超预算
            assertThrows(PoolExhaustedException.class, () -> pool.allocateDirect(1));
        }

        @Test
        @DisplayName("allocateDirect capacity 为 0 或负数应抛 IllegalArgumentException")
        void shouldRejectNonPositiveDirectCapacity() {
            createAndInit();
            assertThrows(IllegalArgumentException.class, () -> pool.allocateDirect(0));
            assertThrows(IllegalArgumentException.class, () -> pool.allocateDirect(-1));
        }
    }

    // ==================== 统计查询 ====================

    @Nested
    @DisplayName("统计查询")
    class StatsTests {

        @Test
        @DisplayName("getTotalMemoryUsed 应为堆内 + 堆外之和")
        void shouldReturnTotalMemoryAsSum() {
            createAndInit();

            long heapOnly = pool.getTotalMemoryUsed();
            assertEquals(pool.getHeapMemoryUsed(), heapOnly);
            assertEquals(0, pool.getDirectMemoryUsed());

            pool.allocateDirect(1024);
            assertEquals(pool.getHeapMemoryUsed() + pool.getDirectMemoryUsed(),
                    pool.getTotalMemoryUsed());
        }

        @Test
        @DisplayName("getMaxMemoryBytes 应返回配置上限的字节数")
        void shouldReturnMaxMemoryInBytes() {
            pool = new MemoryPool(config);
            assertEquals(32L * 1024 * 1024, pool.getMaxMemoryBytes());
        }

        @Test
        @DisplayName("stats 应反映当前池状态")
        void shouldExposeStats() throws Exception {
            createAndInit();

            var stats = pool.stats();
            assertEquals(0, stats.getActiveCount(), "未借出时 activeCount=0");
            assertEquals(2, stats.getIdleCount(), "minIdle=2 个空闲页");

            MemoryPage page = pool.acquire();
            stats = pool.stats();
            assertEquals(1, stats.getActiveCount());
            assertEquals(1, stats.getIdleCount());

            pool.release(page);
            stats = pool.stats();
            assertEquals(0, stats.getActiveCount());
            assertEquals(2, stats.getIdleCount());
        }
    }

    // ==================== 并发 ====================

    @Nested
    @DisplayName("并发")
    class ConcurrencyTests {

        @Test
        @DisplayName("多线程 acquire/release 无死锁无泄露")
        void shouldHandleConcurrentAcquireRelease() throws Exception {
            config.setMaxPoolSize(32);
            config.setMinIdle(8);
            createAndInit();

            int threads = 10;
            int iterations = 100;
            CountDownLatch latch = new CountDownLatch(threads);
            AtomicInteger errors = new AtomicInteger(0);

            for (int t = 0; t < threads; t++) {
                new Thread(() -> {
                    try {
                        for (int i = 0; i < iterations; i++) {
                            MemoryPage page = pool.acquire();
                            byte[] data = page.getData();
                            data[0] = (byte) i;
                            pool.release(page);
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                }).start();
            }

            assertTrue(latch.await(30, TimeUnit.SECONDS), "所有线程应在 30 秒内完成");
            assertEquals(0, errors.get(), "不应有异常");

            var stats = pool.stats();
            assertEquals(0, stats.getActiveCount(), "activeCount 应为 0");
        }

        @Test
        @DisplayName("多线程 allocateDirect/freeDirect 无竞态")
        void shouldHandleConcurrentDirectAllocation() throws Exception {
            createAndInit();

            int threads = 4;
            CountDownLatch latch = new CountDownLatch(threads);
            AtomicInteger errors = new AtomicInteger(0);

            for (int t = 0; t < threads; t++) {
                new Thread(() -> {
                    try {
                        List<ByteBuffer> buffers = new ArrayList<>();
                        for (int i = 0; i < 50 && buffers.size() < 10; i++) {
                            try {
                                ByteBuffer buf = pool.allocateDirect(1024);
                                buffers.add(buf);
                            } catch (PoolExhaustedException e) {
                                break;
                            }
                        }
                        for (ByteBuffer buf : buffers) {
                            pool.freeDirect(buf);
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                }).start();
            }

            assertTrue(latch.await(30, TimeUnit.SECONDS));
            assertEquals(0, errors.get(), "不应有异常");
            assertEquals(0, pool.getDirectMemoryUsed(), "最终直接内存用量应为 0");
        }
    }

    // ==================== 销毁与重建 ====================

    @Nested
    @DisplayName("销毁与重建")
    class DestroyAndReinitTests {

        @Test
        @DisplayName("destroy 应释放所有堆内内存")
        void shouldReleaseAllHeapMemoryOnDestroy() throws Exception {
            createAndInit();

            // 填满池（用超时避免死锁）
            List<MemoryPage> pages = new ArrayList<>();
            for (int i = 0; i < config.getMaxPoolSize(); i++) {
                pages.add(quickAcquire());
            }

            long beforeDestroy = pool.getHeapMemoryUsed();
            assertTrue(beforeDestroy > 0);

            pool.destroy();
            assertEquals(0, pool.getHeapMemoryUsed(), "destroy 后堆内存应为 0");
        }

        @Test
        @DisplayName("destroy 后直接内存计数器不变（Cleaner 异步回收）")
        void shouldNotAffectDirectCounterOnDestroy() {
            createAndInit();
            pool.allocateDirect(4096);
            assertEquals(4096, pool.getDirectMemoryUsed());

            pool.destroy();
            // 直接内存由 Cleaner 异步回收，计数器不归零（调用方自行 freeDirect）
            assertEquals(4096, pool.getDirectMemoryUsed());
        }

        @Test
        @DisplayName("重复 destroy 应幂等")
        void shouldBeIdempotentDestroy() {
            createAndInit();
            pool.destroy();
            pool.destroy(); // 不抛异常
        }
    }
}
