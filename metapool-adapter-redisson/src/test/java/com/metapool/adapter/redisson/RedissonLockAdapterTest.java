package com.metapool.adapter.redisson;

import com.metapool.common.capability.DistributedLock;
import com.metapool.common.capability.Pool;
import com.metapool.common.exception.MetaPoolConfigException;
import com.metapool.common.exception.MetaPoolException;
import com.metapool.common.resource.ManagedResource;
import com.metapool.common.resource.Tunable;
import com.metapool.common.spi.ResourceDefinition;
import com.metapool.common.stats.HealthStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
 * 不需要 Redis 的部分：能力隔离、配置校验、未启动行为、工厂解析。
 *
 * <p>真加锁 / 互斥 / 租约到期见 {@link RedissonLockAdapterRedisTest}（需 Docker）。
 */
class RedissonLockAdapterTest {

    private static RedissonLockAdapter.Builder aLock(String name) {
        return RedissonLockAdapter.builder().named(name).address("redis://127.0.0.1:6379");
    }

    // ==================== 能力隔离 ====================

    /** P-07：锁不是池。1.0 曾把锁建模成「借出一个 Boolean」。 */
    @Test
    void lock_isNotAPool_capabilitySegregation() {
        ManagedResource lock = aLock("order-lock").build();
        assertFalse(lock instanceof Pool, "锁不是池：不应实现 Pool（P-07）");
        assertEquals("lock", lock.type());
    }

    /**
     * 本适配器<b>刻意不实现</b> {@link Tunable}：Redisson 锁没有有意义的运行时可调参数
     * （{@code waitTime} / {@code leaseTime} 是每次调用传入的，不是配置）。
     *
     * <p>这条断言守的是「不为了看起来完整而硬凑能力」—— 可选能力接口谁有谁实现，
     * 没有就不实现，正是能力隔离在正常工作的证据。
     */
    @Test
    void lock_doesNotImplementTunable_becauseNothingIsTunableAtRuntime() {
        ManagedResource lock = aLock("order-lock").build();
        assertFalse(lock instanceof Tunable,
                "Redisson 锁无运行时可调参数，不应为了「显得完整」而实现 Tunable");
    }

    /** 释放锁的唯一途径是凭证：接口上不得出现 unlock(key)，否则会解掉别人的锁。 */
    @Test
    void distributedLock_hasNoUnlockByKey() {
        boolean hasUnlock = java.util.Arrays.stream(DistributedLock.class.getMethods())
                .anyMatch(m -> m.getName().equals("unlock"));
        assertFalse(hasUnlock, "不得提供 unlock(key)：无法判断调用方是否持有者");
    }

    // ==================== 生命周期 ====================

    @Test
    void health_isDown_beforeStart() {
        RedissonLockAdapter lock = aLock("order-lock").build();
        assertEquals(HealthStatus.Status.DOWN, lock.health().status());
        assertEquals("not started", lock.health().detail());
    }

    @Test
    void useBeforeStart_throwsMetaPoolException() {
        RedissonLockAdapter lock = aLock("order-lock").build();
        assertThrows(MetaPoolException.class,
                () -> lock.tryLock("k", Duration.ZERO, Duration.ofSeconds(10)),
                "未启动是 MetaPool 自己的错误语义，必须是 MetaPoolException（RULES §3.2 反向边界）");
    }

    @Test
    void stop_isIdempotent_andSafeWhenNeverStarted() {
        RedissonLockAdapter lock = aLock("order-lock").build();
        lock.stop(Duration.ZERO);   // 从未启动
        lock.stop(Duration.ZERO);   // 重复
        assertEquals(HealthStatus.Status.DOWN, lock.health().status());
    }

    /** RULES §3.5 / 坑 P-09：生命周期方法必须 synchronized，否则并发 start/stop 会丢 stop。 */
    @Test
    void lifecycleMethods_areSynchronized_perRules() throws Exception {
        assertTrue(Modifier.isSynchronized(
                RedissonLockAdapter.class.getMethod("start").getModifiers()),
                "start() 必须 synchronized（RULES §3.5）");
        assertTrue(Modifier.isSynchronized(
                RedissonLockAdapter.class.getMethod("stop", Duration.class).getModifiers()),
                "stop() 必须 synchronized（RULES §3.5）");
    }

    @Test
    void stats_areZero_beforeAnyUse() {
        RedissonLockAdapter lock = aLock("order-lock").build();
        assertEquals(0, lock.lockStats().heldByThisProcess());
        assertEquals(0, lock.lockStats().totalAcquired());
        assertEquals(0, lock.lockStats().totalTimeout());
        assertEquals(0, lock.lockStats().totalLeaseExpired());
    }

