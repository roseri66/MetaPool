package com.metapool.adapter.pool2;

import com.metapool.common.capability.Pool;
import com.metapool.common.exception.MetaPoolConfigException;
import com.metapool.common.exception.MetaPoolException;
import com.metapool.common.exception.PoolExhaustedException;
import com.metapool.common.resource.ManagedResource;
import com.metapool.common.resource.Tunable;
import com.metapool.common.spi.ResourceDefinition;
import com.metapool.common.stats.HealthStatus;
import com.metapool.common.stats.PoolStats;
import com.metapool.common.stats.TuneResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2.1 P1-③ 验收：Commons Pool2 通用对象池纳入治理。
 *
 * <p>本模块的测试<b>不需要 Docker</b>——用内存对象工厂即可，任何环境都全跑。
 */
class CommonsPool2AdapterTest {

    private static final long AWAIT_SECONDS = 5;

    @BeforeEach
    void resetFactoryCounters() {
        CountingObjectFactory.reset();
    }

    private static CommonsPool2Adapter<Object> aPool(String name) {
        return CommonsPool2Adapter.builder()
                .named(name).factory(new CountingObjectFactory())
                .maxTotal(4).maxIdle(4).minIdle(0)
                .maxWait(Duration.ofMillis(200))
                .build();
    }

    // ==================== 能力隔离 ====================

    /** 对象池是**真·池**：它<b>应当</b>实现 Pool —— 与 bucket4j / executor / lock 的反例互为对照。 */
    @Test
    void objectPool_isARealPool_andIsTunable() {
        ManagedResource r = aPool("buf");
        assertTrue(r instanceof Pool, "通用对象池有 borrow/release 语义，应实现 Pool");
        assertTrue(r instanceof Tunable, "池容量运行时可调，应实现 Tunable");
        assertEquals("object", r.type());
    }

    // ==================== 生命周期 ====================

    @Test
    void lifecycle_borrowAndRelease() throws Exception {
        CommonsPool2Adapter<Object> pool = aPool("buf");
        assertEquals(HealthStatus.Status.DOWN, pool.health().status());

        pool.start();
        assertEquals(HealthStatus.Status.UP, pool.health().status());

        Object a = pool.borrow();
        assertNotNull(a);
        assertEquals(1, pool.poolStats().active());
        assertEquals(1, pool.poolStats().totalBorrowed());

        pool.release(a);
        assertEquals(0, pool.poolStats().active());
        assertEquals(1, pool.poolStats().idle(), "归还后应回到空闲区");
        assertEquals(1, pool.poolStats().totalReleased());

        pool.stop(Duration.ofSeconds(1));
        assertEquals(HealthStatus.Status.DOWN, pool.health().status());
    }

    /** 归还的对象应被复用，而不是每次都造新的——池化的意义就在这。 */
    @Test
    void releasedObject_isReused() throws Exception {
        CommonsPool2Adapter<Object> pool = aPool("buf");
        pool.start();
        try {
            Object first = pool.borrow();
            pool.release(first);
            Object second = pool.borrow();
            assertEquals(first, second, "应复用同一个对象");
            assertEquals(1, CountingObjectFactory.CREATED.get(), "只应创建过 1 个对象");
            pool.release(second);
        } finally {
            pool.stop(Duration.ZERO);
        }
    }

    /** 坑 P-01：stop() 必须置空底层字段，否则重启复用的是已关闭的池。 */
    @Test
    void restart_afterStop_yieldsUsablePool() throws Exception {
        CommonsPool2Adapter<Object> pool = aPool("buf");
        pool.start();
        pool.release(pool.borrow());
        pool.stop(Duration.ZERO);
        assertEquals(HealthStatus.Status.DOWN, pool.health().status());

        pool.start();
        assertEquals(HealthStatus.Status.UP, pool.health().status());
        assertNotNull(pool.borrow(), "重启后应能正常借出");
        pool.stop(Duration.ZERO);
    }

    /** RULES §3.5 / 坑 P-09：生命周期方法必须 synchronized。 */
    @Test
    void lifecycleMethods_areSynchronized_perRules() throws Exception {
        assertTrue(Modifier.isSynchronized(
                CommonsPool2Adapter.class.getMethod("start").getModifiers()),
                "start() 必须 synchronized（RULES §3.5）");
        assertTrue(Modifier.isSynchronized(
                CommonsPool2Adapter.class.getMethod("stop", Duration.class).getModifiers()),
                "stop() 必须 synchronized（RULES §3.5）");
    }

