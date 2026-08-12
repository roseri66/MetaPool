package com.metapool.adapter.executor;

import com.metapool.common.capability.ManagedExecutor;
import com.metapool.common.exception.ErrorCode;
import com.metapool.common.exception.MetaPoolConfigException;
import com.metapool.common.exception.MetaPoolException;
import com.metapool.common.resource.ManagedResource;
import com.metapool.common.resource.ResourceTypes;
import com.metapool.common.resource.Tunable;
import com.metapool.common.stats.ExecutorStats;
import com.metapool.common.stats.HealthStatus;
import com.metapool.common.stats.TuneResult;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 把 JDK {@link ThreadPoolExecutor} 纳入 MetaPool 治理面的适配器。
 *
 * <h3>线程池不是池（P-07 的正面示范）</h3>
 * <p>本类<b>不实现</b> {@link com.metapool.common.capability.Pool}：你不「借出一个线程、用完归还」，
 * 你是<b>提交任务</b>。1.0 曾让自研线程池实现 {@code Pool} 语义的 {@code acquire()} 并直接
 * {@code throw new UnsupportedOperationException()} —— 这是台账 P-07 的原始现场。
 * 2.0 把功能性 API 拆成可选能力接口之后，「线程池没有 borrow」的表达方式是
 * <b>它压根不出现在 {@code Pool} 的子类型里</b>，于是误用在<b>编译期</b>就被拦下，而不是运行期抛异常。
 *
 * <h3>能力落点</h3>
 * <ul>
 *   <li>{@link com.metapool.common.resource.ManagedLifecycle} — start 建池；stop 三段式优雅停机（见下）</li>
 *   <li>{@link com.metapool.common.resource.MetricsSource} — 注册 {@code metapool.executor.*} 指标，打统一 tag</li>
 *   <li>{@link ManagedExecutor} — execute / submit / unwrap；<b>2.1 新接口的第一个实现</b></li>
 *   <li>{@link Tunable} — 经 JDK 原生 {@code setCorePoolSize} / {@code setMaximumPoolSize} 热调，无需重启</li>
 * </ul>
 *
 * <h3>优雅停机的三段式</h3>
 * <pre>
 *   shutdown()                 停止收新任务，队列中已排队的任务继续跑完
 *   awaitTermination(graceful) 在给定时限内等在跑的任务自己结束
 *   shutdownNow()              超时则中断（Duration.ZERO 直接走这一步）
 * </pre>
 * 停机后<b>字段置空</b>（坑 P-01：不置空会导致 stop→start 复用一个已关闭的池）。
 *
 * <h3>⚠️ 无界队列时 maximum-pool-size 不生效</h3>
 * <p>{@code queue-capacity} 默认为 {@link Integer#MAX_VALUE}（与 {@code Executors.newFixedThreadPool} 一致）。
 * {@code ThreadPoolExecutor} 的扩容规则是<b>「队列满了才开新线程」</b>，因此队列无界时线程数
 * 永远停在 {@code core-pool-size}，{@code maximum-pool-size} 形同虚设，任务只会在队列里无限堆积到 OOM。
 * 这是 JDK 的经典陷阱，MetaPool 不替它做决定，但如实写在这里：<b>要让 max 生效，就得配有界队列。</b>
 *
 * <h3>使用（编程式）</h3>
 * <pre>{@code
 * JdkExecutorAdapter ex = JdkExecutorAdapter.builder()
 *         .named("order-worker").corePoolSize(4).maximumPoolSize(8).queueCapacity(100).build();
 * ex.start();
 * CompletableFuture<String> f = ex.submit(() -> "done");
 * ex.stop(Duration.ofSeconds(5));
 * }</pre>
 *
 * @since 2.1.0
 */
public final class JdkExecutorAdapter implements ManagedResource, ManagedExecutor, Tunable {

    private static final Logger log = LoggerFactory.getLogger(JdkExecutorAdapter.class);

    /** 内置可热调参数（kebab-case，与 YAML/tunable 声明一致）。 */
    static final String KEY_CORE_POOL_SIZE = "core-pool-size";
    static final String KEY_MAXIMUM_POOL_SIZE = "maximum-pool-size";

    /**
     * 本适配器能够热调的全部参数。配置里声明的 {@code tunable} 白名单必须是它的子集 ——
     * 否则构建期即失败（fail-fast，RULES §3.3），不留到运维真去调参时才报（见坑 P-13）。
     *
     * <p>{@code queue-capacity} <b>刻意不在其中</b>：{@code ThreadPoolExecutor} 的队列在构造时确定，
     * 运行时无法扩缩。把它放进白名单等于承诺一件底层做不到的事 —— 那正是 P-07 的思路错误
     * （抽象层强加了实现履行不了的契约），只不过换到了调参这一面。
     * {@code keep-alive} 虽然 JDK 支持 {@code setKeepAliveTime}，但它只影响空闲线程回收速度，
     * 治理价值远低于两个 size，暂不纳入，保持白名单最小。
     */
    static final Set<String> SUPPORTED_TUNABLE_KEYS = Set.of(KEY_CORE_POOL_SIZE, KEY_MAXIMUM_POOL_SIZE);

    private final String name;
    private final int queueCapacity;
    private final Duration keepAlive;
    private final RejectionPolicy rejectionPolicy;
    private final Set<String> tunableKeys;

    /**
     * 当前核心/最大线程数。<b>调参时必须同时更新它们</b>（而不只是改运行中的 executor）——
     * {@link #start()} 是用这两个字段重建线程池的，只改运行中对象会导致
     * 「stop→start 后调参结果丢失」（坑 P-15，HikariAdapter 踩过）。
     */
    private volatile int corePoolSize;
    private volatile int maximumPoolSize;

    private volatile ThreadPoolExecutor executor;

    /** JDK 不统计拒绝次数（见 {@link ExecutorStats#rejectedCount()} 的约定），由适配器自持。 */
    private final AtomicLong totalRejected = new AtomicLong();

    JdkExecutorAdapter(String name, int corePoolSize, int maximumPoolSize, int queueCapacity,
                       Duration keepAlive, RejectionPolicy rejectionPolicy, Set<String> tunableKeys) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        if (corePoolSize < 0) {
            throw new MetaPoolConfigException("core-pool-size must not be negative, got " + corePoolSize);
        }
        if (maximumPoolSize < 1) {
            throw new MetaPoolConfigException("maximum-pool-size must be at least 1, got " + maximumPoolSize
                    + " (executor '" + name + "')");
        }
        if (maximumPoolSize < corePoolSize) {
            throw new MetaPoolConfigException("maximum-pool-size(" + maximumPoolSize
                    + ") must not be less than core-pool-size(" + corePoolSize + ") for executor '" + name + "'");
        }
        if (queueCapacity < 0) {
            throw new MetaPoolConfigException("queue-capacity must not be negative, got " + queueCapacity);
        }
        Objects.requireNonNull(keepAlive, "keepAlive must not be null");
        if (keepAlive.isNegative()) {
            throw new MetaPoolConfigException("keep-alive must not be negative, got " + keepAlive);
        }
        this.corePoolSize = corePoolSize;
        this.maximumPoolSize = maximumPoolSize;
        this.queueCapacity = queueCapacity;
        this.keepAlive = keepAlive;
        this.rejectionPolicy = Objects.requireNonNull(rejectionPolicy, "rejectionPolicy must not be null");
        this.tunableKeys = validateTunableKeys(name, tunableKeys);
    }

    /** 启动前就拒掉拼错/不支持的 tunable key，而不是等到调参时才返回 rejected（坑 P-13）。 */
    private static Set<String> validateTunableKeys(String name, Set<String> keys) {
        Objects.requireNonNull(keys, "tunableKeys must not be null");
        Set<String> unsupported = new LinkedHashSet<>(keys);
        unsupported.removeAll(SUPPORTED_TUNABLE_KEYS);
        if (!unsupported.isEmpty()) {
            throw new MetaPoolConfigException("executor '" + name + "' declares unsupported tunable key(s) "
                    + unsupported + "; supported: " + SUPPORTED_TUNABLE_KEYS);
        }
        return Set.copyOf(keys);
    }

    public static Builder builder() {
        return new Builder();
    }

    // ==================== ManagedResource ====================

    @Override
    public String name() {
        return name;
    }

    @Override
    public String type() {
        return ResourceTypes.EXECUTOR;
    }

    // ==================== ManagedLifecycle ====================

    @Override
    public synchronized void start() {
        if (executor != null) {
            return; // 幂等
        }
        // queue-capacity = 0 → SynchronousQueue（直接交接，不排队）；否则有界/无界 LinkedBlockingQueue
        BlockingQueue<Runnable> queue = queueCapacity == 0
                ? new SynchronousQueue<>()
                : new LinkedBlockingQueue<>(queueCapacity);
        executor = new ThreadPoolExecutor(corePoolSize, maximumPoolSize,
                keepAlive.toMillis(), TimeUnit.MILLISECONDS,
                queue, new MetaPoolThreadFactory(name), countingHandler());
        log.info("[MetaPool] executor '{}' started (core={}, max={}, queueCapacity={}, policy={})",
                name, corePoolSize, maximumPoolSize, queueCapacity, rejectionPolicy.configValue());
    }

    @Override
    public synchronized void stop(Duration graceful) {
        ThreadPoolExecutor e = this.executor;
        if (e == null) {
            return; // 幂等
        }
        e.shutdown();   // 停收新任务，已排队的继续跑
        try {
            if (graceful == null || graceful.isZero() || graceful.isNegative()) {
                e.shutdownNow();
            } else if (!e.awaitTermination(graceful.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("[MetaPool] executor '{}' did not terminate within {}, interrupting running tasks",
                        name, graceful);
                e.shutdownNow();
            }
        } catch (InterruptedException ie) {
            e.shutdownNow();
            Thread.currentThread().interrupt();   // 不吞中断标志
        } finally {
            // P-01：必须置空，否则 stop 之后再 start 会复用这个已关闭的池
            this.executor = null;
        }
        log.info("[MetaPool] executor '{}' stopped", name);
    }

    /**
     * 未启动为 DOWN；有界队列已满<b>且</b>线程数已达上限为 DEGRADED；其余 UP。
     *
     * <p>两个条件缺一不可：{@link SynchronousQueue} 的 {@code remainingCapacity()} <b>恒为 0</b>
     * （它本来就不存元素），只看队列会让所有直接交接型线程池永远显示 DEGRADED。
     * 加上「线程也满了」才是真正的饱和。
     *
     * <p>这也是控制面聚合优先级 DOWN &gt; DEGRADED &gt; UP（坑 P-06）第一次有资源真的会产出 DEGRADED。
     */
    @Override
    public HealthStatus health() {
        ThreadPoolExecutor e = this.executor;
        if (e == null) {
            return HealthStatus.down("not started");
        }
        if (e.getQueue().remainingCapacity() == 0 && e.getPoolSize() >= e.getMaximumPoolSize()) {
            return HealthStatus.degraded("saturated: queue full and poolSize reached maximum ("
                    + e.getMaximumPoolSize() + ")");
        }
        return HealthStatus.up();
    }

    /** 计数后委托给用户配置的原生策略 —— abort 策略在此原样抛出 {@code RejectedExecutionException}（不包装）。 */
    private RejectedExecutionHandler countingHandler() {
        RejectedExecutionHandler delegate = rejectionPolicy.jdkHandler();
        return (task, exec) -> {
            totalRejected.incrementAndGet();
            delegate.rejectedExecution(task, exec);
        };
    }

    // ==================== MetricsSource ====================

    @Override
    public void bindTo(MeterRegistry registry) {
        Tags tags = Tags.of("metapool.resource", name, "metapool.type", type());
        Gauge.builder("metapool.executor.active", this, a -> a.executorStats().activeCount())
                .tags(tags).register(registry);
        Gauge.builder("metapool.executor.pool.size", this, a -> a.executorStats().poolSize())
                .tags(tags).register(registry);
        Gauge.builder("metapool.executor.queue.size", this, a -> a.executorStats().queueSize())
                .tags(tags).register(registry);
        // 底层执行器自持的累计数：重启会归零（新池新计数），Prometheus 侧按 counter reset 处理
        FunctionCounter.builder("metapool.executor.completed.total", this,
                        a -> a.executorStats().completedTaskCount())
                .tags(tags).register(registry);
        // 适配器自持的累计数：跨重启单调递增
        FunctionCounter.builder("metapool.executor.rejected.total", totalRejected, AtomicLong::doubleValue)
                .tags(tags).register(registry);
    }

    // ==================== ManagedExecutor ====================

    @Override
    public void execute(Runnable task) {
        Objects.requireNonNull(task, "task must not be null");
        requireStarted().execute(task);
    }

    @Override
    public <T> CompletableFuture<T> submit(Callable<T> task) {
        Objects.requireNonNull(task, "task must not be null");
        ThreadPoolExecutor e = requireStarted();
        CompletableFuture<T> future = new CompletableFuture<>();
        // 刻意用 execute 而非 CompletableFuture.supplyAsync：饱和被拒时
        // RejectedExecutionException 必须【同步抛给调用方】，而不是变成一个异常完成的 future ——
        // 后者会让调用方误以为任务已被受理，只是失败了。受理与否和执行成败是两件事。
        e.execute(() -> {
            if (future.isDone()) {
                return;   // 已被取消，不必再跑
            }
            try {
                future.complete(task.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    @Override
    public ExecutorService unwrap() {
        return requireStarted();
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code completedTaskCount} 来自底层执行器，<b>stop→start 后归零</b>（重建了新池）；
     * {@code rejectedCount} 由适配器自持，跨重启单调递增。两者语义不同，看板上并列时需知道这点。
     */
    @Override
    public ExecutorStats executorStats() {
        ThreadPoolExecutor e = this.executor;
        if (e == null) {
            return new ExecutorStats(0, 0, corePoolSize, maximumPoolSize, 0, 0, 0L, totalRejected.get());
        }
        return new ExecutorStats(e.getActiveCount(), e.getPoolSize(),
                e.getCorePoolSize(), e.getMaximumPoolSize(),
                e.getQueue().size(), e.getQueue().remainingCapacity(),
                e.getCompletedTaskCount(), totalRejected.get());
    }

    // ==================== Tunable ====================

    @Override
    public Set<String> tunableKeys() {
        return tunableKeys;
    }

    /**
     * 热调核心/最大线程数，无需重启（🎯 头牌能力）。
     *
     * <p><b>两处不显然的地方：</b>
     * <ol>
     *   <li><b>先整体校验再落地</b>。{@code ThreadPoolExecutor} 的两个 setter 各自都会校验
     *       {@code core <= max}，逐个应用会在中间态触发 {@code IllegalArgumentException},
     *       留下「只改了一半」的线程池。所以这里先算出目标值组合，非法就整组拒绝。</li>
     *   <li><b>应用顺序随方向变化</b>。扩容（目标 max 变大）必须先抬 max 再抬 core，
     *       缩容必须先降 core 再降 max —— 否则中间态同样违反 {@code core <= max}。</li>
     * </ol>
     */
    @Override
    public TuneResult apply(Map<String, Object> patch) {
        Objects.requireNonNull(patch, "patch must not be null");
        ThreadPoolExecutor e = this.executor;
        Map<String, String> rejected = new LinkedHashMap<>();
        Set<String> applied = new LinkedHashSet<>();

        if (e == null) {
            patch.keySet().forEach(k -> rejected.put(k, "resource not started"));
            return TuneResult.partial(Set.of(), rejected);
        }

        Integer newCore = null;
        Integer newMax = null;
        for (Map.Entry<String, Object> entry : patch.entrySet()) {
            String key = entry.getKey();
            if (!tunableKeys.contains(key)) {
                rejected.put(key, "not in tunable whitelist " + tunableKeys);
                continue;
            }
            try {
                int v = parseSize(key, entry.getValue());
                if (KEY_CORE_POOL_SIZE.equals(key)) {
                    newCore = v;
                } else if (KEY_MAXIMUM_POOL_SIZE.equals(key)) {
                    newMax = v;
                } else {
                    rejected.put(key, "unsupported by JdkExecutorAdapter");
                }
            } catch (IllegalArgumentException iae) {
                rejected.put(key, iae.getMessage());
            }
        }

        int targetCore = newCore != null ? newCore : e.getCorePoolSize();
        int targetMax = newMax != null ? newMax : e.getMaximumPoolSize();
        if (targetCore > targetMax) {
            String reason = "resulting core-pool-size(" + targetCore
                    + ") would exceed maximum-pool-size(" + targetMax + ")";
            if (newCore != null) {
                rejected.put(KEY_CORE_POOL_SIZE, reason);
            }
            if (newMax != null) {
                rejected.put(KEY_MAXIMUM_POOL_SIZE, reason);
            }
            return TuneResult.partial(applied, rejected);
        }

        if (targetMax >= e.getMaximumPoolSize()) {   // 扩容：先抬上限
            applyMax(e, newMax, targetMax, applied);
            applyCore(e, newCore, targetCore, applied);
        } else {                                     // 缩容：先降核心
            applyCore(e, newCore, targetCore, applied);
            applyMax(e, newMax, targetMax, applied);
        }

        if (!applied.isEmpty()) {
            // P-15：同时写回「用于重建的配置」，否则 stop→start 会把调参结果丢掉
            this.corePoolSize = targetCore;
            this.maximumPoolSize = targetMax;
            log.info("[MetaPool] executor '{}' tuned core={}, max={}", name, targetCore, targetMax);
        }
        return TuneResult.partial(applied, rejected);
    }

    private static void applyCore(ThreadPoolExecutor e, Integer requested, int target, Set<String> applied) {
        if (requested != null) {
            e.setCorePoolSize(target);
            applied.add(KEY_CORE_POOL_SIZE);
        }
    }

    private static void applyMax(ThreadPoolExecutor e, Integer requested, int target, Set<String> applied) {
        if (requested != null) {
            e.setMaximumPoolSize(target);
            applied.add(KEY_MAXIMUM_POOL_SIZE);
        }
    }

    // ==================== helpers ====================

    private ThreadPoolExecutor requireStarted() {
        ThreadPoolExecutor e = this.executor;
        if (e == null) {
            throw new MetaPoolException(ErrorCode.INTERNAL, "executor '" + name + "' not started");
        }
        return e;
    }

    /** {@code core} 允许为 0（对应 cached 型线程池），{@code max} 至少为 1。 */
    private static int parseSize(String key, Object v) {
        int i;
        try {
            i = (v instanceof Number n) ? n.intValue() : Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException nfe) {
            throw new IllegalArgumentException("not an integer: " + v);
        }
        int min = KEY_MAXIMUM_POOL_SIZE.equals(key) ? 1 : 0;
        if (i < min) {
            throw new IllegalArgumentException("must be >= " + min + ", got " + i);
        }
        return i;
    }

    /**
     * 线程命名为 {@code metapool-{资源名}-{序号}}。
     *
     * <p>为什么值得单独做：线上 jstack / 火焰图里，默认的 {@code pool-3-thread-7} 完全看不出线程属于哪个
     * 被治理资源。治理面既然给了指标统一 tag，线程名也该能一眼归属 —— 排障时这是最省时间的一环。
     */
    private static final class MetaPoolThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger seq = new AtomicInteger(1);

        MetaPoolThreadFactory(String resourceName) {
            this.prefix = "metapool-" + resourceName + "-";
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + seq.getAndIncrement());
            t.setDaemon(false);   // 业务线程池，不设守护：停机由控制面负责，而非靠 JVM 退出强杀
            return t;
        }
    }

    /** 极简 fluent 构建器。 */
    public static final class Builder {
        private String name;
        private int corePoolSize = -1;
        private int maximumPoolSize = -1;
        private int queueCapacity = Integer.MAX_VALUE;
        private Duration keepAlive = Duration.ofSeconds(60);
        private RejectionPolicy rejectionPolicy = RejectionPolicy.ABORT;
        private Set<String> tunableKeys = SUPPORTED_TUNABLE_KEYS;

        private Builder() {
        }

        public Builder named(String name) {
            this.name = name;
            return this;
        }

        public Builder corePoolSize(int corePoolSize) {
            this.corePoolSize = corePoolSize;
            return this;
        }

        public Builder maximumPoolSize(int maximumPoolSize) {
            this.maximumPoolSize = maximumPoolSize;
            return this;
        }

        /** {@code 0} 表示 {@link SynchronousQueue}（不排队）；默认 {@link Integer#MAX_VALUE}（无界，注意类注释的陷阱）。 */
        public Builder queueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
            return this;
        }

        public Builder keepAlive(Duration keepAlive) {
            this.keepAlive = keepAlive;
            return this;
        }

        public Builder rejectionPolicy(RejectionPolicy rejectionPolicy) {
            this.rejectionPolicy = rejectionPolicy;
            return this;
        }

        public Builder tunable(Set<String> keys) {
            this.tunableKeys = keys;
            return this;
        }

        public JdkExecutorAdapter build() {
            if (name == null || name.isBlank()) {
                throw new MetaPoolConfigException("JdkExecutorAdapter requires a non-blank name");
            }
            if (corePoolSize < 0) {
                throw new MetaPoolConfigException("executor '" + name + "' requires 'core-pool-size'");
            }
            // max 未显式设置时跟随 core（等价于 Executors.newFixedThreadPool 的形状）
            int max = maximumPoolSize < 0 ? corePoolSize : maximumPoolSize;
            return new JdkExecutorAdapter(name, corePoolSize, max, queueCapacity,
                    keepAlive, rejectionPolicy, tunableKeys);
        }
    }
}
