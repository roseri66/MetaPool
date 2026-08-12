package com.metapool.core;

import com.metapool.common.exception.MetaPoolException;
import com.metapool.common.manager.ResourceManager;
import com.metapool.common.resource.ManagedResource;
import com.metapool.common.stats.HealthStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 控制面的<b>故障路径</b>验收。
 *
 * <p>为什么单开一个文件：{@code metapool-core} 是唯一被<b>所有</b>资源共用的代码，
 * 它出问题时五类资源同时受影响，而且是「治理面自己坏了」—— 比某个适配器坏了严重得多，
 * 也最难在使用方现场复现。原有的 {@link DefaultResourceManagerTest} 覆盖的是顺利路径
 * （注册、启停顺序、聚合健康、调参路由），这里专门覆盖<b>有人抛异常时会发生什么</b>。
 *
 * <p>贯穿本文件的一条判据：<b>治理面必须比被治理者更结实。</b>
 * 某个资源行为不端，不该让控制面失去对其余资源的控制。
 */
class ControlPlaneFailurePathTest {

    /** 可编程失败的 Fake 资源：想让哪个方法炸就设哪个标志。 */
    static class ExplodingResource implements ManagedResource {
        private final String name;
        private final List<String> events;
        boolean failOnStart;
        boolean failOnStop;
        boolean failOnHealth;
        boolean failOnBind;
        HealthStatus.Status status = HealthStatus.Status.UP;

        ExplodingResource(String name, List<String> events) {
            this.name = name;
            this.events = events;
        }

        @Override public String name() { return name; }
        @Override public String type() { return "fake"; }

        @Override public void start() {
            events.add("start:" + name);
            if (failOnStart) {
                throw new IllegalStateException("boom-start:" + name);
            }
        }

        @Override public void stop(Duration graceful) {
            events.add("stop:" + name);
            if (failOnStop) {
                throw new IllegalStateException("boom-stop:" + name);
            }
        }

        @Override public HealthStatus health() {
            if (failOnHealth) {
                throw new IllegalStateException("boom-health:" + name);
            }
            return new HealthStatus(status, name + "-detail");
        }

        @Override public void bindTo(MeterRegistry registry) {
            events.add("bind:" + name);
            if (failOnBind) {
                throw new IllegalStateException("boom-bind:" + name);
            }
        }
    }

    private static ExplodingResource res(String name, List<String> events) {
        return new ExplodingResource(name, events);
    }

    // ==================== 停机的容错 ====================

    /**
     * 停机时某个资源抛异常，<b>其余资源必须照样被停掉</b>。
     *
     * <p>这是控制面最不能失守的一条：停机路径上一个行为不端的适配器，
     * 会让它<b>后面</b>所有资源的连接/线程泄漏到 JVM 结束 —— 而停机往往发生在
     * 容器关闭这种没人盯着的时刻，泄漏不会有人立刻发现。
     */
    @Test
    void close_keepsStoppingOthers_whenOneResourceThrows() {
        List<String> events = new ArrayList<>();
        ResourceManager mgr = new DefaultResourceManager(Duration.ZERO);
        mgr.register(res("r1", events));
        ExplodingResource bad = mgr.register(res("r2", events));
        mgr.register(res("r3", events));
        bad.failOnStop = true;

        mgr.start();
        events.clear();

        mgr.close();   // 不得抛：停机是尽力而为，不能因一个资源半途而废

        assertEquals(List.of("stop:r3", "stop:r2", "stop:r1"), events,
                "r2 抛异常后，r1 仍必须被停掉（逆序继续走完）");
    }

    /** 全部资源的 stop 都炸，close 仍不得抛 —— 否则容器关闭流程会被打断。 */
    @Test
    void close_doesNotThrow_evenWhenAllResourcesFail() {
        List<String> events = new ArrayList<>();
        ResourceManager mgr = new DefaultResourceManager(Duration.ZERO);
        for (String n : List.of("r1", "r2", "r3")) {
            ExplodingResource r = mgr.register(res(n, events));
            r.failOnStop = true;
        }
        mgr.start();
        events.clear();

        mgr.close();

        assertEquals(3, events.size(), "三个都应被尝试停机");
    }

    /** close 可重复调用（容器可能重复触发销毁），语义是「再停一遍」，不得抛。 */
    @Test
    void close_isIdempotentFromTheControlPlaneSide() {
        List<String> events = new ArrayList<>();
        ResourceManager mgr = new DefaultResourceManager(Duration.ZERO);
        mgr.register(res("r1", events));
        mgr.start();

        mgr.close();
        mgr.close();

        assertEquals(2, Collections.frequency(events, "stop:r1"),
                "控制面本身不去重，两次 close 就调两次 stop —— 幂等由适配器保证（RULES §3.5）");
    }

    // ==================== 启动失败的回滚（P-11） ====================

