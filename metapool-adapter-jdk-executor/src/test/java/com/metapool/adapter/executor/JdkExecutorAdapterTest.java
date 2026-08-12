package com.metapool.adapter.executor;

import com.metapool.common.capability.Pool;
import com.metapool.common.exception.MetaPoolConfigException;
import com.metapool.common.exception.MetaPoolException;
import com.metapool.common.resource.ManagedResource;
import com.metapool.common.spi.ResourceDefinition;
import com.metapool.common.stats.ExecutorStats;
import com.metapool.common.stats.HealthStatus;
import com.metapool.common.stats.TuneResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2.1 P1-① 验收：JDK 线程池纳入治理面，且验证 {@code ManagedExecutor} 这个 2.1 新能力接口抽对了。
 *
 * <p>时序敏感的场景（饱和、拒绝、DEGRADED）一律用 {@link CountDownLatch} 卡住工作线程来构造确定态，
 * 不用 sleep 猜时间 —— 坑 P-17 的教训：断言绑在挂钟上，本机连过 5 次、CI 一有负载就假失败。
 */
class JdkExecutorAdapterTest {

    /** 每个等待都给上限，避免用例卡死时把整个构建挂住。 */
    private static final long AWAIT_SECONDS = 5;

    private static JdkExecutorAdapter.Builder anExecutor(String name) {
        return JdkExecutorAdapter.builder().named(name).corePoolSize(2).maximumPoolSize(2).queueCapacity(10);
    }

    // ==================== 能力隔离（P-07） ====================

    /**
     * P-07 正面验收：线程池<b>不是池</b>。
     *
     * <p>1.0 的错误是让线程池实现 {@code Pool.acquire()} 后抛 {@code UnsupportedOperationException} ——
     * 错误只在运行期暴露。2.0 拆出可选能力接口后，「没有 borrow 语义」的表达方式是根本不出现在
     * {@code Pool} 的子类型里，误用在编译期就被拦住。
     */
    @Test
    void executor_isNotAPool_capabilitySegregation() {
        // 以 ManagedResource 承接：JdkExecutorAdapter 是 final 且不实现 Pool，
        // 对其直接做 instanceof Pool 会被编译器判为「不可能」而报错——这本身就是能力隔离的编译期证据。
        ManagedResource ex = anExecutor("worker").build();
        assertFalse(ex instanceof Pool, "线程池不是池：不应实现 Pool（P-07）");
        assertEquals("executor", ex.type());
    }

    /**
     * 5 条拍板决策之 ④：适配器自身也不得是 {@link ExecutorService} ——
     * 否则业务代码依然能拿到 {@code shutdown()}，控制面之外就有了第二个停机入口。
     */
    @Test
    void adapter_isNotAnExecutorService_noSecondShutdownEntrance() {
        assertFalse(ExecutorService.class.isAssignableFrom(JdkExecutorAdapter.class),
                "适配器不得是 ExecutorService：shutdown() 会成为绕过控制面的第二个停机入口");
    }

    // ==================== 生命周期 ====================

    @Test
    void lifecycle_executeAndSubmit() throws Exception {
        JdkExecutorAdapter ex = anExecutor("worker").build();
        assertEquals(HealthStatus.Status.DOWN, ex.health().status());

        ex.start();
        assertEquals(HealthStatus.Status.UP, ex.health().status());

        CountDownLatch ran = new CountDownLatch(1);
        ex.execute(ran::countDown);
        assertTrue(ran.await(AWAIT_SECONDS, TimeUnit.SECONDS), "execute 提交的任务应被执行");

        CompletableFuture<String> f = ex.submit(() -> "done");
        assertEquals("done", f.get(AWAIT_SECONDS, TimeUnit.SECONDS));

        ex.stop(Duration.ofSeconds(2));
        assertEquals(HealthStatus.Status.DOWN, ex.health().status());
    }

