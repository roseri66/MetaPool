package com.metapool.core;

import com.metapool.common.exception.ErrorCode;
import com.metapool.common.exception.MetaPoolException;
import com.metapool.common.manager.ResourceManager;
import com.metapool.common.resource.ManagedResource;
import com.metapool.common.resource.Tunable;
import com.metapool.common.stats.HealthStatus;
import com.metapool.common.stats.TuneResult;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 控制面编排逻辑单测：注册冲突、启停顺序、聚合健康、调参路由。用 Fake 资源，不依赖真实适配器。
 */
class DefaultResourceManagerTest {

    /** 记录全局生命周期事件，用于验证顺序。 */
    static class FakeResource implements ManagedResource {
        final String name;
        final List<String> events;
        HealthStatus.Status healthStatus = HealthStatus.Status.UP;

        FakeResource(String name, List<String> events) {
            this.name = name;
            this.events = events;
        }

        @Override public String name() { return name; }
        @Override public String type() { return "fake"; }
        @Override public void start() { events.add("start:" + name); }
        @Override public void stop(Duration graceful) { events.add("stop:" + name); }
        @Override public HealthStatus health() { return new HealthStatus(healthStatus, name); }
        @Override public void bindTo(MeterRegistry registry) { events.add("bind:" + name); }
    }

    static final class TunableFake extends FakeResource implements Tunable {
        TunableFake(String name, List<String> events) { super(name, events); }
        @Override public Set<String> tunableKeys() { return Set.of("x"); }
        @Override public TuneResult apply(Map<String, Object> patch) {
            return patch.containsKey("x") ? TuneResult.ok(Set.of("x"))
                    : TuneResult.partial(Set.of(), Map.of("bad", "no"));
        }
    }

    @Test
    void register_conflict_isRejected() {
        ResourceManager mgr = new DefaultResourceManager();
        mgr.register(new FakeResource("a", new ArrayList<>()));
        assertThrows(MetaPoolException.class,
                () -> mgr.register(new FakeResource("a", new ArrayList<>())));
    }

    @Test
    void start_inOrder_close_inReverse() {
        List<String> events = new ArrayList<>();
        ResourceManager mgr = new DefaultResourceManager(Duration.ZERO);
        mgr.register(new FakeResource("r1", events));
        mgr.register(new FakeResource("r2", events));
        mgr.register(new FakeResource("r3", events));

        mgr.start();
        mgr.close();

        assertEquals(List.of("start:r1", "start:r2", "start:r3",
                "stop:r3", "stop:r2", "stop:r1"), events);
    }

    /**
     * 坑 P-11：start() 中途失败若不回滚，已启动的资源就永远没人关 —— Spring 场景下 @Bean 抛异常，
     * 容器拿不到 bean，destroyMethod="close" 永不执行，底层池的线程/连接泄漏到 JVM 结束。
     */
    @Test
    void start_failure_rollsBackAlreadyStartedResources_inReverse() {
        List<String> events = new ArrayList<>();
        ResourceManager mgr = new DefaultResourceManager(Duration.ZERO);
        mgr.register(new FakeResource("r1", events));
        mgr.register(new FakeResource("r2", events));
        mgr.register(new FailingResource("boom", events));
        mgr.register(new FakeResource("never", events));

        MetaPoolException thrown = assertThrows(MetaPoolException.class, mgr::start);
        assertTrue(thrown.getMessage().contains("boom"));

        // r1/r2 启动过 → 必须被逆序停掉；boom 自身没启动成功；never 根本没轮到
        assertEquals(List.of("start:r1", "start:r2", "start:boom", "stop:r2", "stop:r1"), events);
    }

    /** 启动即失败的资源，用于验证回滚。 */
    static final class FailingResource extends FakeResource {
        FailingResource(String name, List<String> events) { super(name, events); }
        @Override public void start() {
            events.add("start:" + name);
            throw new MetaPoolException(ErrorCode.INTERNAL, "cannot start '" + name + "'");
        }
    }

    @Test
    void health_aggregation() {
        List<String> events = new ArrayList<>();
        DefaultResourceManager mgr = new DefaultResourceManager();
        FakeResource a = mgr.register(new FakeResource("a", events));
        FakeResource b = mgr.register(new FakeResource("b", events));

        assertEquals(HealthStatus.Status.UP, mgr.health().status());

        b.healthStatus = HealthStatus.Status.DEGRADED;
        assertEquals(HealthStatus.Status.DEGRADED, mgr.health().status());

        a.healthStatus = HealthStatus.Status.DOWN;
        assertEquals(HealthStatus.Status.DOWN, mgr.health().status());
    }

    @Test
    void tune_routesToTunable_rejectsNonTunable() {
        ResourceManager mgr = new DefaultResourceManager();
        mgr.register(new TunableFake("t", new ArrayList<>()));
        mgr.register(new FakeResource("plain", new ArrayList<>()));

        assertTrue(mgr.tune("t", Map.of("x", 1)).success());
        assertThrows(MetaPoolException.class, () -> mgr.tune("plain", Map.of("x", 1)));
        assertThrows(MetaPoolException.class, () -> mgr.tune("missing", Map.of("x", 1)));
    }
}
