package com.metapool.adapter.hikari;

import com.metapool.common.capability.Pool;
import com.metapool.common.exception.ErrorCode;
import com.metapool.common.exception.MetaPoolConfigException;
import com.metapool.common.exception.MetaPoolException;
import com.metapool.common.exception.PoolExhaustedException;
import com.metapool.common.resource.ManagedResource;
import com.metapool.common.resource.ResourceTypes;
import com.metapool.common.resource.Tunable;
import com.metapool.common.stats.HealthStatus;
import com.metapool.common.stats.PoolStats;
import com.metapool.common.stats.TuneResult;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 把 HikariCP 数据库连接池纳入 MetaPool 治理面的适配器。
 *
 * <p>它<em>不重造连接池</em>——底层就是一个 {@link HikariDataSource}。本类只负责把 HikariCP 统一到
 * MetaPool 的治理契约：{@link ManagedResource 生命周期 + 可观测} + 可选能力 {@link Pool} 与 {@link Tunable}。
 *
 * <h3>能力落点</h3>
 * <ul>
 *   <li>{@link com.metapool.common.resource.ManagedLifecycle} — start 建池；stop 先 drain 在用连接再关闭</li>
 *   <li>{@link com.metapool.common.resource.MetricsSource} — 注册 {@code metapool.datasource.*} 网关指标，
 *       读 {@link HikariPoolMXBean}，打统一 tag，<b>与 start 顺序无关</b></li>
 *   <li>{@link Pool}{@code <Connection>} — borrow=getConnection，release=connection.close()。
 *       两条路径都可用，但只有走 {@link Pool} 能力的流量会计入 {@link #poolStats()} 的累计计数
 *       （原因见 {@link #poolStats()}）</li>
 *   <li>{@link Tunable} — 经 {@code HikariConfigMXBean} 热调 {@code maximum-pool-size} / {@code connection-timeout}</li>
 * </ul>
 *
 * <h3>使用（编程式）</h3>
 * <pre>{@code
 * HikariConfig cfg = new HikariConfig();
 * cfg.setJdbcUrl("jdbc:postgresql://localhost:5432/app");
 * HikariAdapter ds = HikariAdapter.from(cfg).named("main").build();
 * ds.start();
 *
 * // 原生用法：直通 JDBC 惯例，不计入 poolStats 累计计数
 * try (Connection c = ds.getConnection()) { ... }
 *
 * // Pool 能力用法：计入 poolStats().totalBorrowed()/totalReleased()
 * Connection c = ds.borrow();
 * try { ... } finally { ds.release(c); }
 *
 * ds.stop(Duration.ofSeconds(5));
 * }</pre>
 *
 * <h3>线程安全</h3>
 * <p>{@link #start()} / {@link #stop(Duration)} 由控制面保证单线程调用；其余方法多线程安全。
 *
 * @since 2.0.0
 */
public final class HikariAdapter implements ManagedResource, Pool<Connection>, Tunable {

    private static final Logger log = LoggerFactory.getLogger(HikariAdapter.class);

    /** drain 轮询间隔 */
    private static final Duration DRAIN_POLL_INTERVAL = Duration.ofMillis(50);

    /** 内置可热调参数（kebab-case，与 YAML/tunable 声明一致） */
    static final String KEY_MAX_POOL_SIZE = "maximum-pool-size";
    static final String KEY_CONNECTION_TIMEOUT = "connection-timeout";

    /**
     * 本适配器能够热调的全部参数。配置里声明的 {@code tunable} 白名单必须是它的子集 ——
     * 否则构建期即失败（fail-fast，RULES §3.3），不留到运维真去调参时才报（见坑 P-13）。
     */
    static final Set<String> SUPPORTED_TUNABLE_KEYS = Set.of(KEY_MAX_POOL_SIZE, KEY_CONNECTION_TIMEOUT);

    private final String name;
    private final HikariConfig config;
    private final Set<String> tunableKeys;

    private final AtomicLong totalBorrowed = new AtomicLong();
    private final AtomicLong totalReleased = new AtomicLong();

    /** 由 start() 创建；volatile 供 metrics/health 无锁读取 */
    private volatile HikariDataSource dataSource;

    /** 仅在 stop() 的 drain 窗口内为 true，用于把新请求的报错从「未启动」区分为「正在停机」 */
    private volatile boolean stopping;

    HikariAdapter(String name, HikariConfig config, Set<String> tunableKeys) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.tunableKeys = validateTunableKeys(name, tunableKeys);
        this.config.setPoolName(name);
    }

    /** 启动前就拒掉拼错/不支持的 tunable key，而不是等到调参时返回 rejected。 */
    private static Set<String> validateTunableKeys(String name, Set<String> keys) {
        Objects.requireNonNull(keys, "tunableKeys must not be null");
        Set<String> unsupported = new java.util.LinkedHashSet<>(keys);
        unsupported.removeAll(SUPPORTED_TUNABLE_KEYS);
        if (!unsupported.isEmpty()) {
            throw new MetaPoolConfigException("datasource '" + name + "' declares unsupported tunable key(s) "
                    + unsupported + "; supported: " + SUPPORTED_TUNABLE_KEYS);
        }
        return Set.copyOf(keys);
    }

    public static Builder from(HikariConfig config) {
        return new Builder(config);
    }

    // ==================== ManagedResource ====================

    @Override
    public String name() {
        return name;
    }

    @Override
    public String type() {
        return ResourceTypes.DATASOURCE;
    }

    // ==================== ManagedLifecycle ====================

    @Override
    public synchronized void start() {
        if (dataSource != null) {
            return; // 幂等
        }
        try {
            dataSource = new HikariDataSource(config);
            log.info("[MetaPool] datasource '{}' started (maxPoolSize={})",
                    name, config.getMaximumPoolSize());
        } catch (RuntimeException e) {
            throw new MetaPoolConfigException("Failed to start datasource '" + name + "': " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void stop(Duration graceful) {
        HikariDataSource ds = this.dataSource;
        if (ds == null) {
            return; // 幂等
        }
        this.dataSource = null;   // 先置空：使 start() 可重启，且 health/metrics 立即视为已停
        this.stopping = true;     // drain 期间让新请求收到 SHUTTING_DOWN 而非 "not started"
        try {
            if (!ds.isClosed()) {
                drain(ds, graceful);
                ds.close();
            }
        } finally {
            this.stopping = false;
        }
        log.info("[MetaPool] datasource '{}' stopped", name);
    }

    /** 在 graceful 期内等待在用连接归还；超时则放弃等待，交由 close() 强制回收。 */
    private void drain(HikariDataSource ds, Duration graceful) {
        long deadline = System.nanoTime() + Math.max(0, graceful.toNanos());
        try {
            HikariPoolMXBean mx = ds.getHikariPoolMXBean();
            while (mx.getActiveConnections() > 0 && System.nanoTime() < deadline) {
                Thread.sleep(DRAIN_POLL_INTERVAL.toMillis());
            }
            int remaining = mx.getActiveConnections();
            if (remaining > 0) {
                log.warn("[MetaPool] datasource '{}' drain timeout, {} connection(s) still active",
                        name, remaining);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.warn("[MetaPool] datasource '{}' drain skipped: {}", name, e.getMessage());
        }
    }

    @Override
    public HealthStatus health() {
        HikariDataSource ds = this.dataSource;
        if (ds == null || ds.isClosed()) {
            return HealthStatus.down("not started");
        }
        try {
            HikariPoolMXBean mx = ds.getHikariPoolMXBean();
            if (mx.getIdleConnections() == 0 && mx.getThreadsAwaitingConnection() > 0) {
                return HealthStatus.degraded("saturated: " + mx.getThreadsAwaitingConnection() + " waiting");
            }
            return HealthStatus.up();
        } catch (RuntimeException e) {
            return HealthStatus.down(e.getMessage());
        }
    }

    // ==================== MetricsSource ====================

    /**
     * {@inheritDoc}
     *
     * <p>幂等由 Micrometer 自身保证（同名同 tag 的 meter 重复注册返回已有实例），因此这里不再自持
     * "已绑定" 标志 —— 那个标志会让<b>第二个</b> registry 静默拿不到任何指标（见坑 P-14）。
     *
     * <p>注意 {@code metapool.datasource.connections.total} 在 Prometheus 端点上导出为
     * {@code metapool_datasource_connections}：客户端会剥掉 {@code _total} 后缀。写 PromQL 时按后者。
     */
    @Override
    public void bindTo(MeterRegistry registry) {
        Tags tags = Tags.of("metapool.resource", name, "metapool.type", type());
        gauge(registry, "metapool.datasource.connections.active", tags, m -> m.getActiveConnections());
        gauge(registry, "metapool.datasource.connections.idle", tags, m -> m.getIdleConnections());
        gauge(registry, "metapool.datasource.connections.total", tags, m -> m.getTotalConnections());
        gauge(registry, "metapool.datasource.connections.pending", tags, m -> m.getThreadsAwaitingConnection());
    }

    private void gauge(MeterRegistry registry, String metric, Tags tags, PoolGauge fn) {
        Gauge.builder(metric, this, self -> self.sample(fn)).tags(tags).register(registry);
    }

    /** 网关采样：池未启动或已关闭时返回 0，绝不抛异常。 */
    private double sample(PoolGauge fn) {
        HikariDataSource ds = this.dataSource;
        if (ds == null || ds.isClosed()) {
            return 0d;
        }
        try {
            return fn.read(ds.getHikariPoolMXBean());
        } catch (RuntimeException e) {
            return 0d;
        }
    }

    @FunctionalInterface
    private interface PoolGauge {
        int read(HikariPoolMXBean mx);
    }

    // ==================== Pool<Connection> ====================

    @Override
    public Connection borrow() throws InterruptedException {
        Connection c = doGetConnection();
        totalBorrowed.incrementAndGet();
        return c;
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>阻抗说明</b>：HikariCP 的获取超时由配置项 {@code connectionTimeout} 统一治理，
     * 不支持逐次调用传入超时。本方法以配置的 {@code connectionTimeout} 为有效界，{@code timeout} 参数仅作提示；
     * 池耗尽/超时统一映射为 {@link PoolExhaustedException}。
     */
    @Override
    public Connection borrow(Duration timeout) throws InterruptedException, PoolExhaustedException {
        Connection c = doGetConnection();
        totalBorrowed.incrementAndGet();
        return c;
    }

    /**
     * 原生访问入口 —— 直通 {@link HikariDataSource#getConnection()}，用完按 JDBC 惯例
     * {@code close()} 归还。
     *
     * <p><b>不计入</b> {@link #poolStats()} 的累计计数：归还走的是 {@code Connection.close()}，
     * 适配器无从观测，若在此计 borrow 就会出现「借出 1000、归还 0」的失真（见坑 P-12）。
     * 需要瞬时口径请看 {@code metapool.datasource.connections.*} 指标（由 HikariCP 自身统计）。
     */
    public Connection getConnection() {
        return doGetConnection();
    }

    private Connection doGetConnection() {
        HikariDataSource ds = requireStarted();
        try {
            return ds.getConnection();
        } catch (SQLTransientConnectionException e) {
            throw new PoolExhaustedException(
                    "datasource '" + name + "' exhausted (connectionTimeout exceeded)", e);
        } catch (SQLException e) {
            throw new MetaPoolException(ErrorCode.INTERNAL,
                    "datasource '" + name + "' getConnection failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void release(Connection resource) {
        if (resource == null) {
            return;
        }
        try {
            resource.close(); // HikariCP：close() 即归还
            totalReleased.incrementAndGet();
        } catch (SQLException e) {
            log.warn("[MetaPool] datasource '{}' release failed: {}", name, e.getMessage());
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>计数口径</b>：{@code totalBorrowed} / {@code totalReleased} 只统计经 {@link Pool} 能力
     * （{@link #borrow()} / {@link #release(Connection)}）的流量，因此两者可比、可对账。经
     * {@link #getConnection()} 的原生流量不计入 —— 它靠 {@code Connection.close()} 归还，
     * 适配器观测不到，计了就只涨 borrowed 不涨 released（见坑 P-12）。
     * {@code active}/{@code idle}/{@code pending} 是全量瞬时值，两种用法都涵盖。
     */
    @Override
    public PoolStats poolStats() {
        HikariDataSource ds = this.dataSource;
        if (ds == null || ds.isClosed()) {
            return new PoolStats(0, 0, 0, totalBorrowed.get(), totalReleased.get());
        }
        HikariPoolMXBean mx = ds.getHikariPoolMXBean();
        return new PoolStats(mx.getActiveConnections(), mx.getIdleConnections(),
                mx.getThreadsAwaitingConnection(), totalBorrowed.get(), totalReleased.get());
    }

    // ==================== Tunable ====================

    @Override
    public Set<String> tunableKeys() {
        return tunableKeys;
    }

    /**
     * {@inheritDoc}
     *
     * <p>生效的参数会同时回写到本适配器持有的 {@link HikariConfig}，因此 {@code stop()} → {@code start()}
     * 之后调参结果<b>不丢</b>——与 {@code Bucket4jAdapter} 的行为一致（此前两个 adapter 语义不同，
     * Hikari 会静默退回原始池大小，见坑 P-15）。
     */
    @Override
    public TuneResult apply(Map<String, Object> patch) {
        Objects.requireNonNull(patch, "patch must not be null");
        HikariDataSource ds = this.dataSource;
        Map<String, String> rejected = new LinkedHashMap<>();
        var applied = new java.util.LinkedHashSet<String>();

        if (ds == null || ds.isClosed()) {
            patch.keySet().forEach(k -> rejected.put(k, "resource not started"));
            return TuneResult.partial(Set.of(), rejected);
        }

        for (Map.Entry<String, Object> e : patch.entrySet()) {
            String key = e.getKey();
            if (!tunableKeys.contains(key)) {
                rejected.put(key, "not in tunable whitelist " + tunableKeys);
                continue;
            }
            try {
                switch (key) {
                    case KEY_MAX_POOL_SIZE -> {
                        int v = requirePositiveInt(e.getValue());
                        ds.getHikariConfigMXBean().setMaximumPoolSize(v);
                        config.setMaximumPoolSize(v);   // 回写，使 stop→start 后调参结果不丢
                        applied.add(key);
                    }
                    case KEY_CONNECTION_TIMEOUT -> {
                        long v = requirePositiveLong(e.getValue());
                        ds.getHikariConfigMXBean().setConnectionTimeout(v);
                        config.setConnectionTimeout(v);
                        applied.add(key);
                    }
                    default -> rejected.put(key, "unsupported by HikariAdapter");
                }
            } catch (IllegalArgumentException iae) {
                rejected.put(key, iae.getMessage());
            }
        }
        if (!applied.isEmpty()) {
            log.info("[MetaPool] datasource '{}' tuned {}", name, applied);
        }
        return TuneResult.partial(applied, rejected);
    }

    // ==================== helpers ====================

    private HikariDataSource requireStarted() {
        HikariDataSource ds = this.dataSource;
        if (ds == null || ds.isClosed()) {
            // 优雅停机窗口内要给出可区分的语义：调用方据此重试别处，而不是当成配置/内部错误
            if (stopping) {
                throw new MetaPoolException(ErrorCode.SHUTTING_DOWN,
                        "datasource '" + name + "' is shutting down, not accepting new connections");
            }
            throw new MetaPoolException(ErrorCode.INTERNAL, "datasource '" + name + "' not started");
        }
        return ds;
    }

    private static int requirePositiveInt(Object v) {
        int i = (v instanceof Number n) ? n.intValue() : Integer.parseInt(String.valueOf(v));
        if (i <= 0) {
            throw new IllegalArgumentException("must be positive, got " + i);
        }
        return i;
    }

    private static long requirePositiveLong(Object v) {
        long l = (v instanceof Number n) ? n.longValue() : Long.parseLong(String.valueOf(v));
        if (l <= 0) {
            throw new IllegalArgumentException("must be positive, got " + l);
        }
        return l;
    }

    /** 极简 fluent 构建器，匹配 {@code HikariAdapter.from(cfg).named("main").build()}。 */
    public static final class Builder {
        private final HikariConfig config;
        private String name;
        private Set<String> tunableKeys = SUPPORTED_TUNABLE_KEYS;

        private Builder(HikariConfig config) {
            this.config = Objects.requireNonNull(config, "config must not be null");
        }

        public Builder named(String name) {
            this.name = name;
            return this;
        }

        public Builder tunable(Set<String> keys) {
            this.tunableKeys = keys;
            return this;
        }

        public HikariAdapter build() {
            if (name == null || name.isBlank()) {
                throw new MetaPoolConfigException("HikariAdapter requires a non-blank name");
            }
            return new HikariAdapter(name, config, tunableKeys);
        }
    }
}