    /**
     * 坑 P-11：启动中途失败必须逆序回滚已启动者，且<b>回滚过程中的异常不得掩盖首因</b>。
     *
     * <p>原有测试验了事件顺序，这里补的是更要命的一半：回滚时 r1 的 stop 也炸了，
     * 抛出来的仍必须是「启动失败」这个原始异常，回滚异常挂在 suppressed 上。
     * 否则排查时看到的是「停机失败」，真正的启动错误反而不见了。
     */
    @Test
    void startFailure_rollbackException_doesNotMaskTheOriginalCause() {
        List<String> events = new ArrayList<>();
        ResourceManager mgr = new DefaultResourceManager();
        ExplodingResource r1 = mgr.register(res("r1", events));
        ExplodingResource r2 = mgr.register(res("r2", events));
        r1.failOnStop = true;      // 回滚时炸
        r2.failOnStart = true;     // 启动时炸（首因）

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, mgr::start);

        assertEquals("boom-start:r2", thrown.getMessage(), "抛出的必须是首因（启动失败）");
        assertEquals(1, thrown.getSuppressed().length, "回滚异常应挂在 suppressed 上，而不是替换首因");
        assertEquals("boom-stop:r1", thrown.getSuppressed()[0].getMessage());
        assertEquals(List.of("start:r1", "start:r2", "stop:r1"), events,
                "r2 启动失败后，已启动的 r1 必须被回滚");
    }

    /** 第一个资源就启动失败：没有已启动者可回滚，异常原样抛出，不得因空回滚而变形。 */
    @Test
    void startFailure_onFirstResource_hasNothingToRollBack() {
        List<String> events = new ArrayList<>();
        ResourceManager mgr = new DefaultResourceManager();
        ExplodingResource r1 = mgr.register(res("r1", events));
        mgr.register(res("r2", events));
        r1.failOnStart = true;

        IllegalStateException thrown = assertThrows(IllegalStateException.class, mgr::start);
        assertEquals("boom-start:r1", thrown.getMessage());
        assertEquals(0, thrown.getSuppressed().length);
        assertEquals(List.of("start:r1"), events, "r2 不应被启动");
    }

    // ==================== 聚合健康的健壮性 ====================

    /**
     * 🔴 某个资源的 {@code health()} 抛异常，<b>不得击穿整个聚合健康</b>。
     *
     * <p>聚合健康正是「出事的时候」要看的东西。若一个行为不端的适配器能让
     * {@code /actuator/health} 整个报错，那么最需要它的时刻它恰好不可用 ——
     * 而且运维看到的会是一个与真实故障无关的异常。
     *
     * <p>期望：把抛异常的资源计为 <b>DOWN</b> 并在 detail 里点名，其余资源照常参与聚合。
     */
    @Test
    void health_oneResourceThrowing_doesNotBreakTheAggregate() {
        List<String> events = new ArrayList<>();
        ResourceManager mgr = new DefaultResourceManager();
        mgr.register(res("healthy", events));
        ExplodingResource bad = mgr.register(res("rogue", events));
        bad.failOnHealth = true;

        HealthStatus h = mgr.health();

        assertEquals(HealthStatus.Status.DOWN, h.status(),
                "health() 抛异常的资源应被当作 DOWN，而不是让整个聚合崩掉");
        assertTrue(h.detail().contains("rogue"), "detail 要点名是谁：" + h.detail());
    }

    /** DOWN 必须压过 DEGRADED（坑 P-06），且 detail 要同时列出两者。 */
    @Test
    void health_downOutranksDegraded_andDetailListsBoth() {
        List<String> events = new ArrayList<>();
        ResourceManager mgr = new DefaultResourceManager();
        ExplodingResource degraded = mgr.register(res("degraded-one", events));
        ExplodingResource down = mgr.register(res("down-one", events));
        degraded.status = HealthStatus.Status.DEGRADED;
        down.status = HealthStatus.Status.DOWN;

        HealthStatus h = mgr.health();

        assertEquals(HealthStatus.Status.DOWN, h.status(), "DOWN > DEGRADED（P-06）");
        assertTrue(h.detail().contains("degraded-one"), h.detail());
        assertTrue(h.detail().contains("down-one"), h.detail());
    }

    /** 一个资源都没有时聚合为 UP —— 空控制面不算故障。 */
    @Test
    void health_ofEmptyControlPlane_isUp() {
        assertEquals(HealthStatus.Status.UP, new DefaultResourceManager().health().status());
    }

    // ==================== 指标绑定 ====================

    /**
     * 绑定指标时某个资源抛异常：当前行为是<b>快速失败</b>（异常抛给调用方），
     * 且在它之前的资源已经绑上了。
     *
     * <p>这里刻意断言现状而不是"容错"：{@code bindTo} 抛异常属于接线错误，发生在启动期，
     * 此时<b>大声失败好过静默少一半指标</b> —— 后者会让人在故障时对着一块缺了曲线的看板
     * 做判断。本测试的作用是把这个取舍钉住，防止有人"顺手"给它加个 try-catch。
     */
    @Test
    void bindMetrics_failsFast_ratherThanSilentlyBindingHalfTheResources() {
        List<String> events = new ArrayList<>();
        ResourceManager mgr = new DefaultResourceManager();
        mgr.register(res("first", events));
        ExplodingResource bad = mgr.register(res("second", events));
        mgr.register(res("third", events));
        bad.failOnBind = true;

        assertThrows(IllegalStateException.class, () -> mgr.bindMetrics(new SimpleMeterRegistry()));
        assertEquals(List.of("bind:first", "bind:second"), events,
                "失败即停：third 不应被绑定，且异常必须抛给调用方（启动期大声失败）");
    }

    // ==================== 注册表语义 ====================

    /**
     * 并发注册同名资源：<b>有且仅有一个</b>成功，其余必须被拒。
     *
     * <p>注册表是 `LinkedHashMap` + 显式锁，这条守的是那把锁真的在起作用 ——
     * 若换成非线程安全的实现或漏掉同步，这里会同时冒出两个"成功"。
     */
    @Test
    void concurrentRegister_ofTheSameName_admitsExactlyOne() throws Exception {
        ResourceManager mgr = new DefaultResourceManager();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> rejected = new ConcurrentLinkedQueue<>();

        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        mgr.register(res("same-name", new ArrayList<>()));
                        succeeded.incrementAndGet();
                    } catch (MetaPoolException e) {
                        rejected.add(e);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, succeeded.get(), "同名并发注册只能成功一次");
        assertEquals(threads - 1, rejected.size(), "其余全部应被拒");
        assertEquals(1, mgr.resources().size());
    }

    /** 并发注册不同名资源：一个都不能丢。 */
    @Test
    void concurrentRegister_ofDistinctNames_losesNothing() throws Exception {
        ResourceManager mgr = new DefaultResourceManager();
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch done = new CountDownLatch(threads);
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                pool.submit(() -> {
                    try {
                        mgr.register(res("r" + idx, new ArrayList<>()));
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(done.await(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(threads, mgr.resources().size());
    }

    /** {@code resources()} 是只读快照：改它不影响注册表，也不该抛出到调用方之外的意外。 */
    @Test
    void resources_returnsReadOnlySnapshot() {
        List<String> events = new ArrayList<>();
        ResourceManager mgr = new DefaultResourceManager();
        mgr.register(res("r1", events));

        var snapshot = mgr.resources();
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.add(res("sneaky", events)), "返回的集合必须只读");

        mgr.register(res("r2", events));
        assertEquals(1, snapshot.size(), "已取出的快照不应随后续注册而变化");
        assertEquals(2, mgr.resources().size());
    }

    @Test
    void get_unknownName_throwsResourceNotFound() {
        ResourceManager mgr = new DefaultResourceManager();
        MetaPoolException e = assertThrows(MetaPoolException.class, () -> mgr.get("nope"));
        assertTrue(e.getMessage().contains("nope"), e.getMessage());
        assertTrue(mgr.find("nope").isEmpty());
    }

    @Test
    void tune_unknownResource_throwsResourceNotFound() {
        ResourceManager mgr = new DefaultResourceManager();
        assertThrows(MetaPoolException.class, () -> mgr.tune("nope", java.util.Map.of("k", "v")));
    }

    /**
     * 已启动之后再注册的资源<b>不会</b>被自动启动 —— 这是个容易踩的语义，用测试钉住并写明。
     *
     * <p>控制面不追踪"我已经 start 过了"，`start()` 只是"把当前注册表里的都启动一遍"。
     * 晚注册的资源需要调用方自己 start，或在注册齐全后再 start。
     */
    @Test
    void register_afterStart_isNotAutoStarted() {
        List<String> events = new ArrayList<>();
        ResourceManager mgr = new DefaultResourceManager(Duration.ZERO);
        mgr.register(res("early", events));
        mgr.start();
        mgr.register(res("late", events));

        assertEquals(List.of("start:early"), events, "late 不会被自动启动");

        mgr.close();
        assertTrue(events.contains("stop:late"),
                "但它已在注册表里，close 时会被一起停 —— 停一个没启动的资源必须安全（适配器幂等）");
    }

    /** close 之后再 start，等于把注册表里的资源重新启动一遍（依赖适配器支持重启，坑 P-01）。 */
    @Test
    void startAfterClose_restartsEverything() {
        List<String> events = new ArrayList<>();
        ResourceManager mgr = new DefaultResourceManager(Duration.ZERO);
        mgr.register(res("r1", events));

        mgr.start();
        mgr.close();
        events.clear();
        mgr.start();

        assertEquals(List.of("start:r1"), events);
        assertNotNull(mgr.get("r1"), "close 不注销资源，注册表保持不变");
        assertFalse(mgr.resources().isEmpty());
    }
}
