package com.metapool.adapter.lettuce;

import com.metapool.common.exception.ErrorCode;
import com.metapool.common.exception.MetaPoolConfigException;
import com.metapool.common.exception.MetaPoolException;
import com.metapool.common.resource.ManagedResource;
import com.metapool.common.resource.ResourceTypes;
import com.metapool.common.resource.Tunable;
import com.metapool.common.spi.ConfigValues;
import com.metapool.common.stats.HealthStatus;
import com.metapool.common.stats.TuneResult;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisChannelHandler;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisConnectionStateListener;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 把 Lettuce 的 Redis 连接纳入 MetaPool 治理面的适配器。
 *
 * <h3>🎯 它<b>刻意不实现</b> {@link com.metapool.common.capability.Pool}</h3>
 * <p>Lettuce 的 {@link StatefulRedisConnection} 是<b>单连接多路复用</b>：一个连接天然线程安全，
 * 可以被所有线程共享，命令在同一条 TCP 连接上流水线化。它<b>没有「借出 / 归还」这回事</b> ——
 * 强行套上 {@code Pool} 语义，等于凭空发明一个不存在的生命周期。
 *
 * <p>为什么值得专门说：本项目已有两个 {@code Pool} 实现（HikariCP、Commons Pool2），
 * 于是「别的 adapter 都实现了 {@code Pool}，这个也该实现」听起来很自然。
 * <b>但那是用一致性绑架语义</b> —— 和 1.0 把所有资源硬塞进 {@code acquire/release} 是同一个错误
 * （台账 P-07），只不过那次更明显。可选能力接口的意义正在于：<b>没有的能力就不声明。</b>
 *
 * <p><b>那业务怎么用？</b> 调 {@link #connection()} 拿原生连接，照 Lettuce 的方式发命令。
 * 这看起来是「业务耦合到了具体 adapter」，但那个耦合<b>本来就存在</b> —— 业务要发 Redis 命令，
 * 必然要用 Lettuce 的 {@code RedisCommands} API。套一层 {@code Pool} 不会解耦任何东西，
 * 只会多一层假抽象。
 *
 * <p>确有需要独占连接的场景（阻塞命令 {@code BLPOP}、事务 {@code MULTI/EXEC}、独占 pipeline），
 * 那时才需要连接池 —— Lettuce 自己提供 {@code ConnectionPoolSupport}。
 * <b>那是使用方按场景的选择，不该由治理层替所有人预先决定。</b>
 *
 * <h3>它<b>实现</b> {@link Tunable} —— 与 Redisson 适配器恰好相反</h3>
 * <p>{@code command-timeout} 是运行时真可写的（{@code StatefulConnection.setTimeout}），
 * 线上 Redis 变慢时可以不重启地放宽。而 {@code metapool-adapter-redisson} 没有任何运行时可调参数，
 * 所以它不实现 {@code Tunable}。<b>同一条判据（有没有真参数），两个相反结论</b> ——
 * 这正是可选能力接口在正常工作的样子。
 *
 * <h3>健康三态：DEGRADED 表示「正在自动重连」，不是故障</h3>
 * <p>Lettuce <b>默认自动重连</b>。连接短暂断开时 {@code isOpen()} 为 false，但客户端正在恢复 ——
 * 这时报 DOWN 会造成误报警。判据与线程池「饱和不等于故障」同源：
 * <b>把「正在自愈」和「已经坏了」分开。</b>
 *
 * @since 2.3.0
 */
public final class LettuceAdapter implements ManagedResource, Tunable {

    private static final Logger log = LoggerFactory.getLogger(LettuceAdapter.class);

    /** 内置可热调参数（kebab-case，与 YAML/tunable 声明一致）。 */
    static final String KEY_COMMAND_TIMEOUT = "command-timeout";

    /**
     * 本适配器能够热调的全部参数。配置里声明的 {@code tunable} 白名单必须是它的子集 ——
     * 否则构建期即失败（fail-fast，RULES §3.3），不留到调参时才报（见坑 P-13）。
     *
     * <p>{@code uri} 与 {@code auto-reconnect} 不在其中：前者换地址等于换资源，
     * 后者是 {@code ClientOptions} 的构造期设置，运行时改不了。<b>底层做不到的事不进白名单</b>
     * （与 jdk-executor 的 {@code queue-capacity} 同理）。
     */
    static final Set<String> SUPPORTED_TUNABLE_KEYS = Set.of(KEY_COMMAND_TIMEOUT);

    private final String name;
    private final String uri;
    private final boolean autoReconnect;

    /**
     * 当前命令超时。<b>调参时必须同时更新它</b>（而不只是改运行中的连接）——
     * {@link #start()} 用它重建连接，只改运行中对象会导致「stop→start 后调参结果丢失」
     * （坑 P-15，HikariAdapter 踩过）。
     */
    private volatile Duration commandTimeout;

    private volatile RedisClient client;
    private volatile StatefulRedisConnection<String, String> connection;

    /**
     * 连接层事件计数。业务直接用原生 API 发命令，<b>适配器观测不到命令量</b>，
     * 所以这里只统计我们真看得见的东西（坑 P-12 的教训：观测不到就别记计数器）。
     *
     * <p>{@code disconnects} 是本适配器最有价值的产出：Lettuce 自动重连会把网络抖动<b>掩盖掉</b>，
     * 业务侧只觉得「偶尔慢一下」，没人知道底下在反复重连。
     */
    private final AtomicLong connects = new AtomicLong();
    private final AtomicLong disconnects = new AtomicLong();
    private final AtomicLong exceptions = new AtomicLong();

    LettuceAdapter(String name, String uri, Duration commandTimeout, boolean autoReconnect,
                   Set<String> tunableKeys) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.uri = requireNonBlank(uri, "uri");
        Objects.requireNonNull(commandTimeout, "commandTimeout must not be null");
        if (commandTimeout.isZero() || commandTimeout.isNegative()) {
            throw new MetaPoolConfigException("command-timeout must be positive, got " + commandTimeout);
        }
        this.commandTimeout = commandTimeout;
        this.autoReconnect = autoReconnect;
        this.tunableKeys = validateTunableKeys(name, tunableKeys);
    }

    private final Set<String> tunableKeys;

    private static String requireNonBlank(String v, String field) {
        if (v == null || v.isBlank()) {
            throw new MetaPoolConfigException(field + " must not be blank");
        }
        return v;
    }

    private static Set<String> validateTunableKeys(String name, Set<String> keys) {
        Objects.requireNonNull(keys, "tunableKeys must not be null");
        Set<String> unsupported = new LinkedHashSet<>(keys);
        unsupported.removeAll(SUPPORTED_TUNABLE_KEYS);
        if (!unsupported.isEmpty()) {
            throw new MetaPoolConfigException("redis '" + name + "' declares unsupported tunable key(s) "
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
        return ResourceTypes.REDIS;
    }

    // ==================== ManagedLifecycle ====================

    @Override
    public synchronized void start() {
        if (client != null) {
            return; // 幂等
        }
        RedisURI redisUri;
        try {
            redisUri = RedisURI.create(uri);
        } catch (RuntimeException e) {
            throw new MetaPoolConfigException("redis '" + name + "' has invalid uri '" + uri + "'", e);
        }
        redisUri.setTimeout(commandTimeout);

        RedisClient c = RedisClient.create(redisUri);
        c.setOptions(ClientOptions.builder().autoReconnect(autoReconnect).build());
        c.addListener(new StateListener());
        try {
            this.connection = c.connect();
        } catch (RuntimeException e) {
            c.shutdown(Duration.ZERO, Duration.ofSeconds(2));
            throw new MetaPoolException(ErrorCode.CONFIG_INVALID,
                    "redis '" + name + "' failed to connect to " + uri, e);
        }
        this.client = c;
        log.info("[MetaPool] redis '{}' started (uri={}, commandTimeout={}, autoReconnect={})",
                name, uri, commandTimeout, autoReconnect);
    }

    @Override
    public synchronized void stop(Duration graceful) {
        RedisClient c = this.client;
        if (c == null) {
            return; // 幂等
        }
        StatefulRedisConnection<String, String> conn = this.connection;
        try {
            if (conn != null) {
                conn.close();
            }
        } catch (RuntimeException e) {
            log.warn("[MetaPool] redis '{}' connection close raised", name, e);
        }
        try {
            // quietPeriod=0：没有「等新请求安静下来」的必要，连接是共享的，业务侧要么在用要么不在用
            long timeoutMillis = (graceful == null || graceful.isNegative()) ? 0 : graceful.toMillis();
            c.shutdown(Duration.ZERO, Duration.ofMillis(timeoutMillis));
        } catch (RuntimeException e) {
            log.warn("[MetaPool] redis '{}' client shutdown raised", name, e);
        } finally {
            // P-01：必须置空，否则 stop 之后再 start 会复用已关闭的客户端
            this.connection = null;
            this.client = null;
        }
        log.info("[MetaPool] redis '{}' stopped", name);
    }

    /**
     * 未启动为 DOWN；连接不 open 为 <b>DEGRADED</b>（Lettuce 正在自动重连，不是故障）；
     * PING 通为 UP；PING 抛异常为 DOWN。
     *
     * <p>把「正在自愈」与「已经坏了」分开，是为了避免网络抖动期间的误报警 ——
     * 与线程池「借满不等于故障」同源的判断。
     */
    @Override
    public HealthStatus health() {
        StatefulRedisConnection<String, String> conn = this.connection;
        if (client == null || conn == null) {
            return HealthStatus.down("not started");
        }
        if (!conn.isOpen()) {
            return autoReconnect
                    ? HealthStatus.degraded("connection not open; Lettuce is reconnecting")
                    : HealthStatus.down("connection not open and auto-reconnect is disabled");
        }
        try {
            conn.sync().ping();
            return HealthStatus.up();
        } catch (RuntimeException e) {
            return HealthStatus.down("PING failed: " + e.getMessage());
        }
    }

    // ==================== MetricsSource ====================

    @Override
    public void bindTo(MeterRegistry registry) {
        Tags tags = Tags.of("metapool.resource", name, "metapool.type", type());
        Gauge.builder("metapool.redis.connection.open", this, a -> a.isConnectionOpen() ? 1d : 0d)
                .tags(tags).register(registry);
        FunctionCounter.builder("metapool.redis.connects.total", connects, AtomicLong::doubleValue)
                .tags(tags).register(registry);
        // 🎯 这条持续上涨 = 网络在抖。Lettuce 自动重连把问题掩盖了，不埋这个指标没人看得见。
        FunctionCounter.builder("metapool.redis.disconnects.total", disconnects, AtomicLong::doubleValue)
                .tags(tags).register(registry);
        FunctionCounter.builder("metapool.redis.exceptions.total", exceptions, AtomicLong::doubleValue)
                .tags(tags).register(registry);
    }

    private boolean isConnectionOpen() {
        StatefulRedisConnection<String, String> conn = this.connection;
        try {
            return conn != null && conn.isOpen();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 连接状态监听：这是适配器唯一能真实观测到的一层，不越界去猜命令量。 */
    private final class StateListener implements RedisConnectionStateListener {
        @Override
        public void onRedisConnected(RedisChannelHandler<?, ?> handler, SocketAddress remote) {
            connects.incrementAndGet();
            log.info("[MetaPool] redis '{}' connected to {}", name, remote);
        }

        @Override
        public void onRedisDisconnected(RedisChannelHandler<?, ?> handler) {
            disconnects.incrementAndGet();
            log.warn("[MetaPool] redis '{}' disconnected (auto-reconnect={})", name, autoReconnect);
        }

        @Override
        public void onRedisExceptionCaught(RedisChannelHandler<?, ?> handler, Throwable cause) {
            exceptions.incrementAndGet();
            log.warn("[MetaPool] redis '{}' connection exception: {}", name, cause.toString());
        }
    }

    // ==================== 原生访问入口 ====================

    /**
     * 取被治理的原生连接，照 Lettuce 的方式发命令：
     * {@code adapter.connection().sync().set("k", "v")}。
     *
     * <p>这是本适配器的<b>主入口</b>，不是逃生舱 —— 因为它刻意不实现 {@code Pool}（见类注释）。
     * 连接是<b>共享且线程安全</b>的：<b>不要 close 它</b>，生命周期由控制面负责。
     *
     * @return 原生连接，绝不为 null；资源未启动时抛 {@code MetaPoolException}
     */
    public StatefulRedisConnection<String, String> connection() {
        StatefulRedisConnection<String, String> conn = this.connection;
        if (conn == null) {
            throw new MetaPoolException(ErrorCode.INTERNAL, "redis '" + name + "' not started");
        }
        return conn;
    }

    /** 取底层 {@link RedisClient}，用于本适配器未覆盖的高级用法（发布订阅、集群等）。 */
    public RedisClient unwrap() {
        RedisClient c = this.client;
        if (c == null) {
            throw new MetaPoolException(ErrorCode.INTERNAL, "redis '" + name + "' not started");
        }
        return c;
    }

    // ==================== Tunable ====================

    @Override
    public Set<String> tunableKeys() {
        return tunableKeys;
    }

    @Override
    public TuneResult apply(Map<String, Object> patch) {
        Objects.requireNonNull(patch, "patch must not be null");
        StatefulRedisConnection<String, String> conn = this.connection;
        Map<String, String> rejected = new LinkedHashMap<>();
        Set<String> applied = new LinkedHashSet<>();

        if (conn == null) {
            patch.keySet().forEach(k -> rejected.put(k, "resource not started"));
            return TuneResult.partial(Set.of(), rejected);
        }

        for (Map.Entry<String, Object> e : patch.entrySet()) {
            String key = e.getKey();
            if (!tunableKeys.contains(key)) {
                rejected.put(key, "not in tunable whitelist " + tunableKeys);
                continue;
            }
            if (KEY_COMMAND_TIMEOUT.equals(key)) {
                try {
                    Duration v = ConfigValues.duration(KEY_COMMAND_TIMEOUT, e.getValue());
                    if (v.isZero() || v.isNegative()) {
                        throw new IllegalArgumentException("must be positive, got " + v);
                    }
                    conn.setTimeout(v);
                    this.commandTimeout = v;   // P-15：同时写回「用于重建的配置」
                    applied.add(key);
                } catch (IllegalArgumentException | MetaPoolConfigException ex) {
                    rejected.put(key, ex.getMessage());
                }
            } else {
                rejected.put(key, "unsupported by LettuceAdapter");
            }
        }
        if (!applied.isEmpty()) {
            log.info("[MetaPool] redis '{}' tuned commandTimeout={}", name, commandTimeout);
        }
        return TuneResult.partial(applied, rejected);
    }

    /** 当前生效的命令超时（供测试与诊断）。 */
    public Duration commandTimeout() {
        return commandTimeout;
    }

    /** 极简 fluent 构建器。 */
    public static final class Builder {
        private String name;
        private String uri;
        private Duration commandTimeout = Duration.ofSeconds(60);
        private boolean autoReconnect = true;
        private Set<String> tunableKeys = SUPPORTED_TUNABLE_KEYS;

        private Builder() {
        }

        public Builder named(String name) {
            this.name = name;
            return this;
        }

        /** 如 {@code redis://127.0.0.1:6379}，直通 {@link RedisURI#create(String)}。 */
        public Builder uri(String uri) {
            this.uri = uri;
            return this;
        }

        public Builder commandTimeout(Duration commandTimeout) {
            this.commandTimeout = commandTimeout;
            return this;
        }

        public Builder autoReconnect(boolean autoReconnect) {
            this.autoReconnect = autoReconnect;
            return this;
        }

        public Builder tunable(Set<String> keys) {
            this.tunableKeys = keys;
            return this;
        }

        public LettuceAdapter build() {
            if (name == null || name.isBlank()) {
                throw new MetaPoolConfigException("LettuceAdapter requires a non-blank name");
            }
            return new LettuceAdapter(name, uri, commandTimeout, autoReconnect, tunableKeys);
        }
    }
}
