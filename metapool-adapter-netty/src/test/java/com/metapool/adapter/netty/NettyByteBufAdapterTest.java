package com.metapool.adapter.netty;

import com.metapool.common.capability.Pool;
import com.metapool.common.exception.MetaPoolConfigException;
import com.metapool.common.exception.MetaPoolException;
import com.metapool.common.resource.ManagedResource;
import com.metapool.common.resource.Tunable;
import com.metapool.common.spi.ResourceDefinition;
import com.metapool.common.stats.HealthStatus;
import com.metapool.common.stats.PoolStats;
import com.metapool.common.stats.TuneResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2.4 验收：Netty 池化堆外内存纳入治理。测试全在内存里跑，不需要 Docker。
 *
 * <p>重点不在「能不能借到 buf」，而在<b>引用计数语义与普通池的差别有没有被如实对待</b>。
 */
class NettyByteBufAdapterTest {

    private static NettyByteBufAdapter aMemory(String name) {
        return NettyByteBufAdapter.builder()
                .named(name).preferDirect(true).defaultCapacity(256).maxCapacity(64 * 1024)
                .build();
    }

    // ==================== 能力落点 ====================

    /**
     * 它<b>实现</b> {@code Pool}，与 lettuce 适配器相反。
     *
     * <p>判据：Netty <b>确实存在</b> borrow/release 这个动作，只是语义更强（带引用计数）；
     * 而 Lettuce 的单连接多路复用<b>根本没有</b>这个动作。
     * <b>「语义更强」和「语义不存在」是两回事</b> —— 前者可映射并注明，后者只能靠撒谎。
     */
    @Test
    void byteBufPool_isAPool_unlikeTheLettuceAdapter() {
        ManagedResource m = aMemory("buf");
        assertTrue(m instanceof Pool, "Netty 确实有 borrow/release 动作，应实现 Pool");
        assertTrue(m instanceof Tunable, "容量运行时可调，应实现 Tunable");
        assertEquals("memory", m.type());
    }

    // ==================== 生命周期 ====================

    @Test
    void lifecycle_borrowAndRelease() {
        NettyByteBufAdapter m = aMemory("buf");
        assertEquals(HealthStatus.Status.DOWN, m.health().status());

        m.start();
        assertEquals(HealthStatus.Status.UP, m.health().status());

        ByteBuf buf = m.borrow();
        assertNotNull(buf);
        assertEquals(256, buf.capacity());
        assertEquals(1, buf.refCnt());
        assertEquals(1, m.poolStats().active());
        assertEquals(1, m.poolStats().totalBorrowed());

        m.release(buf);
        assertEquals(0, buf.refCnt(), "release 后引用计数归零");
        assertEquals(0, m.poolStats().active());
        assertEquals(1, m.poolStats().totalReleased());

        m.stop(Duration.ZERO);
        assertEquals(HealthStatus.Status.DOWN, m.health().status());
    }

    /**
     * 🎯 <b>本适配器最该讲清的一条：release 是「引用计数减一」，不是「还给池」。</b>
     *
     * <p>{@code retain()} 过的 buf 需要对应次数的 release 才真正回池 ——
     * 这是它与 HikariCP / Commons Pool2 的根本差别，写成测试以免被当成普通池对待。
     */
    @Test
    void release_decrementsRefCount_itDoesNotSimplyReturnToPool() {
        NettyByteBufAdapter m = aMemory("buf");
        m.start();
        try {
            ByteBuf buf = m.borrow();
            buf.retain();                       // 别处也持有了一份
            assertEquals(2, buf.refCnt());

            m.release(buf);
            assertEquals(1, buf.refCnt(), "减到 1，内存【尚未】回池");
            assertTrue(buf.isReadable() || buf.capacity() > 0, "仍然可用");

            m.release(buf);
            assertEquals(0, buf.refCnt(), "第二次才真正归还");
        } finally {
            m.stop(Duration.ZERO);
        }
    }

    /** 契约要求：归还 null 或已完全释放的 buf 静默忽略并记 WARN，不得抛。 */
    @Test
    void release_ofNullOrAlreadyReleasedBuffer_isSilentlyIgnored() {
        NettyByteBufAdapter m = aMemory("buf");
        m.start();
        try {
            m.release(null);                     // 不得抛

            ByteBuf buf = m.borrow();
            m.release(buf);
            long releasedAfterFirst = m.poolStats().totalReleased();

            m.release(buf);                      // refCnt 已为 0，不得抛
            assertEquals(releasedAfterFirst, m.poolStats().totalReleased(),
                    "重复归还不重复计数（否则 released 会虚高，掩盖真实泄漏）");
        } finally {
            m.stop(Duration.ZERO);
        }
    }

