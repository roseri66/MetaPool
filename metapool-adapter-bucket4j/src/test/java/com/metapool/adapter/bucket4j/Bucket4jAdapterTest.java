package com.metapool.adapter.bucket4j;

import com.metapool.common.capability.Pool;
import com.metapool.common.exception.MetaPoolConfigException;
import com.metapool.common.resource.ManagedResource;
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
 * M3-A 验收：Bucket4j 适配器把「非池资源」纳入治理，且不实现 Pool。
 */
class Bucket4jAdapterTest {

    @Test
    void rateLimiter_isNotAPool_capabilitySegregation() {
        // 以 ManagedResource 承接：Bucket4jAdapter 是 final 且不实现 Pool，
        // 对其直接做 instanceof Pool 会被编译器判为「不可能」而报错——这本身就是能力隔离的编译期证据。
        ManagedResource rl = Bucket4jAdapter.builder()
                .named("order-api").limitForPeriod(10).refillPeriod(Duration.ofSeconds(1)).build();
        assertFalse(rl instanceof Pool, "RateLimiter 不应实现 Pool（能力隔离，根除 1.0 LSP 错误）");
        assertEquals("rate-limiter", rl.type());
    }

    @Test
    void lifecycle_and_tryAcquire_consumesTokens() {
        Bucket4jAdapter rl = Bucket4jAdapter.builder()
                .named("api").limitForPeriod(3).refillPeriod(Duration.ofMinutes(1)).build();
        assertEquals(HealthStatus.Status.DOWN, rl.health().status());

        rl.start();
        assertEquals(HealthStatus.Status.UP, rl.health().status());

        assertTrue(rl.tryAcquire(1));
        assertTrue(rl.tryAcquire(1));
        assertTrue(rl.tryAcquire(1));
        assertFalse(rl.tryAcquire(1), "第 4 次应被限流");

        assertEquals(3, rl.limiterStats().totalAcquired());
        assertEquals(1, rl.limiterStats().totalRejected());

        rl.stop(Duration.ZERO);
        assertEquals(HealthStatus.Status.DOWN, rl.health().status());
    }

    @Test
    void restart_afterStop_yieldsUsableLimiter() {
        Bucket4jAdapter rl = Bucket4jAdapter.builder()
                .named("api").limitForPeriod(3).refillPeriod(Duration.ofMinutes(1)).build();
        rl.start();
        assertTrue(rl.tryAcquire(1));
        rl.stop(Duration.ZERO);
        assertEquals(HealthStatus.Status.DOWN, rl.health().status());

        rl.start();   // 重启后额度应恢复
        assertEquals(HealthStatus.Status.UP, rl.health().status());
        assertTrue(rl.tryAcquire(1));
        rl.stop(Duration.ZERO);
    }

    @Test
    void metrics_unifiedTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Bucket4jAdapter rl = Bucket4jAdapter.builder()
                .named("search").limitForPeriod(5).refillPeriod(Duration.ofSeconds(1)).build();
        rl.bindTo(registry);
        rl.start();
        rl.tryAcquire(1);

        assertNotNull(registry.find("metapool.ratelimiter.available.tokens")
                .tag("metapool.resource", "search")
                .tag("metapool.type", "rate-limiter").gauge());
        assertEquals(1.0, registry.get("metapool.ratelimiter.acquired.total")
                .tag("metapool.resource", "search").functionCounter().count());
        rl.stop(Duration.ZERO);
    }

    @Test
    void tunable_hotChangeLimit() {
        Bucket4jAdapter rl = Bucket4jAdapter.builder()
                .named("api").limitForPeriod(2).refillPeriod(Duration.ofMinutes(1)).build();
        rl.start();
        assertTrue(rl.tryAcquire(2));
        assertFalse(rl.tryAcquire(1)); // 用尽

        TuneResult ok = rl.apply(Map.of("limit-for-period", 10));
        assertTrue(ok.success());
        // 提高上限后（ADDITIVE 继承：增量额度立即授予当前桶），应重新有额度
        assertTrue(rl.tryAcquire(1));

        TuneResult rejected = rl.apply(Map.of("refill-period", "5s"));
        assertFalse(rejected.success());
        assertTrue(rejected.rejected().containsKey("refill-period"));
        rl.stop(Duration.ZERO);
    }

    @Test
    void factory_viaResourceDefinition() {
        Bucket4jAdapterFactory factory = new Bucket4jAdapterFactory();
        assertEquals("rate-limiter", factory.supportedType());

        var def = new ResourceDefinition("order-api", "rate-limiter",
                Map.of("limit-for-period", 100, "refill-period", "1s"),
                Set.of("limit-for-period"));
        var resource = factory.create(def);
        assertEquals("order-api", resource.name());
        resource.start();
        assertEquals(HealthStatus.Status.UP, resource.health().status());
        resource.stop(Duration.ZERO);
    }

    /**
     * RULES §3.5 要求生命周期方法 start/stop 都 synchronized。start() 一直有，stop() 曾漏（坑 P-09）：
     * 漏了会丢 stop —— stop() 在 start() 的「bucket != null」检查与赋值之间把字段置空，
     * start() 随后赋上新桶，于是 stop() 已返回而限流器还活着继续放行流量。
     *
     * <p>竞态本身不可确定性复现，因此这里直接守护规则所述的不变量：方法必须带 synchronized 修饰。
     */
    @Test
    void lifecycleMethods_areSynchronized_perRules() throws Exception {
        assertTrue(Modifier.isSynchronized(
                        Bucket4jAdapter.class.getMethod("start").getModifiers()),
                "start() 必须 synchronized（RULES §3.5）");
        assertTrue(Modifier.isSynchronized(
                        Bucket4jAdapter.class.getMethod("stop", Duration.class).getModifiers()),
                "stop() 必须 synchronized（RULES §3.5）—— 否则与 start() 竞争会丢 stop");
    }

    @Test
    void stop_isIdempotent_andSafeWhenNeverStarted() {
        Bucket4jAdapter rl = Bucket4jAdapter.builder()
                .named("api").limitForPeriod(1).refillPeriod(Duration.ofMinutes(1)).build();
        rl.stop(Duration.ZERO);   // 从未启动
        assertEquals(HealthStatus.Status.DOWN, rl.health().status());

        rl.start();
        rl.stop(Duration.ZERO);
        rl.stop(Duration.ZERO);   // 重复停机
        assertEquals(HealthStatus.Status.DOWN, rl.health().status());
    }

    /** 坑 P-13：tunable 白名单里不支持的 key 必须构建期就报，而不是等运维调参时才返回 rejected。 */
    @Test
    void unsupportedTunableKey_failsFastAtBuildTime() {
        MetaPoolConfigException e = assertThrows(MetaPoolConfigException.class,
                () -> Bucket4jAdapter.builder().named("api").limitForPeriod(10)
                        .tunable(Set.of("refill-period"))   // 不支持热调
                        .build());
        assertTrue(e.getMessage().contains("refill-period"), e.getMessage());

        var def = new ResourceDefinition("order-api", "rate-limiter",
                Map.of("limit-for-period", 100), Set.of("limit-per-period"));  // 拼错
        assertThrows(MetaPoolConfigException.class, () -> new Bucket4jAdapterFactory().create(def));
    }

}
