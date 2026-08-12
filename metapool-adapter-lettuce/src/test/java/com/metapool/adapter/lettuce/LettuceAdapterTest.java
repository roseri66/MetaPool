package com.metapool.adapter.lettuce;

import com.metapool.common.capability.ManagedExecutor;
import com.metapool.common.capability.Pool;
import com.metapool.common.capability.RateLimiter;
import com.metapool.common.exception.MetaPoolConfigException;
import com.metapool.common.exception.MetaPoolException;
import com.metapool.common.resource.ManagedResource;
import com.metapool.common.resource.Tunable;
import com.metapool.common.spi.ResourceDefinition;
import com.metapool.common.stats.HealthStatus;
import com.metapool.common.stats.TuneResult;
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
 * 不需要 Redis 的部分：<b>能力隔离（本适配器最核心的主张）</b>、配置校验、未启动行为、工厂解析。
 *
 * <p>真连接 / PING / 热调超时见 {@link LettuceAdapterRedisTest}（需 Docker）。
 */
class LettuceAdapterTest {

    private static LettuceAdapter.Builder aRedis(String name) {
        return LettuceAdapter.builder().named(name).uri("redis://127.0.0.1:6379");
    }

    // ==================== 能力隔离：本适配器的核心主张 ====================

    /**
     * 🎯 <b>本适配器最重要的一条断言：它不实现 {@code Pool}。</b>
     *
     * <p>Lettuce 是单连接多路复用 —— 一个连接天然线程安全、被所有线程共享，
     * <b>没有「借出/归还」这回事</b>。本项目已有两个 {@code Pool} 实现，
     * 于是「别的 adapter 都实现了 Pool，这个也该实现」听起来很自然 ——
     * <b>但那是用一致性绑架语义</b>，与 1.0 把所有资源硬塞进 acquire/release 同源（P-07）。
     *
     * <p>写成测试，是为了让这个决定<b>不会被后来者"顺手补全"</b>。
     */
    @Test
    void redisConnection_isNotAPool_becauseMultiplexingHasNoBorrowSemantics() {
        ManagedResource redis = aRedis("cache").build();
        assertFalse(redis instanceof Pool,
                "Lettuce 单连接多路复用没有借还语义，不得实现 Pool（P-07：别用一致性绑架语义）");
        assertFalse(redis instanceof RateLimiter, "不是限流器");
        assertFalse(redis instanceof ManagedExecutor, "不是执行器");
        assertEquals("redis", redis.type());
    }

    /**
     * 但它<b>确实</b>实现 {@link Tunable} —— 与 Redisson 适配器恰好相反。
     *
     * <p>{@code command-timeout} 是运行时真可写的；而 Redisson 锁没有任何运行时可调参数，
     * 所以那个适配器不实现 {@code Tunable}。<b>同一条判据（有没有真参数），两个相反结论。</b>
     */
    @Test
    void redisAdapter_isTunable_unlikeTheRedissonOne() {
        ManagedResource redis = aRedis("cache").build();
        assertTrue(redis instanceof Tunable,
                "command-timeout 运行时可写，是真参数，应实现 Tunable");
        assertEquals(Set.of("command-timeout"), ((Tunable) redis).tunableKeys());
    }

    // ==================== 生命周期 ====================

    @Test
    void health_isDown_beforeStart() {
        LettuceAdapter redis = aRedis("cache").build();
        assertEquals(HealthStatus.Status.DOWN, redis.health().status());
        assertEquals("not started", redis.health().detail());
    }

    @Test
    void useBeforeStart_throwsMetaPoolException() {
        LettuceAdapter redis = aRedis("cache").build();
        assertThrows(MetaPoolException.class, redis::connection,
                "未启动是 MetaPool 自己的错误语义（RULES §3.2 反向边界）");
        assertThrows(MetaPoolException.class, redis::unwrap);
    }

    @Test
    void stop_isIdempotent_andSafeWhenNeverStarted() {
        LettuceAdapter redis = aRedis("cache").build();
        redis.stop(Duration.ZERO);
        redis.stop(Duration.ZERO);
        assertEquals(HealthStatus.Status.DOWN, redis.health().status());
    }

    /** RULES §3.5 / 坑 P-09：生命周期方法必须 synchronized。 */
    @Test
    void lifecycleMethods_areSynchronized_perRules() throws Exception {
        assertTrue(Modifier.isSynchronized(LettuceAdapter.class.getMethod("start").getModifiers()));
        assertTrue(Modifier.isSynchronized(
                LettuceAdapter.class.getMethod("stop", Duration.class).getModifiers()));
    }

    /** 指标可在启动前绑定（与其余适配器一致），不依赖后端连通。 */
    @Test
    void metrics_unifiedTags_bindableBeforeStart() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LettuceAdapter redis = aRedis("cache").build();
        redis.bindTo(registry);