    /**
     * 停机时若仍有未释放的 buf：<b>记为泄漏但绝不替调用方 release</b>。
     *
     * <p>那块内存可能正被别处使用（{@code retain()} 过），强行释放会造成 use-after-free，
     * <b>比泄漏更危险</b>。
     */
    @Test
    void stop_countsLeaksButNeverReleasesOnYourBehalf() {
        NettyByteBufAdapter m = aMemory("buf");
        m.start();
        ByteBuf leaked = m.borrow();

        m.stop(Duration.ZERO);

        assertEquals(1, leaked.refCnt(), "停机不得替调用方释放 —— 那块内存可能还在被用");
        leaked.release();   // 测试自己收拾干净
    }

    @Test
    void useBeforeStart_throwsMetaPoolException() {
        NettyByteBufAdapter m = aMemory("buf");
        assertThrows(MetaPoolException.class, m::borrow);
        assertThrows(MetaPoolException.class, m::unwrap);
    }

    /** 坑 P-01：stop 后必须能重启。 */
    @Test
    void restart_afterStop_yieldsUsableAllocator() {
        NettyByteBufAdapter m = aMemory("buf");
        m.start();
        m.release(m.borrow());
        m.stop(Duration.ZERO);
        assertEquals(HealthStatus.Status.DOWN, m.health().status());

        m.start();
        assertEquals(HealthStatus.Status.UP, m.health().status());
        ByteBuf buf = m.borrow();
        assertNotNull(buf);
        m.release(buf);
        m.stop(Duration.ZERO);
    }

    /** RULES §3.5 / 坑 P-09。 */
    @Test
    void lifecycleMethods_areSynchronized_perRules() throws Exception {
        assertTrue(Modifier.isSynchronized(NettyByteBufAdapter.class.getMethod("start").getModifiers()));
        assertTrue(Modifier.isSynchronized(
                NettyByteBufAdapter.class.getMethod("stop", Duration.class).getModifiers()));
    }

    @Test
    void stop_isIdempotent_andSafeWhenNeverStarted() {
        NettyByteBufAdapter m = aMemory("buf");
        m.stop(Duration.ZERO);
        m.start();
        m.stop(Duration.ZERO);
        m.stop(Duration.ZERO);
        assertEquals(HealthStatus.Status.DOWN, m.health().status());
    }

    /**
     * 健康<b>只有两态</b>，刻意没有 DEGRADED。
     *
     * <p>内存分配器不存在「饱和但仍在工作」这个中间态 —— 要么分配成功，要么直接 OOM。
     * 为了和别的适配器"看起来一致"而硬造三态，就是在治理面上制造假象。
     */
    @Test
    void health_hasNoDegradedState_becauseAllocationEitherSucceedsOrThrows() {
        NettyByteBufAdapter m = aMemory("buf");
        m.start();
        try {
            for (int i = 0; i < 50; i++) {
                m.borrow();                      // 故意只借不还
            }
            assertEquals(HealthStatus.Status.UP, m.health().status(),
                    "大量未释放不该被伪装成 DEGRADED —— 泄漏该看指标，不该看健康状态");
            assertEquals(50, m.poolStats().active());
        } finally {
            m.stop(Duration.ZERO);
        }
    }

    // ==================== borrow(Duration) 的第三种语义 ====================

    /**
     * {@code borrow(Duration)} 在本适配器上<b>无等待语义，参数被忽略</b>。
     *
     * <p>内存分配不排队——要么立刻成功，要么直接抛 OOM，不存在「等一会儿就有了」。
     * 至此本项目的 {@code borrow(Duration)} 有三种语义强度（Pool2 真超时 / Hikari 以配置为界 /
     * 本适配器忽略），三边 javadoc 均已写明。
     */
    @Test
    void borrowWithTimeout_ignoresTheTimeout_becauseAllocationNeverQueues() {
        NettyByteBufAdapter m = aMemory("buf");
        m.start();
        try {
            long start = System.nanoTime();
            ByteBuf buf = m.borrow(Duration.ofSeconds(30));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertNotNull(buf);
            assertTrue(elapsedMs < 1000, "不该真的去等，实测 " + elapsedMs + "ms");
            m.release(buf);
        } finally {
            m.stop(Duration.ZERO);
        }
    }