    @Test
    void stop_isIdempotent_andSafeWhenNeverStarted() {
        CommonsPool2Adapter<Object> pool = aPool("buf");
        pool.stop(Duration.ZERO);   // 从未启动
        pool.start();
        pool.stop(Duration.ZERO);
        pool.stop(Duration.ZERO);   // 重复
        assertEquals(HealthStatus.Status.DOWN, pool.health().status());
    }

    /**
     * 优雅停机会在 graceful 期内等待借出对象归还。
     *
     * <p>{@code GenericObjectPool.close()} 自己<b>不等</b>借出对象，所以 drain 必须由适配器做 ——
     * 直接 close 等于把在用对象连同池一起丢掉（1.0 的 {@code destroy()} 强杀老毛病）。
     */
    @Test
    void stop_graceful_waitsForBorrowedObjects() throws Exception {
        CommonsPool2Adapter<Object> pool = aPool("buf");
        pool.start();

        Object borrowed = pool.borrow();
        CountDownLatch returned = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try {
                Thread.sleep(300);          // 模拟仍在使用
                pool.release(borrowed);
                returned.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        holder.start();

        pool.stop(Duration.ofSeconds(AWAIT_SECONDS));   // 应等到归还才关
        assertTrue(returned.await(AWAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals(1, pool.poolStats().totalReleased(), "graceful 期内的归还应被记账");
    }

    @Test
    void useBeforeStart_throwsMetaPoolException() {
        CommonsPool2Adapter<Object> pool = aPool("buf");
        assertThrows(MetaPoolException.class, pool::borrow,
                "未启动是 MetaPool 自己的错误语义（RULES §3.2 反向边界）");
    }

    // ==================== 耗尽与超时 ====================

    /**
     * 🎯 <b>本适配器与 HikariAdapter 的关键差别</b>：{@code borrow(Duration)} 是<b>真超时</b>。
     *
     * <p>Hikari 的获取超时只能由配置项 {@code connectionTimeout} 统一治理，逐次传入的
     * {@code timeout} 仅作提示；Commons Pool2 原生支持 {@code borrowObject(Duration)}，
     * 所以这里确实按传入值限时。用「配置 200ms、调用传 1500ms，实测耗时超过 1s」来坐实
     * 生效的是参数而不是配置。
     */
    @Test
    void borrowWithTimeout_honoursThePassedDuration_notTheConfiguredOne() throws Exception {
        CommonsPool2Adapter<Object> pool = CommonsPool2Adapter.builder()
                .named("tiny").factory(new CountingObjectFactory())
                .maxTotal(1).maxIdle(1)
                .maxWait(Duration.ofMillis(200))     // 配置里是 200ms
                .build();
        pool.start();
        try {
            Object held = pool.borrow();             // 占满（maxTotal=1）
            long start = System.nanoTime();
            assertThrows(PoolExhaustedException.class,
                    () -> pool.borrow(Duration.ofMillis(1500)),
                    "耗尽且超时应映射为 PoolExhaustedException");
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            // 只断言「明显超过配置值」，不断言精确耗时（挂钟精确断言会假失败，见坑 P-17）
            assertTrue(elapsedMs > 1000,
                    "生效的应是调用传入的 1500ms 而非配置的 200ms，实测等了 " + elapsedMs + "ms");
            pool.release(held);
        } finally {
            pool.stop(Duration.ZERO);
        }
    }

    /** 不传超时的 borrow() 走配置的 max-wait；耗尽同样映射为 PoolExhaustedException。 */
    @Test
    void borrowWithoutTimeout_usesConfiguredMaxWait() throws Exception {
        CommonsPool2Adapter<Object> pool = CommonsPool2Adapter.builder()
                .named("tiny").factory(new CountingObjectFactory())
                .maxTotal(1).maxWait(Duration.ofMillis(150))
                .build();
        pool.start();
        try {
            Object held = pool.borrow();
            assertThrows(PoolExhaustedException.class, pool::borrow);
            pool.release(held);
        } finally {
            pool.stop(Duration.ZERO);
        }
    }

    /** 耗尽且有人在排队 → DEGRADED。仅「借满」不算故障（那正是池在满负荷工作）。 */
    @Test
    void health_isDegraded_whenExhaustedAndSomeoneIsWaiting() throws Exception {
        CommonsPool2Adapter<Object> pool = CommonsPool2Adapter.builder()
                .named("tiny").factory(new CountingObjectFactory())
                .maxTotal(1).maxWait(Duration.ofSeconds(3))
                .build();
        pool.start();
        try {
            Object held = pool.borrow();
            assertEquals(HealthStatus.Status.UP, pool.health().status(),
                    "只是借满、没人排队，不算降级");

            CountDownLatch waiting = new CountDownLatch(1);
            AtomicReference<HealthStatus.Status> observed = new AtomicReference<>();
            Thread waiter = new Thread(() -> {
                try {
                    waiting.countDown();
                    pool.borrow(Duration.ofSeconds(2));   // 排队等待
                } catch (Exception ignored) {
                    // 超时即可，本用例只关心「排队期间」的健康状态
                }
            });
            waiter.start();
            assertTrue(waiting.await(AWAIT_SECONDS, TimeUnit.SECONDS));

            // 等到 Pool2 确实登记了等待者再断言，避免与线程启动竞速（P-17：不用 sleep 猜时间）
            long deadline = System.nanoTime() + Duration.ofSeconds(AWAIT_SECONDS).toNanos();
            while (System.nanoTime() < deadline) {
                if (pool.poolStats().pending() > 0) {
                    observed.set(pool.health().status());
                    break;
                }
                Thread.onSpinWait();
            }
            assertEquals(HealthStatus.Status.DEGRADED, observed.get(),
                    "耗尽 + 有人排队应报 DEGRADED");
            waiter.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS));
            pool.release(held);
        } finally {
            pool.stop(Duration.ZERO);
        }
    }

    // ==================== 归还异常路径 ====================

    /**
     * 契约：归还非本池或已归还的对象<b>静默忽略并记 WARN</b>。
     *
     * <p>Commons Pool2 这时抛 {@code IllegalStateException}，放任它冒出去会在使用方的
     * {@code finally} 里掩盖业务的原始异常。
     */
    @Test
    void release_ofForeignOrAlreadyReturnedObject_isSilentlyIgnored() throws Exception {
        CommonsPool2Adapter<Object> pool = aPool("buf");
        pool.start();
        try {
            pool.release("not-from-this-pool");     // 不得抛
            assertEquals(0, pool.poolStats().totalReleased(), "非本池对象不计入归还");

            Object a = pool.borrow();
            pool.release(a);
            pool.release(a);                        // 重复归还，不得抛
            assertEquals(1, pool.poolStats().totalReleased(), "重复归还不重复计数");

            pool.release(null);                     // null 也不得抛
        } finally {
            pool.stop(Duration.ZERO);
        }
    }

    // ==================== 指标 ====================

    @Test
    void metrics_unifiedTags() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CommonsPool2Adapter<Object> pool = aPool("buf");
        pool.bindTo(registry);
        pool.start();
        try {
            Object a = pool.borrow();
            assertEquals(1.0, registry.get("metapool.object.active")
                    .tag("metapool.resource", "buf")
                    .tag("metapool.type", "object").gauge().value());
            assertEquals(1.0, registry.get("metapool.object.borrowed.total")
                    .tag("metapool.resource", "buf").functionCounter().count());
            pool.release(a);
            assertNotNull(registry.find("metapool.object.idle")
                    .tag("metapool.resource", "buf").gauge());
            assertNotNull(registry.find("metapool.object.pending")
                    .tag("metapool.resource", "buf").gauge());
            assertEquals(1.0, registry.get("metapool.object.released.total")
                    .tag("metapool.resource", "buf").functionCounter().count());
        } finally {
            pool.stop(Duration.ZERO);
        }
    }