    /** 任务抛异常时，以 future 异常完成的形式传递，不影响线程池继续服务。 */
    @Test
    void submit_taskThrowing_completesFutureExceptionally() {
        JdkExecutorAdapter ex = anExecutor("worker").build();
        ex.start();
        try {
            CompletableFuture<String> f = ex.submit(() -> {
                throw new IllegalStateException("boom");
            });
            var thrown = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> f.get(AWAIT_SECONDS, TimeUnit.SECONDS));
            assertEquals("boom", thrown.getCause().getMessage());
        } finally {
            ex.stop(Duration.ZERO);
        }
    }

    /** 坑 P-01：stop() 必须置空底层字段，否则重启复用的是已关闭的池。 */
    @Test
    void restart_afterStop_yieldsUsableExecutor() throws Exception {
        JdkExecutorAdapter ex = anExecutor("worker").build();
        ex.start();
        assertEquals("first", ex.submit(() -> "first").get(AWAIT_SECONDS, TimeUnit.SECONDS));
        ex.stop(Duration.ZERO);
        assertEquals(HealthStatus.Status.DOWN, ex.health().status());

        ex.start();
        assertEquals(HealthStatus.Status.UP, ex.health().status());
        assertEquals("second", ex.submit(() -> "second").get(AWAIT_SECONDS, TimeUnit.SECONDS));
        ex.stop(Duration.ZERO);
    }

    /**
     * RULES §3.5 要求生命周期方法 start/stop 都 synchronized（坑 P-09：Bucket4j 的 stop 曾漏，
     * 与 start 竞争会把 stop 整个丢掉）。竞态不可确定性复现，故直接守护规则所述的不变量。
     */
    @Test
    void lifecycleMethods_areSynchronized_perRules() throws Exception {
        assertTrue(Modifier.isSynchronized(JdkExecutorAdapter.class.getMethod("start").getModifiers()),
                "start() 必须 synchronized（RULES §3.5）");
        assertTrue(Modifier.isSynchronized(
                        JdkExecutorAdapter.class.getMethod("stop", Duration.class).getModifiers()),
                "stop() 必须 synchronized（RULES §3.5）—— 否则与 start() 竞争会丢 stop");
    }

    @Test
    void stop_isIdempotent_andSafeWhenNeverStarted() {
        JdkExecutorAdapter ex = anExecutor("worker").build();
        ex.stop(Duration.ZERO);   // 从未启动
        assertEquals(HealthStatus.Status.DOWN, ex.health().status());

        ex.start();
        ex.stop(Duration.ZERO);
        ex.stop(Duration.ZERO);   // 重复停机
        assertEquals(HealthStatus.Status.DOWN, ex.health().status());
    }

    /** 优雅停机：已排队的任务应在 graceful 窗口内跑完，而不是被直接丢弃。 */
    @Test
    void stop_graceful_letsQueuedTaskFinish() throws Exception {
        JdkExecutorAdapter ex = JdkExecutorAdapter.builder()
                .named("worker").corePoolSize(1).maximumPoolSize(1).queueCapacity(10).build();
        ex.start();

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch queuedDone = new CountDownLatch(1);
        ex.execute(() -> {
            started.countDown();
            await(release);
        });
        assertTrue(started.await(AWAIT_SECONDS, TimeUnit.SECONDS));
        ex.execute(queuedDone::countDown);   // 排在队列里

        release.countDown();
        ex.stop(Duration.ofSeconds(AWAIT_SECONDS));
        assertEquals(0, queuedDone.getCount(), "graceful 期内排队任务应跑完，而非被丢弃");
    }

    @Test
    void useBeforeStart_throwsMetaPoolException() {
        JdkExecutorAdapter ex = anExecutor("worker").build();
        assertThrows(MetaPoolException.class, () -> ex.execute(() -> {
        }), "未启动时 MetaPool 自己产生的错误仍必须是 MetaPoolException（RULES §3.2 反向边界）");
        assertThrows(MetaPoolException.class, () -> ex.unwrap());
    }

    // ==================== 饱和与拒绝（决策 ⑤：透传不包装） ====================

    /**
     * 5 条拍板决策之 ⑤：饱和时 {@link RejectedExecutionException} <b>原样透传</b>，不包装成
     * {@code MetaPoolException} —— 该异常类型是生态契约的一部分（{@code CompletableFuture}、
     * Spring {@code @Async} 都按它做处理），包装即破坏互操作。同时验证适配器自持的拒绝计数。
     */
    @Test
    void saturation_abortPolicy_propagatesRejectedExecutionException_andCounts() throws Exception {
        JdkExecutorAdapter ex = JdkExecutorAdapter.builder()
                .named("tiny").corePoolSize(1).maximumPoolSize(1).queueCapacity(1)
                .rejectionPolicy(RejectionPolicy.ABORT).build();
        ex.start();
        CountDownLatch release = new CountDownLatch(1);
        try {
            occupyAndFill(ex, release);   // 1 个在跑 + 1 个在队列 = 满

            RejectedExecutionException rejected = assertThrows(RejectedExecutionException.class,
                    () -> ex.execute(() -> {
                    }), "abort 策略下必须原样抛 JDK 的 RejectedExecutionException，不得包装");
            // 用反射而非 instanceof：`rejected instanceof MetaPoolException` 会被编译器判为
            // 「不可能」而直接编译失败 —— 那其实是比任何断言都强的证据，可惜编译不过的代码留不下来。
            assertFalse(MetaPoolException.class.isInstance(rejected),
                    "不得包装成 MetaPoolException（决策 ⑤：别在接口层发明第二套饱和语义）");
            assertEquals(1, ex.executorStats().rejectedCount(), "JDK 不统计拒绝次数，须由适配器自持");
        } finally {
            release.countDown();
            ex.stop(Duration.ofSeconds(AWAIT_SECONDS));
        }
    }

    /**
     * {@code submit()} 被拒时也必须<b>同步抛出</b>，而不是返回一个异常完成的 future。
     *
     * <p>「没受理」和「受理了但失败了」是两件事：后者会让调用方以为任务已进入线程池，
     * 从而写出错误的重试/补偿逻辑。
     */
    @Test
    void saturation_submit_throwsSynchronously_notFailedFuture() throws Exception {
        JdkExecutorAdapter ex = JdkExecutorAdapter.builder()
                .named("tiny").corePoolSize(1).maximumPoolSize(1).queueCapacity(1).build();
        ex.start();
        CountDownLatch release = new CountDownLatch(1);
        try {
            occupyAndFill(ex, release);
            assertThrows(RejectedExecutionException.class, () -> ex.submit(() -> "never"),
                    "被拒必须同步抛出，不能伪装成一个异常完成的 future");
        } finally {
            release.countDown();
            ex.stop(Duration.ofSeconds(AWAIT_SECONDS));
        }
    }

    /** caller-runs：饱和时由提交线程自己跑，形成背压 —— 任务不丢，但提交方被拖慢。 */
    @Test
    void saturation_callerRunsPolicy_runsOnCallerThread() throws Exception {
        JdkExecutorAdapter ex = JdkExecutorAdapter.builder()
                .named("tiny").corePoolSize(1).maximumPoolSize(1).queueCapacity(1)
                .rejectionPolicy(RejectionPolicy.CALLER_RUNS).build();
        ex.start();
        CountDownLatch release = new CountDownLatch(1);
        try {
            occupyAndFill(ex, release);

            AtomicReference<String> ranOn = new AtomicReference<>();
            ex.execute(() -> ranOn.set(Thread.currentThread().getName()));
            assertEquals(Thread.currentThread().getName(), ranOn.get(),
                    "caller-runs 应在提交线程上同步执行");
            assertEquals(1, ex.executorStats().rejectedCount(), "被拒仍应计数，即使最终由调用线程执行");
        } finally {
            release.countDown();
            ex.stop(Duration.ofSeconds(AWAIT_SECONDS));
        }
    }

    // ==================== 健康 ====================

    /** 饱和（队列满 + 线程到顶）时报 DEGRADED —— 控制面聚合优先级 DOWN &gt; DEGRADED &gt; UP（P-06）终于有真实来源。 */
    @Test
    void health_isDegraded_whenSaturated() throws Exception {
        JdkExecutorAdapter ex = JdkExecutorAdapter.builder()
                .named("tiny").corePoolSize(1).maximumPoolSize(1).queueCapacity(1).build();
        ex.start();
        CountDownLatch release = new CountDownLatch(1);
        try {
            occupyAndFill(ex, release);
            assertEquals(HealthStatus.Status.DEGRADED, ex.health().status());
            assertTrue(ex.health().detail().contains("saturated"), ex.health().detail());
        } finally {
            release.countDown();
            ex.stop(Duration.ofSeconds(AWAIT_SECONDS));
        }
    }

    /**
     * 陷阱守护：{@link java.util.concurrent.SynchronousQueue} 的 {@code remainingCapacity()} <b>恒为 0</b>，
     * 若健康判断只看队列，所有「不排队」型线程池都会永远显示 DEGRADED。必须叠加「线程数已达上限」。
     */
    @Test
    void health_isUp_forIdleSynchronousQueueExecutor() {
        JdkExecutorAdapter ex = JdkExecutorAdapter.builder()
                .named("direct").corePoolSize(0).maximumPoolSize(2).queueCapacity(0).build();
        ex.start();
        try {
            assertEquals(0, ex.executorStats().queueRemainingCapacity(),
                    "SynchronousQueue 的 remainingCapacity 恒为 0 —— 这正是只看队列会误判的原因");
            assertEquals(HealthStatus.Status.UP, ex.health().status(),
                    "空闲的直接交接型线程池不应被误判为 DEGRADED");
        } finally {
            ex.stop(Duration.ZERO);
        }
    }

    // ==================== 指标 ====================

    @Test
    void metrics_unifiedTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        JdkExecutorAdapter ex = anExecutor("worker").build();
        ex.bindTo(registry);
        ex.start();
        try {
            assertNotNull(registry.find("metapool.executor.active")
                    .tag("metapool.resource", "worker")
                    .tag("metapool.type", "executor").gauge());
            assertNotNull(registry.find("metapool.executor.pool.size")
                    .tag("metapool.resource", "worker").gauge());
            assertNotNull(registry.find("metapool.executor.queue.size")
                    .tag("metapool.resource", "worker").gauge());
            assertNotNull(registry.find("metapool.executor.completed.total")
                    .tag("metapool.resource", "worker").functionCounter());
            assertEquals(0.0, registry.get("metapool.executor.rejected.total")
                    .tag("metapool.resource", "worker").functionCounter().count());
        } finally {
            ex.stop(Duration.ZERO);
        }
    }

    /**
     * 线程名带资源名：线上 jstack 里默认的 {@code pool-3-thread-7} 看不出归属，
     * 治理面既然统一了指标 tag，线程名也该能一眼归属。
     */
    @Test
    void threads_areNamedAfterTheResource() throws Exception {
        JdkExecutorAdapter ex = anExecutor("order-worker").build();
        ex.start();
        try {
            String threadName = ex.submit(() -> Thread.currentThread().getName())
                    .get(AWAIT_SECONDS, TimeUnit.SECONDS);
            assertTrue(threadName.startsWith("metapool-order-worker-"), threadName);
        } finally {
            ex.stop(Duration.ZERO);
        }
    }

    // ==================== 动态调参 ====================

    /**
     * 一个 patch 同时改两个 size 时的<b>顺序陷阱</b>：{@code ThreadPoolExecutor} 的两个 setter
     * 各自都校验 {@code core <= max}，逐个应用会在中间态抛 {@code IllegalArgumentException}。
     * 扩容必须先抬 max，缩容必须先降 core。
     *
     * <p>注意 {@link Map#of} 的迭代顺序<b>未定义</b> —— 正因如此，正确性不能依赖 patch 的遍历顺序，
     * 必须由实现显式排序。
     */
    @Test
    void tune_bothSizesInOnePatch_appliesInSafeOrder() {
        JdkExecutorAdapter ex = JdkExecutorAdapter.builder()
                .named("worker").corePoolSize(1).maximumPoolSize(2).queueCapacity(10).build();
        ex.start();
        try {
            // 扩容：目标 core(4) 大于当前 max(2)，若先设 core 必抛 IllegalArgumentException
            TuneResult grow = ex.apply(Map.of("core-pool-size", 4, "maximum-pool-size", 4));
            assertTrue(grow.success(), "扩容应成功，rejected=" + grow.rejected());
            assertEquals(Set.of("core-pool-size", "maximum-pool-size"), grow.applied());
            assertEquals(4, ex.executorStats().corePoolSize());
            assertEquals(4, ex.executorStats().maximumPoolSize());

            // 缩容：目标 max(1) 小于当前 core(4)，若先设 max 必抛 IllegalArgumentException
            TuneResult shrink = ex.apply(Map.of("core-pool-size", 1, "maximum-pool-size", 1));
            assertTrue(shrink.success(), "缩容应成功，rejected=" + shrink.rejected());
            assertEquals(1, ex.executorStats().corePoolSize());
            assertEquals(1, ex.executorStats().maximumPoolSize());
        } finally {
            ex.stop(Duration.ZERO);
        }
    }

    /** 单独调 core 到超过当前 max 时，整组拒绝，不留下「只改了一半」的线程池。 */
    @Test
    void tune_coreExceedingMax_isRejectedWholesale() {
        JdkExecutorAdapter ex = JdkExecutorAdapter.builder()
                .named("worker").corePoolSize(1).maximumPoolSize(2).queueCapacity(10).build();
        ex.start();
        try {
            TuneResult r = ex.apply(Map.of("core-pool-size", 8));
            assertFalse(r.success());
            assertTrue(r.rejected().containsKey("core-pool-size"), r.rejected().toString());
            assertEquals(1, ex.executorStats().corePoolSize(), "被拒时不得改动运行中的线程池");
            assertEquals(2, ex.executorStats().maximumPoolSize());
        } finally {
            ex.stop(Duration.ZERO);
        }
    }

    /**
     * 坑 P-15：{@code apply()} 必须同时更新「运行中的对象」和「用于重建的配置」，
     * 否则 stop→start 后调参结果静默回退（HikariAdapter 踩过这个坑）。
     */
    @Test
    void tune_survivesRestart_perPitfallP15() {
        JdkExecutorAdapter ex = JdkExecutorAdapter.builder()
                .named("worker").corePoolSize(1).maximumPoolSize(2).queueCapacity(10).build();
        ex.start();
        try {
            assertTrue(ex.apply(Map.of("core-pool-size", 3, "maximum-pool-size", 6)).success());

            ex.stop(Duration.ZERO);
            ex.start();   // 重建线程池

            assertEquals(3, ex.executorStats().corePoolSize(), "重启后调参结果不得丢失（P-15）");
            assertEquals(6, ex.executorStats().maximumPoolSize());
        } finally {
            ex.stop(Duration.ZERO);
        }
    }

    @Test
    void tune_rejectsNonWhitelistedAndInvalidValues() {
        JdkExecutorAdapter ex = anExecutor("worker").build();
        ex.start();
        try {
            TuneResult notWhitelisted = ex.apply(Map.of("queue-capacity", 99));
            assertFalse(notWhitelisted.success());
            assertTrue(notWhitelisted.rejected().containsKey("queue-capacity"),
                    "queue-capacity 运行时改不了，不在白名单");

            TuneResult notANumber = ex.apply(Map.of("core-pool-size", "abc"));
            assertFalse(notANumber.success());
            assertTrue(notANumber.rejected().containsKey("core-pool-size"));

            TuneResult negative = ex.apply(Map.of("maximum-pool-size", 0));
            assertFalse(negative.success(), "maximum-pool-size 至少为 1");
        } finally {
            ex.stop(Duration.ZERO);
        }
    }

    @Test
    void tune_beforeStart_isRejected() {
        JdkExecutorAdapter ex = anExecutor("worker").build();
        TuneResult r = ex.apply(Map.of("core-pool-size", 4));
        assertFalse(r.success());
        assertEquals("resource not started", r.rejected().get("core-pool-size"));
    }

    /** 坑 P-13：不支持的 tunable key 必须构建期就报，而不是等运维真去调参时才返回 rejected。 */
    @Test
    void unsupportedTunableKey_failsFastAtBuildTime() {
        MetaPoolConfigException e = assertThrows(MetaPoolConfigException.class,
                () -> anExecutor("worker").tunable(Set.of("queue-capacity")).build());
        assertTrue(e.getMessage().contains("queue-capacity"), e.getMessage());

        var def = new ResourceDefinition("worker", "executor",
                Map.of("core-pool-size", 2), Set.of("core-poolsize"));   // 拼错
        assertThrows(MetaPoolConfigException.class, () -> new JdkExecutorAdapterFactory().create(def));
    }

    // ==================== 工厂与配置校验 ====================

    @Test
    void factory_viaResourceDefinition() {
        JdkExecutorAdapterFactory factory = new JdkExecutorAdapterFactory();
        assertEquals("executor", factory.supportedType());

        Map<String, Object> props = new HashMap<>();
        props.put("core-pool-size", 2);
        props.put("maximum-pool-size", 4);
        props.put("queue-capacity", 50);
        props.put("keep-alive", "30s");
        props.put("rejection-policy", "caller-runs");
        var def = new ResourceDefinition("order-worker", "executor", props, Set.of("maximum-pool-size"));

        ManagedResource resource = factory.create(def);
        assertEquals("order-worker", resource.name());
        resource.start();
        try {
            assertEquals(HealthStatus.Status.UP, resource.health().status());
            ExecutorStats stats = ((JdkExecutorAdapter) resource).executorStats();
            assertEquals(2, stats.corePoolSize());
            assertEquals(4, stats.maximumPoolSize());
            assertEquals(50, stats.queueRemainingCapacity());
        } finally {
            resource.stop(Duration.ZERO);
        }
    }

    /** 无 queue-capacity 时为无界队列，约定返回 {@code Integer.MAX_VALUE}（见 ExecutorStats javadoc）。 */
    @Test
    void factory_defaultQueue_isUnbounded() {
        var def = new ResourceDefinition("w", "executor", Map.of("core-pool-size", 1), Set.of());
        ManagedResource r = new JdkExecutorAdapterFactory().create(def);
        r.start();
        try {
            assertEquals(Integer.MAX_VALUE, ((JdkExecutorAdapter) r).executorStats().queueRemainingCapacity());
        } finally {
            r.stop(Duration.ZERO);
        }
    }

    @Test
    void factory_invalidConfig_failsFastWithMetaPoolConfigException() {
        JdkExecutorAdapterFactory factory = new JdkExecutorAdapterFactory();

        assertThrows(MetaPoolConfigException.class, () -> factory.create(
                new ResourceDefinition("w", "executor", Map.of(), Set.of())), "缺 core-pool-size 应报错");

        assertThrows(MetaPoolConfigException.class, () -> factory.create(
                new ResourceDefinition("w", "executor", Map.of("core-pool-size", "abc"), Set.of())));

        assertThrows(MetaPoolConfigException.class, () -> factory.create(
                new ResourceDefinition("w", "executor",
                        Map.of("core-pool-size", 4, "maximum-pool-size", 2), Set.of())),
                "max < core 应启动即失败，而不是等到运行时");

        assertThrows(MetaPoolConfigException.class, () -> factory.create(
                new ResourceDefinition("w", "executor",
                        Map.of("core-pool-size", 1, "rejection-policy", "abrot"), Set.of())),
                "拼错的策略名不得被静默降级成默认值");

        assertThrows(MetaPoolConfigException.class, () -> factory.create(
                new ResourceDefinition("w", "executor", Map.of("core-pool-size", 0), Set.of())),
                "core=0 且未给 max 时，max 跟随 core 为 0，应报「至少为 1」");
    }

    @Test
    void factory_parseDuration_variants() {
        assertEquals(Duration.ofSeconds(60), JdkExecutorAdapterFactory.parseDuration("60s"));
        assertEquals(Duration.ofMillis(500), JdkExecutorAdapterFactory.parseDuration("500ms"));
        assertEquals(Duration.ofMinutes(2), JdkExecutorAdapterFactory.parseDuration("2m"));
        assertEquals(Duration.ofMillis(250), JdkExecutorAdapterFactory.parseDuration(250));
        assertEquals(Duration.ofSeconds(3), JdkExecutorAdapterFactory.parseDuration("PT3S"));
    }

    @Test
    void rejectionPolicy_parsing_isCaseAndSeparatorInsensitive() {
        assertEquals(RejectionPolicy.ABORT, RejectionPolicy.from("abort"));
        assertEquals(RejectionPolicy.CALLER_RUNS, RejectionPolicy.from("CALLER_RUNS"));
        assertEquals(RejectionPolicy.DISCARD_OLDEST, RejectionPolicy.from("discard-oldest"));
        assertThrows(MetaPoolConfigException.class, () -> RejectionPolicy.from("nope"));
    }

    // ==================== helpers ====================

    /**
     * 把线程池填成确定的「满」状态：core=max=1、queueCapacity=1 时，
     * 1 个任务占住唯一线程 + 1 个任务占住唯一队列位 —— 此后任何提交都必然被拒。
     */
    private static void occupyAndFill(JdkExecutorAdapter ex, CountDownLatch release) throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        ex.execute(() -> {
            started.countDown();
            await(release);
        });
        assertTrue(started.await(AWAIT_SECONDS, TimeUnit.SECONDS), "占位任务应已开始执行");
        ex.execute(() -> await(release));   // 填满队列
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(AWAIT_SECONDS * 2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch not released in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