    // ==================== 统计口径 ====================

    /** {@code idle} / {@code pending} 恒为 0 —— 这是刻意的，用测试钉住以免有人去"补全"。 */
    @Test
    void poolStats_idleAndPendingAreAlwaysZero_byDesign() {
        NettyByteBufAdapter m = aMemory("buf");
        m.start();
        try {
            ByteBuf b = m.borrow();
            PoolStats s = m.poolStats();
            assertEquals(0, s.idle(), "Netty 的池按 arena/chunk 组织，没有可数的空闲对象");
            assertEquals(0, s.pending(), "分配不排队，不存在等待者");
            assertEquals(1, s.active());
            m.release(b);
        } finally {
            m.stop(Duration.ZERO);
        }
    }

    // ==================== 指标 ====================

    @Test
    void metrics_unifiedTags_andLeakSignalIsExposed() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        NettyByteBufAdapter m = aMemory("buf");
        m.bindTo(registry);
        m.start();
        try {
            ByteBuf a = m.borrow();
            m.borrow();                          // 故意漏一个
            m.release(a);

            assertEquals(2.0, registry.get("metapool.memory.allocated.total")
                    .tag("metapool.resource", "buf")
                    .tag("metapool.type", "memory").functionCounter().count());
            assertEquals(1.0, registry.get("metapool.memory.released.total")
                    .tag("metapool.resource", "buf").functionCounter().count());
            // 🎯 头牌信号：allocated 与 released 分叉即泄漏
            assertEquals(1.0, registry.get("metapool.memory.outstanding")
                    .tag("metapool.resource", "buf").gauge().value());
            assertNotNull(registry.find("metapool.memory.used.direct.bytes")
                    .tag("metapool.resource", "buf").gauge());
            assertNotNull(registry.find("metapool.memory.leaked.total")
                    .tag("metapool.resource", "buf").functionCounter());
        } finally {
            m.stop(Duration.ZERO);
        }
    }

    /** 停机时把未释放的计入 leaked —— 这条曲线是泄漏的最终裁决。 */
    @Test
    void stop_recordsOutstandingAsLeaked() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        NettyByteBufAdapter m = aMemory("buf");
        m.bindTo(registry);
        m.start();
        ByteBuf leaked = m.borrow();

        m.stop(Duration.ZERO);

        assertEquals(1.0, registry.get("metapool.memory.leaked.total")
                .tag("metapool.resource", "buf").functionCounter().count());
        leaked.release();
    }

    /** 停机后读指标不得抛异常，应读到 0。 */
    @Test
    void metrics_readableAfterStop_returnZero() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        NettyByteBufAdapter m = aMemory("buf");
        m.bindTo(registry);
        m.start();
        m.stop(Duration.ZERO);

        assertEquals(0.0, registry.get("metapool.memory.used.direct.bytes")
                .tag("metapool.resource", "buf").gauge().value());
    }

    // ==================== 动态调参 ====================

    @Test
    void tune_changesCapacityAtRuntime() {
        NettyByteBufAdapter m = aMemory("buf");
        m.start();
        try {
            assertTrue(m.apply(Map.of("default-capacity", 1024)).success());
            assertEquals(1024, m.defaultCapacity());
            ByteBuf b = m.borrow();
            assertEquals(1024, b.capacity(), "调参后新借出的 buf 应用新容量");
            m.release(b);
        } finally {
            m.stop(Duration.ZERO);
        }
    }

    /** 对照 P-19：两个值互相约束，非法组合<b>整组拒绝</b>，不留半成品配置。 */
    @Test
    void tune_maxBelowDefault_isRejectedWholesale() {
        NettyByteBufAdapter m = aMemory("buf");
        m.start();
        try {
            TuneResult r = m.apply(Map.of("max-capacity", 64));   // 当前 default=256
            assertFalse(r.success());
            assertTrue(r.rejected().containsKey("max-capacity"), r.rejected().toString());
            assertEquals(64 * 1024, m.maxCapacity(), "被拒时不得改动任何一个值");
            assertEquals(256, m.defaultCapacity());
        } finally {
            m.stop(Duration.ZERO);
        }
    }

    @Test
    void tune_rejectsNonWhitelistedAndInvalidValues() {
        NettyByteBufAdapter m = aMemory("buf");
        m.start();
        try {
            TuneResult notWhitelisted = m.apply(Map.of("prefer-direct", "false"));
            assertFalse(notWhitelisted.success());
            assertTrue(notWhitelisted.rejected().containsKey("prefer-direct"),
                    "prefer-direct 是构造期设置，运行时改不了，不该进白名单");

            assertFalse(m.apply(Map.of("default-capacity", 0)).success());
            assertFalse(m.apply(Map.of("default-capacity", "abc")).success());
        } finally {
            m.stop(Duration.ZERO);
        }
    }

    @Test
    void tune_beforeStart_isRejected() {
        NettyByteBufAdapter m = aMemory("buf");
        TuneResult r = m.apply(Map.of("default-capacity", 512));
        assertFalse(r.success());
        assertEquals("resource not started", r.rejected().get("default-capacity"));
    }

    /** 坑 P-13：不支持的 tunable key 构建期就报。 */
    @Test
    void unsupportedTunableKey_failsFastAtBuildTime() {
        MetaPoolConfigException e = assertThrows(MetaPoolConfigException.class,
                () -> NettyByteBufAdapter.builder().named("buf")
                        .tunable(Set.of("prefer-direct")).build());
        assertTrue(e.getMessage().contains("prefer-direct"), e.getMessage());
    }

    // ==================== 配置校验与工厂 ====================

    @Test
    void invalidConfig_failsFastAtBuildTime() {
        assertThrows(MetaPoolConfigException.class,
                () -> NettyByteBufAdapter.builder().build(), "缺 name");
        assertThrows(MetaPoolConfigException.class,
                () -> NettyByteBufAdapter.builder().named("m").defaultCapacity(0).build());
        assertThrows(MetaPoolConfigException.class,
                () -> NettyByteBufAdapter.builder().named("m")
                        .defaultCapacity(1024).maxCapacity(512).build(), "max < default");
    }

    @Test
    void factory_viaResourceDefinition() throws Exception {
        NettyByteBufAdapterFactory f = new NettyByteBufAdapterFactory();
        assertEquals("memory", f.supportedType());

        var def = new ResourceDefinition("bufpool", "memory",
                Map.of("prefer-direct", "false", "default-capacity", 512, "max-capacity", 4096),
                Set.of("default-capacity"));
        ManagedResource r = f.create(def);
        assertEquals("bufpool", r.name());
        assertEquals(512, ((NettyByteBufAdapter) r).defaultCapacity());

        r.start();
        try {
            @SuppressWarnings("unchecked")
            Pool<ByteBuf> pool = (Pool<ByteBuf>) r;
            ByteBuf b = pool.borrow();
            assertEquals(512, b.capacity());
            pool.release(b);
        } finally {
            r.stop(Duration.ZERO);
        }
    }

    /** 同 lettuce：{@code "yes"} 不得被 {@code Boolean.parseBoolean} 静默当成 false。 */
    @Test
    void factory_invalidPreferDirect_isNotSilentlyTreatedAsFalse() {
        MetaPoolConfigException e = assertThrows(MetaPoolConfigException.class,
                () -> new NettyByteBufAdapterFactory().create(new ResourceDefinition("m", "memory",
                        Map.of("prefer-direct", "yes"), Set.of())));
        assertTrue(e.getMessage().contains("prefer-direct"), e.getMessage());
    }

    @Test
    void factory_invalidConfig_failsFast() {
        NettyByteBufAdapterFactory f = new NettyByteBufAdapterFactory();
        assertThrows(MetaPoolConfigException.class, () -> f.create(
                new ResourceDefinition("m", "memory", Map.of("default-capacity", "abc"), Set.of())));
        assertThrows(MetaPoolConfigException.class, () -> f.create(
                new ResourceDefinition("m", "redis", Map.of(), Set.of())), "类型不匹配");
    }

    @Test
    void factory_isDiscoverableViaServiceLoader() {
        boolean found = java.util.ServiceLoader.load(
                        com.metapool.common.spi.ResourceAdapterFactory.class).stream()
                .map(java.util.ServiceLoader.Provider::get)
                .anyMatch(x -> "memory".equals(x.supportedType()));
        assertTrue(found, "META-INF/services 未正确注册 NettyByteBufAdapterFactory");
    }
}
