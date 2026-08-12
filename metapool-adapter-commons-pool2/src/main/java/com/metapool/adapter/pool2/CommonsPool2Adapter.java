package com.metapool.adapter.pool2;

import com.metapool.common.capability.Pool;
import com.metapool.common.exception.ErrorCode;
import com.metapool.common.exception.MetaPoolConfigException;
import com.metapool.common.exception.MetaPoolException;
import com.metapool.common.exception.PoolExhaustedException;
import com.metapool.common.resource.ManagedResource;
import com.metapool.common.resource.ResourceTypes;
import com.metapool.common.resource.Tunable;
import com.metapool.common.spi.ConfigValues;
import com.metapool.common.stats.HealthStatus;
import com.metapool.common.stats.PoolStats;
import com.metapool.common.stats.TuneResult;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.apache.commons.pool2.PooledObjectFactory;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 把 Apache Commons Pool2 的 {@link GenericObjectPool} 纳入 MetaPool 治理面的适配器。
 *
 * <h3>它和 HikariAdapter 的根本区别：没有「自带工厂」</h3>
 * <p>HikariCP 知道怎么造一个 {@code Connection}（给个 JDBC URL 就够了）；
 * Commons Pool2 <b>不知道怎么造 {@code T}</b> —— 它必须拿到调用方提供的
 * {@link PooledObjectFactory}。因此本资源<b>无法只靠 YAML 建出来</b>：
 * <ul>
 *   <li><b>编程式（首选）</b>：{@code builder().named("buf").factory(myFactory)}，类型精确；</li>
 *   <li><b>YAML</b>：配 {@code factory-class}（要求<b>无参构造</b>），由
 *       {@link CommonsPool2AdapterFactory} 反射实例化。</li>
 * </ul>
 * 之所以保留 YAML 路径，是为了不破坏 SPI 对称性 —— 否则 {@code ResourceAdapterFactory.create()}
 * 只能抛异常，这个 adapter 就成了二等公民。
 *
 * <h3>泛型与 SPI 的阻抗</h3>
 * <p>本类是泛型的，但 {@code ResourceAdapterFactory.create()} 返回非泛型的
 * {@link ManagedResource}，所以走 YAML 的实例实际是 {@code CommonsPool2Adapter<Object>}。
 * 这是 Java 泛型擦除下的必然，不是设计缺陷：编程式接入仍可拿到精确类型。
 *
 * <h3>🎯 {@code borrow(Duration)} 在本适配器上是「真超时」</h3>
 * <p>注意与 {@code HikariAdapter} 的差别：Hikari 的获取超时只能由配置项
 * {@code connectionTimeout} 统一治理，逐次传入的 {@code timeout} 仅作提示；
 * 而 Commons Pool2 原生支持 {@code borrowObject(Duration)}，因此本适配器的
 * {@link #borrow(Duration)} <b>确实按传入值限时</b>。
 *
 * <p>同一个接口方法在两个实现上语义强弱不同，两边 javadoc 都写明，别让人以为哪儿都一样。
 *
 * <h3>能力落点</h3>
 * <ul>
 *   <li>{@link com.metapool.common.resource.ManagedLifecycle} — start 建池；stop 先 drain 再 close</li>
 *   <li>{@link com.metapool.common.resource.MetricsSource} — {@code metapool.object.*}，统一 tag</li>
 *   <li>{@link Pool}{@code <T>} — borrow / release / poolStats</li>
 *   <li>{@link Tunable} — 热调 max-total / max-idle / min-idle / max-wait</li>
 * </ul>
 *
 * @param <T> 池中管理的对象类型
 * @since 2.1.0
 */
public final class CommonsPool2Adapter<T> implements ManagedResource, Pool<T>, Tunable {

    private static final Logger log = LoggerFactory.getLogger(CommonsPool2Adapter.class);

    /** 内置可热调参数（kebab-case，与 YAML/tunable 声明一致）。 */
    static final String KEY_MAX_TOTAL = "max-total";
    static final String KEY_MAX_IDLE = "max-idle";
    static final String KEY_MIN_IDLE = "min-idle";
    static final String KEY_MAX_WAIT = "max-wait";

    /**
     * 本适配器能够热调的全部参数。配置里声明的 {@code tunable} 白名单必须是它的子集 ——
     * 否则构建期即失败（fail-fast，RULES §3.3），不留到运维真去调参时才报（见坑 P-13）。
     *
     * <p>四个都是 Commons Pool2 原生的运行时可写属性。与 jdk-executor 不同，这里
     * <b>没有 P-19 那种顺序陷阱</b>：Pool2 的 setter 之间不做交叉校验
     * （{@code minIdle > maxIdle} 不抛异常，只是行为上取小），所以不需要按方向排序。
     * 该判断有测试坐实（{@code tune_minIdleAboveMaxIdle_isAcceptedByPool2_noOrderingHazard}）。
     */
    static final Set<String> SUPPORTED_TUNABLE_KEYS =
            Set.of(KEY_MAX_TOTAL, KEY_MAX_IDLE, KEY_MIN_IDLE, KEY_MAX_WAIT);

    /** 停机 drain 时的轮询间隔。取值小到不拖慢停机，大到不空转烧 CPU。 */
    private static final Duration DRAIN_POLL = Duration.ofMillis(50);

    private final String name;
    private final PooledObjectFactory<T> factory;
    private final Set<String> tunableKeys;

    /**
     * 当前配置值。<b>调参时必须同时更新它们</b>（而不只是改运行中的池）——
     * {@link #start()} 是用这些字段重建池的，只改运行中对象会导致
     * 「stop→start 后调参结果丢失」（坑 P-15，HikariAdapter 踩过）。
     */
    private volatile int maxTotal;
    private volatile int maxIdle;
    private volatile int minIdle;
    private volatile Duration maxWait;

    private volatile GenericObjectPool<T> pool;

    private final AtomicLong totalBorrowed = new AtomicLong();
    private final AtomicLong totalReleased = new AtomicLong();

    CommonsPool2Adapter(String name, PooledObjectFactory<T> factory, int maxTotal, int maxIdle,
                        int minIdle, Duration maxWait, Set<String> tunableKeys) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.factory = Objects.requireNonNull(factory, "factory must not be null");
        if (maxTotal == 0) {
            throw new MetaPoolConfigException("max-total must not be 0 (use a positive value, "
                    + "or a negative value for unlimited) for object pool '" + name + "'");
        }
        if (minIdle < 0) {
            throw new MetaPoolConfigException("min-idle must not be negative, got " + minIdle);
        }
        this.maxTotal = maxTotal;
        this.maxIdle = maxIdle;
        this.minIdle = minIdle;
        this.maxWait = Objects.requireNonNull(maxWait, "maxWait must not be null");
        this.tunableKeys = validateTunableKeys(name, tunableKeys);
    }

    /** 启动前就拒掉拼错/不支持的 tunable key，而不是等到调参时才返回 rejected（坑 P-13）。 */
    private static Set<String> validateTunableKeys(String name, Set<String> keys) {
        Objects.requireNonNull(keys, "tunableKeys must not be null");
        Set<String> unsupported = new LinkedHashSet<>(keys);
        unsupported.removeAll(SUPPORTED_TUNABLE_KEYS);
        if (!unsupported.isEmpty()) {
            throw new MetaPoolConfigException("object pool '" + name + "' declares unsupported tunable key(s) "
                    + unsupported + "; supported: " + SUPPORTED_TUNABLE_KEYS);
        }
        return Set.copyOf(keys);
    }

    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    // ==================== ManagedResource ====================

    @Override
    public String name() {
        return name;
    }

    @Override
    public String type() {
        return ResourceTypes.OBJECT;
    }

    // ==================== ManagedLifecycle ====================

    @Override
    public synchronized void start() {
        if (pool != null) {
            return; // 幂等
        }
        GenericObjectPoolConfig<T> config = new GenericObjectPoolConfig<>();
        config.setMaxTotal(maxTotal);
        config.setMaxIdle(maxIdle);
        config.setMinIdle(minIdle);
        config.setMaxWait(maxWait);
        // 不开 JMX：治理面已经把指标统一导出到 Micrometer，再开一套 JMX 名字空间
        // 等于第二套可观测入口，且多实例时 Pool2 的 JMX 名会互相打架。
        config.setJmxEnabled(false);
        pool = new GenericObjectPool<>(factory, config);
        log.info("[MetaPool] object pool '{}' started (maxTotal={}, maxIdle={}, minIdle={}, maxWait={})",
                name, maxTotal, maxIdle, minIdle, maxWait);
    }

    /**
     * 优雅停机：先在 {@code graceful} 期内等待已借出对象归还（drain），超时后强制 {@code close()}。
     *
     * <p>{@code GenericObjectPool.close()} <b>不会</b>等待借出对象归还，所以 drain 必须由这里做 ——
     * 直接 close 等于把在用对象连同池一起丢掉，正是 1.0 {@code destroy()} 强杀在用资源的老毛病。
     */
    @Override
    public synchronized void stop(Duration graceful) {
        GenericObjectPool<T> p = this.pool;
        if (p == null) {
            return; // 幂等
        }
        try {
            drain(p, graceful);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();   // 不吞中断标志
        } finally {
            try {
                p.close();
            } catch (RuntimeException e) {
                log.warn("[MetaPool] object pool '{}' close raised", name, e);
            }
            // P-01：必须置空，否则 stop 之后再 start 会复用这个已关闭的池
            this.pool = null;
        }
        log.info("[MetaPool] object pool '{}' stopped", name);
    }

    private void drain(GenericObjectPool<T> p, Duration graceful) throws InterruptedException {
        if (graceful == null || graceful.isZero() || graceful.isNegative()) {
            return;
        }
        long deadline = System.nanoTime() + graceful.toNanos();
        while (p.getNumActive() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(DRAIN_POLL.toMillis());
        }
        int leaked = p.getNumActive();
        if (leaked > 0) {
            log.warn("[MetaPool] object pool '{}' still has {} object(s) borrowed after {}; closing anyway",
                    name, leaked, graceful);
        }
    }

    /**
     * 未启动为 DOWN；池已耗尽<b>且</b>有线程在等待为 DEGRADED；其余 UP。
     *
     * <p>两个条件缺一不可：仅「借满」不算故障（正是池在满负荷工作），
     * <b>有人排队等不到</b>才是治理该发出的信号。判据与 jdk-executor 的饱和判定同构。
     *
     * <p>{@code maxTotal} 为负数表示无上限，此时不可能耗尽，直接 UP。
     */
    @Override
    public HealthStatus health() {
        GenericObjectPool<T> p = this.pool;
        if (p == null || p.isClosed()) {
            return HealthStatus.down("not started");
        }
        int max = p.getMaxTotal();
        if (max > 0 && p.getNumActive() >= max && p.getNumWaiters() > 0) {
            return HealthStatus.degraded("exhausted: " + p.getNumActive() + "/" + max
                    + " borrowed, " + p.getNumWaiters() + " waiting");
        }
        return HealthStatus.up();
    }

    // ==================== MetricsSource ====================

    @Override
    public void bindTo(MeterRegistry registry) {
        Tags tags = Tags.of("metapool.resource", name, "metapool.type", type());
        gauge(registry, "metapool.object.active", tags, GenericObjectPool::getNumActive);
        gauge(registry, "metapool.object.idle", tags, GenericObjectPool::getNumIdle);
        gauge(registry, "metapool.object.pending", tags, GenericObjectPool::getNumWaiters);
        FunctionCounter.builder("metapool.object.borrowed.total", totalBorrowed, AtomicLong::doubleValue)
                .tags(tags).register(registry);
        FunctionCounter.builder("metapool.object.released.total", totalReleased, AtomicLong::doubleValue)
                .tags(tags).register(registry);
    }

    private void gauge(MeterRegistry registry, String metric, Tags tags, Pool2Gauge<T> fn) {
        Gauge.builder(metric, this, self -> self.sample(fn)).tags(tags).register(registry);
    }

    /** 采样网关：池未启动或已关闭时返回 0，绝不抛异常（指标读取不该把调用方带崩）。 */
    private double sample(Pool2Gauge<T> fn) {
        GenericObjectPool<T> p = this.pool;
        if (p == null || p.isClosed()) {
            return 0d;
        }
        try {
            return fn.read(p);
        } catch (RuntimeException e) {
            return 0d;
        }
    }

    @FunctionalInterface
    private interface Pool2Gauge<T> {
        int read(GenericObjectPool<T> pool);
    }

    // ==================== Pool<T> ====================

    @Override
    public T borrow() throws InterruptedException {
        GenericObjectPool<T> p = requireStarted();
        T obj = doBorrow(() -> p.borrowObject());
        totalBorrowed.incrementAndGet();
        return obj;
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>本适配器是真超时</b>：Commons Pool2 原生支持 {@code borrowObject(Duration)}，
     * 因此这里确实按传入的 {@code timeout} 限时，而不是像 {@code HikariAdapter} 那样
     * 以配置项为准、参数仅作提示。超时统一映射为 {@link PoolExhaustedException}。
     */
    @Override
    public T borrow(Duration timeout) throws InterruptedException, PoolExhaustedException {
        Objects.requireNonNull(timeout, "timeout must not be null");
        GenericObjectPool<T> p = requireStarted();
        T obj = doBorrow(() -> p.borrowObject(timeout));
        totalBorrowed.incrementAndGet();
        return obj;
    }

    /**
     * 统一异常映射。
     *
     * <p>Commons Pool2 的 {@code borrowObject} 声明 {@code throws Exception}（因为工厂可以抛任何东西），
     * 这里把它收敛回 MetaPool 契约：
     * <ul>
     *   <li>{@link NoSuchElementException}（耗尽 / 等待超时）→ {@link PoolExhaustedException}</li>
     *   <li>{@link InterruptedException} → 原样透传（它是 JDK 的既定中断语义，包装即破坏互操作，
     *       同 RULES §3.2 对 {@code RejectedExecutionException} 的处理）</li>
     *   <li>其余（多半来自使用方自己的工厂）→ {@link MetaPoolException} 并挂上原因</li>
     * </ul>
     */
    private T doBorrow(BorrowCall<T> call) throws InterruptedException {
        try {
            return call.get();
        } catch (NoSuchElementException e) {
            throw new PoolExhaustedException(
                    "object pool '" + name + "' exhausted or borrow timed out", e);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            throw new MetaPoolException(ErrorCode.INTERNAL,
                    "object pool '" + name + "' borrow failed: " + e.getMessage(), e);
        }
    }

    @FunctionalInterface
    private interface BorrowCall<T> {
        T get() throws Exception;
    }

    /**
     * {@inheritDoc}
     *
     * <p>归还非本池或已归还的对象时<b>静默忽略并记 WARN</b>（契约要求）。Commons Pool2 在这种情况下
     * 抛 {@code IllegalStateException}，若放任它冒出去，会在使用方的 {@code finally} 块里
     * 掩盖掉业务的原始异常。
     */
    @Override
    public void release(T resource) {
        if (resource == null) {
            return;
        }
        GenericObjectPool<T> p = this.pool;
        if (p == null || p.isClosed()) {
            log.warn("[MetaPool] object pool '{}' release ignored: pool not running", name);
            return;
        }
        try {
            p.returnObject(resource);
            totalReleased.incrementAndGet();
        } catch (IllegalStateException e) {
            log.warn("[MetaPool] object pool '{}' release ignored (not from this pool or already returned): {}",
                    name, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("[MetaPool] object pool '{}' release failed", name, e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>计数口径</b>：{@code totalBorrowed} / {@code totalReleased} 只统计经本适配器
     * {@link #borrow()} / {@link #release(Object)} 的流量，两者可比、可对账（坑 P-12 的教训：
     * 凡「归还」由底层对象自己完成、适配器观测不到的路径，就别记计数器）。
     * 本适配器不暴露绕过 {@code Pool} 的原生入口，故不存在失真。
     */
    @Override
    public PoolStats poolStats() {
        GenericObjectPool<T> p = this.pool;
        if (p == null || p.isClosed()) {
            return new PoolStats(0, 0, 0, totalBorrowed.get(), totalReleased.get());
        }
        return new PoolStats(p.getNumActive(), p.getNumIdle(), p.getNumWaiters(),
                totalBorrowed.get(), totalReleased.get());
    }

    // ==================== Tunable ====================

    @Override
    public Set<String> tunableKeys() {
        return tunableKeys;
    }

    /**
     * 热调池容量，无需重启。
     *
     * <p>与 jdk-executor 不同，这里<b>不需要按方向排序</b>：Commons Pool2 的四个 setter
     * 互不校验（{@code minIdle > maxIdle} 也照单全收，只是行为上取小），不存在
     * 「中间态非法」的问题（对照坑 P-19）。
     */
    @Override
    public TuneResult apply(Map<String, Object> patch) {
        Objects.requireNonNull(patch, "patch must not be null");
        GenericObjectPool<T> p = this.pool;
        Map<String, String> rejected = new LinkedHashMap<>();
        Set<String> applied = new LinkedHashSet<>();

        if (p == null || p.isClosed()) {
            patch.keySet().forEach(k -> rejected.put(k, "resource not started"));
            return TuneResult.partial(Set.of(), rejected);
        }

        for (Map.Entry<String, Object> entry : patch.entrySet()) {
            String key = entry.getKey();
            if (!tunableKeys.contains(key)) {
                rejected.put(key, "not in tunable whitelist " + tunableKeys);
                continue;
            }
            try {
                switch (key) {
                    case KEY_MAX_TOTAL -> {
                        int v = requireNonZeroInt(entry.getValue());
                        p.setMaxTotal(v);
                        this.maxTotal = v;      // P-15：同时写回「用于重建的配置」
                    }
                    case KEY_MAX_IDLE -> {
                        int v = parseInt(entry.getValue());
                        p.setMaxIdle(v);
                        this.maxIdle = v;
                    }
                    case KEY_MIN_IDLE -> {
                        int v = requireNonNegativeInt(entry.getValue());
                        p.setMinIdle(v);
                        this.minIdle = v;
                    }
                    case KEY_MAX_WAIT -> {
                        Duration v = ConfigValues.duration(KEY_MAX_WAIT, entry.getValue());
                        p.setMaxWait(v);
                        this.maxWait = v;
                    }
                    default -> {
                        rejected.put(key, "unsupported by CommonsPool2Adapter");
                        continue;
                    }
                }
                applied.add(key);
            } catch (IllegalArgumentException | MetaPoolConfigException e) {
                rejected.put(key, e.getMessage());
            }
        }
        if (!applied.isEmpty()) {
            log.info("[MetaPool] object pool '{}' tuned maxTotal={}, maxIdle={}, minIdle={}, maxWait={}",
                    name, maxTotal, maxIdle, minIdle, maxWait);
        }
        return TuneResult.partial(applied, rejected);
    }

    // ==================== helpers ====================

    private GenericObjectPool<T> requireStarted() {
        GenericObjectPool<T> p = this.pool;
        if (p == null || p.isClosed()) {
            throw new MetaPoolException(ErrorCode.INTERNAL, "object pool '" + name + "' not started");
        }
        return p;
    }

    private static int parseInt(Object v) {
        try {
            return (v instanceof Number n) ? n.intValue() : Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not an integer: " + v);
        }
    }

    /** Pool2 约定：负数表示无上限，0 表示「一个都不给」——后者几乎总是配置事故，直接拒。 */
    private static int requireNonZeroInt(Object v) {
        int i = parseInt(v);
        if (i == 0) {
            throw new IllegalArgumentException("must not be 0 (negative means unlimited)");
        }
        return i;
    }

    private static int requireNonNegativeInt(Object v) {
        int i = parseInt(v);
        if (i < 0) {
            throw new IllegalArgumentException("must not be negative, got " + i);
        }
        return i;
    }

    /** 极简 fluent 构建器。 */
    public static final class Builder<T> {
        private String name;
        private PooledObjectFactory<T> factory;
        private int maxTotal = GenericObjectPoolConfig.DEFAULT_MAX_TOTAL;
        private int maxIdle = GenericObjectPoolConfig.DEFAULT_MAX_IDLE;
        private int minIdle = GenericObjectPoolConfig.DEFAULT_MIN_IDLE;
        private Duration maxWait = GenericObjectPoolConfig.DEFAULT_MAX_WAIT;
        private Set<String> tunableKeys = SUPPORTED_TUNABLE_KEYS;

        private Builder() {
        }

        public Builder<T> named(String name) {
            this.name = name;
            return this;
        }

        /** 对象的创建/销毁/校验由它负责 —— Commons Pool2 不知道怎么造 {@code T}。 */
        public Builder<T> factory(PooledObjectFactory<T> factory) {
            this.factory = factory;
            return this;
        }

        public Builder<T> maxTotal(int maxTotal) {
            this.maxTotal = maxTotal;
            return this;
        }

        public Builder<T> maxIdle(int maxIdle) {
            this.maxIdle = maxIdle;
            return this;
        }

        public Builder<T> minIdle(int minIdle) {
            this.minIdle = minIdle;
            return this;
        }

        /** 负数表示无限等待（Pool2 约定）。 */
        public Builder<T> maxWait(Duration maxWait) {
            this.maxWait = maxWait;
            return this;
        }

        public Builder<T> tunable(Set<String> keys) {
            this.tunableKeys = keys;
            return this;
        }

        public CommonsPool2Adapter<T> build() {
            if (name == null || name.isBlank()) {
                throw new MetaPoolConfigException("CommonsPool2Adapter requires a non-blank name");
            }
            if (factory == null) {
                throw new MetaPoolConfigException("object pool '" + name + "' requires a PooledObjectFactory "
                        + "— Commons Pool2 cannot create T on its own");
            }
            return new CommonsPool2Adapter<>(name, factory, maxTotal, maxIdle, minIdle,
                    maxWait, tunableKeys);
        }
    }
}