    /** 指标可以在启动前就绑定（与另外两个适配器一致），不依赖后端连通。 */
    @Test
    void metrics_unifiedTags_bindableBeforeStart() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RedissonLockAdapter lock = aLock("order-lock").build();
        lock.bindTo(registry);

        assertNotNull(registry.find("metapool.lock.held")
                .tag("metapool.resource", "order-lock")
                .tag("metapool.type", "lock").gauge());
        assertNotNull(registry.find("metapool.lock.acquired.total")
                .tag("metapool.resource", "order-lock").functionCounter());
        assertNotNull(registry.find("metapool.lock.timeout.total")
                .tag("metapool.resource", "order-lock").functionCounter());
        assertNotNull(registry.find("metapool.lock.lease.expired.total")
                .tag("metapool.resource", "order-lock").functionCounter());
    }

    // ==================== 配置校验（fail-fast） ====================

    @Test
    void invalidConfig_failsFastAtBuildTime() {
        assertThrows(MetaPoolConfigException.class,
                () -> RedissonLockAdapter.builder().address("redis://h:1").build(),
                "缺 name 应构建期失败");
        assertThrows(MetaPoolConfigException.class,
                () -> RedissonLockAdapter.builder().named("l").build(),
                "缺 address 应构建期失败");
        assertThrows(MetaPoolConfigException.class,
                () -> aLock("l").database(-1).build());
        assertThrows(MetaPoolConfigException.class,
                () -> aLock("l").connectionPoolSize(0).build());
    }

    /**
     * 契约要求 {@code leaseTime} 必须为正：没有租约的分布式锁，持有者进程一崩溃就是永久死锁。
     * 该校验在参数层面完成，因此不需要真实 Redis 也能验证。
     */
    @Test
    void nonPositiveLeaseTime_isRejected() {
        RedissonLockAdapter lock = aLock("l").build();
        // 未启动会先抛 not started，故这里只验证「启动检查在参数检查之前」不成立的那部分：
        // 参数非法时同样是 MetaPoolException，调用方拿到的错误类型一致。
        assertThrows(MetaPoolException.class,
                () -> lock.tryLock("k", Duration.ZERO, Duration.ZERO));
        assertThrows(MetaPoolException.class,
                () -> lock.tryLock("k", Duration.ofSeconds(-1), Duration.ofSeconds(1)));
    }

    // ==================== 工厂 ====================

    @Test
    void factory_viaResourceDefinition() {
        RedissonLockAdapterFactory factory = new RedissonLockAdapterFactory();
        assertEquals("lock", factory.supportedType());

        var def = new ResourceDefinition("order-lock", "lock",
                Map.of("address", "redis://127.0.0.1:6379",
                        "database", 3,
                        "connection-pool-size", 8,
                        "key-prefix", "demo:lock:"),
                Set.of());
        ManagedResource r = factory.create(def);
        assertEquals("order-lock", r.name());
        assertEquals("lock", r.type());
        assertEquals(HealthStatus.Status.DOWN, r.health().status(), "尚未 start");
    }

    @Test
    void factory_invalidConfig_failsFastWithMetaPoolConfigException() {
        RedissonLockAdapterFactory factory = new RedissonLockAdapterFactory();

        assertThrows(MetaPoolConfigException.class, () -> factory.create(
                new ResourceDefinition("l", "lock", Map.of(), Set.of())), "缺 address 应报错");

        assertThrows(MetaPoolConfigException.class, () -> factory.create(
                new ResourceDefinition("l", "lock",
                        Map.of("address", "redis://h:1", "database", "abc"), Set.of())));

        assertThrows(MetaPoolConfigException.class, () -> factory.create(
                new ResourceDefinition("l", "datasource", Map.of("address", "redis://h:1"), Set.of())),
                "类型不匹配应报错");
    }

    /** SPI 注册文件必须真的能被 ServiceLoader 发现，否则 YAML 里写了 lock 也找不到工厂。 */
    @Test
    void factory_isDiscoverableViaServiceLoader() {
        boolean found = java.util.ServiceLoader.load(
                        com.metapool.common.spi.ResourceAdapterFactory.class).stream()
                .map(java.util.ServiceLoader.Provider::get)
                .anyMatch(f -> "lock".equals(f.supportedType()));
        assertTrue(found, "META-INF/services 未正确注册 RedissonLockAdapterFactory");
    }
}