    /** 停机后读指标不得抛异常，应读到 0（采样网关兜底）。 */
    @Test
    void metrics_readableAfterStop_returnZero() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CommonsPool2Adapter<Object> pool = aPool("buf");
        pool.bindTo(registry);
        pool.start();
        pool.stop(Duration.ZERO);

        assertEquals(0.0, registry.get("metapool.object.active")
                .tag("metapool.resource", "buf").gauge().value());
    }

    // ==================== 动态调参 ====================

    @Test
    void tune_changesCapacityAtRuntime() throws Exception {
        CommonsPool2Adapter<Object> pool = CommonsPool2Adapter.builder()
                .named("buf").factory(new CountingObjectFactory())
                .maxTotal(1).maxWait(Duration.ofMillis(100))
                .build();
        pool.start();
        try {
            Object first = pool.borrow();
            assertThrows(PoolExhaustedException.class, pool::borrow, "maxTotal=1，第二次应耗尽");

            TuneResult r = pool.apply(Map.of("max-total", 4));
            assertTrue(r.success(), "rejected=" + r.rejected());
            assertNotNull(pool.borrow(), "调大后应能继续借出");
            pool.release(first);
        } finally {
            pool.stop(Duration.ZERO);
        }
    }

    /**
     * 坑 P-15：{@code apply()} 必须同时更新「运行中的池」和「用于重建的配置」，
     * 否则 stop→start 后调参结果静默回退（HikariAdapter 踩过）。
     */
    @Test
    void tune_survivesRestart_perPitfallP15() throws Exception {
        CommonsPool2Adapter<Object> pool = aPool("buf");
        pool.start();
        try {
            assertTrue(pool.apply(Map.of("max-total", 9, "min-idle", 2)).success());
            pool.stop(Duration.ZERO);
            pool.start();   // 重建池

            // 借满 9 个仍应成功 —— 说明重建用的是调参后的值
            Object[] held = new Object[9];
            for (int i = 0; i < 9; i++) {
                held[i] = pool.borrow();
            }
            assertEquals(9, pool.poolStats().active(), "重启后 max-total 不得丢失（P-15）");
            for (Object o : held) {
                pool.release(o);
            }
        } finally {
            pool.stop(Duration.ZERO);
        }
    }

    /**
     * 与 jdk-executor 的 P-19 对照：Commons Pool2 的 setter <b>互不校验</b>，
     * {@code minIdle > maxIdle} 也照单全收（行为上取小），因此本适配器<b>不需要</b>按方向排序。
     *
     * <p>这条断言就是那个「不需要排序」判断的实测依据 —— 不凭印象。
     */
    @Test
    void tune_minIdleAboveMaxIdle_isAcceptedByPool2_noOrderingHazard() {
        CommonsPool2Adapter<Object> pool = aPool("buf");
        pool.start();
        try {
            TuneResult r = pool.apply(Map.of("min-idle", 8, "max-idle", 2));
            assertTrue(r.success(),
                    "Pool2 不做交叉校验，min>max 也应被接受（对照 P-19：executor 的 setter 会抛）");
            assertEquals(Set.of("min-idle", "max-idle"), r.applied());
        } finally {
            pool.stop(Duration.ZERO);
        }
    }

    @Test
    void tune_rejectsNonWhitelistedAndInvalidValues() {
        CommonsPool2Adapter<Object> pool = aPool("buf");
        pool.start();
        try {
            TuneResult notWhitelisted = pool.apply(Map.of("factory-class", "x"));
            assertFalse(notWhitelisted.success());
            assertTrue(notWhitelisted.rejected().containsKey("factory-class"));

            assertFalse(pool.apply(Map.of("max-total", "abc")).success(), "非数字应被拒");
            assertFalse(pool.apply(Map.of("max-total", 0)).success(), "0 几乎总是配置事故，应被拒");
            assertFalse(pool.apply(Map.of("min-idle", -1)).success(), "min-idle 不得为负");
        } finally {
            pool.stop(Duration.ZERO);
        }
    }

    @Test
    void tune_beforeStart_isRejected() {
        CommonsPool2Adapter<Object> pool = aPool("buf");
        TuneResult r = pool.apply(Map.of("max-total", 4));
        assertFalse(r.success());
        assertEquals("resource not started", r.rejected().get("max-total"));
    }

    /** 坑 P-13：不支持的 tunable key 必须构建期就报。 */
    @Test
    void unsupportedTunableKey_failsFastAtBuildTime() {
        MetaPoolConfigException e = assertThrows(MetaPoolConfigException.class,
                () -> CommonsPool2Adapter.builder().named("buf")
                        .factory(new CountingObjectFactory())
                        .tunable(Set.of("factory-class"))
                        .build());
        assertTrue(e.getMessage().contains("factory-class"), e.getMessage());
    }

    // ==================== 工厂（含 factory-class 反射路径） ====================

    @Test
    void factory_viaResourceDefinition_instantiatesFactoryClass() throws Exception {
        CommonsPool2AdapterFactory f = new CommonsPool2AdapterFactory();
        assertEquals("object", f.supportedType());

        Map<String, Object> props = new HashMap<>();
        props.put("factory-class", CountingObjectFactory.class.getName());
        props.put("max-total", 3);
        props.put("max-idle", 3);
        props.put("min-idle", 1);
        props.put("max-wait", "500ms");
        var def = new ResourceDefinition("buf", "object", props, Set.of("max-total"));

        ManagedResource r = f.create(def);
        assertEquals("buf", r.name());
        r.start();
        try {
            @SuppressWarnings("unchecked")
            Pool<Object> pool = (Pool<Object>) r;
            Object o = pool.borrow();
            assertNotNull(o, "反射实例化出来的工厂应真的能造对象");
            assertTrue(CountingObjectFactory.CREATED.get() >= 1);
            pool.release(o);
            PoolStats stats = pool.poolStats();
            assertEquals(1, stats.totalBorrowed());
            assertEquals(1, stats.totalReleased());
        } finally {
            r.stop(Duration.ZERO);
        }
    }

    /** 三项 fail-fast 校验：类不存在 / 不是 PooledObjectFactory / 没有无参构造。 */
    @Test
    void factory_classValidation_failsFastWithActionableMessages() {
        CommonsPool2AdapterFactory f = new CommonsPool2AdapterFactory();

        MetaPoolConfigException missing = assertThrows(MetaPoolConfigException.class, () -> f.create(
                new ResourceDefinition("buf", "object", Map.of(), Set.of())));
        assertTrue(missing.getMessage().contains("factory-class"),
                "错误消息要说清缺什么、以及可以改用编程式：" + missing.getMessage());

        MetaPoolConfigException notFound = assertThrows(MetaPoolConfigException.class, () -> f.create(
                new ResourceDefinition("buf", "object",
                        Map.of("factory-class", "com.nope.NotThere"), Set.of())));
        assertTrue(notFound.getMessage().contains("not found"), notFound.getMessage());

        MetaPoolConfigException wrongType = assertThrows(MetaPoolConfigException.class, () -> f.create(
                new ResourceDefinition("buf", "object",
                        Map.of("factory-class", String.class.getName()), Set.of())));
        assertTrue(wrongType.getMessage().contains("does not implement"), wrongType.getMessage());

        MetaPoolConfigException noCtor = assertThrows(MetaPoolConfigException.class, () -> f.create(
                new ResourceDefinition("buf", "object",
                        Map.of("factory-class", NeedsArgsFactory.class.getName()), Set.of())));
        assertTrue(noCtor.getMessage().contains("no-arg constructor"), noCtor.getMessage());
    }

    @Test
    void factory_invalidNumbers_failFast() {
        CommonsPool2AdapterFactory f = new CommonsPool2AdapterFactory();
        assertThrows(MetaPoolConfigException.class, () -> f.create(
                new ResourceDefinition("buf", "object",
                        Map.of("factory-class", CountingObjectFactory.class.getName(),
                                "max-total", "abc"), Set.of())));
        assertThrows(MetaPoolConfigException.class, () -> f.create(
                new ResourceDefinition("buf", "object",
                        Map.of("factory-class", CountingObjectFactory.class.getName(),
                                "max-wait", "soon"), Set.of())));
        assertThrows(MetaPoolConfigException.class, () -> f.create(
                new ResourceDefinition("buf", "datasource",
                        Map.of("factory-class", CountingObjectFactory.class.getName()), Set.of())),
                "类型不匹配应报错");
    }


    /** 编程式路径缺 factory 必须构建期就报，且消息要解释「为什么必须给」。 */
    @Test
    void builder_withoutFactory_failsFastWithExplanation() {
        MetaPoolConfigException e = assertThrows(MetaPoolConfigException.class,
                () -> CommonsPool2Adapter.builder().named("buf").build());
        assertTrue(e.getMessage().contains("PooledObjectFactory"), e.getMessage());
    }

    @Test
    void factory_isDiscoverableViaServiceLoader() {
        boolean found = java.util.ServiceLoader.load(
                        com.metapool.common.spi.ResourceAdapterFactory.class).stream()
                .map(java.util.ServiceLoader.Provider::get)
                .anyMatch(x -> "object".equals(x.supportedType()));
        assertTrue(found, "META-INF/services 未正确注册 CommonsPool2AdapterFactory");
    }

    /** 只为「没有无参构造」这条校验存在。 */
    static class NeedsArgsFactory extends org.apache.commons.pool2.BasePooledObjectFactory<Object> {
        NeedsArgsFactory(String required) {
            // 故意只有带参构造
        }

        @Override
        public Object create() {
            return new Object();
        }

        @Override
        public org.apache.commons.pool2.PooledObject<Object> wrap(Object obj) {
            return new org.apache.commons.pool2.impl.DefaultPooledObject<>(obj);
        }
    }
}