        assertNotNull(registry.find("metapool.redis.connection.open")
                .tag("metapool.resource", "cache")
                .tag("metapool.type", "redis").gauge());
        assertNotNull(registry.find("metapool.redis.connects.total")
                .tag("metapool.resource", "cache").functionCounter());
        assertNotNull(registry.find("metapool.redis.disconnects.total")
                .tag("metapool.resource", "cache").functionCounter());
        assertNotNull(registry.find("metapool.redis.exceptions.total")
                .tag("metapool.resource", "cache").functionCounter());
        assertEquals(0.0, registry.get("metapool.redis.connection.open")
                .tag("metapool.resource", "cache").gauge().value(), "未启动应为 0");
    }

    // ==================== 配置校验 ====================

    @Test
    void invalidConfig_failsFastAtBuildTime() {
        assertThrows(MetaPoolConfigException.class,
                () -> LettuceAdapter.builder().uri("redis://h:1").build(), "缺 name");
        assertThrows(MetaPoolConfigException.class,
                () -> LettuceAdapter.builder().named("c").build(), "缺 uri");
        assertThrows(MetaPoolConfigException.class,
                () -> aRedis("c").commandTimeout(Duration.ZERO).build(), "超时须为正");
        assertThrows(MetaPoolConfigException.class,
                () -> aRedis("c").commandTimeout(Duration.ofSeconds(-1)).build());
    }

    /** 坑 P-13：不支持的 tunable key 必须构建期就报。 */
    @Test
    void unsupportedTunableKey_failsFastAtBuildTime() {
        MetaPoolConfigException e = assertThrows(MetaPoolConfigException.class,
                () -> aRedis("c").tunable(Set.of("uri")).build());
        assertTrue(e.getMessage().contains("uri"), e.getMessage());
    }

    /** 非法 uri 应在 start 时报 MetaPoolConfigException，而不是漏出 Lettuce 的裸异常（§3.2）。 */
    @Test
    void invalidUri_failsWithMetaPoolConfigException_onStart() {
        LettuceAdapter redis = LettuceAdapter.builder().named("c").uri("not-a-redis-uri").build();
        assertThrows(MetaPoolConfigException.class, redis::start);
    }

    @Test
    void tune_beforeStart_isRejected() {
        LettuceAdapter redis = aRedis("cache").build();
        TuneResult r = redis.apply(Map.of("command-timeout", "5s"));
        assertFalse(r.success());
        assertEquals("resource not started", r.rejected().get("command-timeout"));
    }

    // ==================== 工厂 ====================

    @Test
    void factory_viaResourceDefinition() {
        LettuceAdapterFactory f = new LettuceAdapterFactory();
        assertEquals("redis", f.supportedType());

        var def = new ResourceDefinition("cache", "redis",
                Map.of("uri", "redis://127.0.0.1:6379",
                        "command-timeout", "5s",
                        "auto-reconnect", "false"),
                Set.of());
        ManagedResource r = f.create(def);
        assertEquals("cache", r.name());
        assertEquals(Duration.ofSeconds(5), ((LettuceAdapter) r).commandTimeout());
        assertEquals(HealthStatus.Status.DOWN, r.health().status(), "尚未 start");
    }

    @Test
    void factory_invalidConfig_failsFast() {
        LettuceAdapterFactory f = new LettuceAdapterFactory();

        assertThrows(MetaPoolConfigException.class,
                () -> f.create(new ResourceDefinition("c", "redis", Map.of(), Set.of())), "缺 uri");
        assertThrows(MetaPoolConfigException.class, () -> f.create(
                new ResourceDefinition("c", "redis",
                        Map.of("uri", "redis://h:1", "command-timeout", "soon"), Set.of())));
        assertThrows(MetaPoolConfigException.class, () -> f.create(
                new ResourceDefinition("c", "lock", Map.of("uri", "redis://h:1"), Set.of())),
                "类型不匹配");
    }

    /**
     * {@code auto-reconnect} 写了识别不了的值必须报错。
     *
     * <p>若直接用 {@code Boolean.parseBoolean}，{@code "yes"} 会静默变成 {@code false} ——
     * 使用方以为开着自动重连，实际关着，而且只有在网络真抖动时才会发现。
     */
    @Test
    void factory_invalidAutoReconnect_isNotSilentlyTreatedAsFalse() {
        MetaPoolConfigException e = assertThrows(MetaPoolConfigException.class,
                () -> new LettuceAdapterFactory().create(new ResourceDefinition("c", "redis",
                        Map.of("uri", "redis://h:1", "auto-reconnect", "yes"), Set.of())));
        assertTrue(e.getMessage().contains("auto-reconnect"), e.getMessage());
    }


    @Test
    void factory_isDiscoverableViaServiceLoader() {
        boolean found = java.util.ServiceLoader.load(
                        com.metapool.common.spi.ResourceAdapterFactory.class).stream()
                .map(java.util.ServiceLoader.Provider::get)
                .anyMatch(x -> "redis".equals(x.supportedType()));
        assertTrue(found, "META-INF/services 未正确注册 LettuceAdapterFactory");
    }
}
