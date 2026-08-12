package com.metapool.adapter.redisson;

import com.metapool.common.capability.DistributedLock;
import com.metapool.common.capability.LockHandle;
import com.metapool.common.exception.ErrorCode;
import com.metapool.common.exception.MetaPoolConfigException;
import com.metapool.common.exception.MetaPoolException;
import com.metapool.common.resource.ManagedResource;
import com.metapool.common.resource.ResourceTypes;
import com.metapool.common.stats.HealthStatus;
import com.metapool.common.stats.LockStats;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 把 Redisson 分布式锁纳入 MetaPool 治理面的适配器。
 *
 * <h3>锁不是池</h3>
 * <p>本类<b>不实现</b> {@link com.metapool.common.capability.Pool}：加锁不是「借出一个对象、用完归还」，
 * 而是「在一段租约内独占一个键」。1.0 曾把锁建模成 {@code ResourceLifecycle<Boolean>}
 * （即「借出一个 Boolean」），是台账 P-07 的原始现场之一。
 *
 * <h3>本适配器也不实现 Tunable —— 这是刻意的</h3>
 * <p>Redisson 锁<b>没有有意义的运行时可调参数</b>：{@code waitTime} / {@code leaseTime} 是
 * <b>每次调用传入</b>的，不是配置项。为了「看起来完整」硬凑一个可调参数，就是在制造
 * 治理面上的假象。{@code Tunable} 是可选能力，谁有谁实现 —— 没有就不实现，这本身就是能力隔离
 * 在正常工作的证据。
 *
 * <h3>⚠️ 必填 leaseTime 关掉了 Redisson 的看门狗 —— 代价与规避</h3>
 * <p>Redisson 的 {@code tryLock(wait, lease, unit)} <b>一旦显式传入 lease，就不再自动续期</b>
 * （看门狗只在不传 lease 时生效）。而 {@link DistributedLock} 的契约规定 {@code leaseTime}
 * <b>必填</b>（强制调用方对「最多持有多久」表态），因此在 MetaPool 下<b>看门狗永远不启用</b>。
 *
 * <p><b>由此带来的真实风险，如实写在这里而不是回避</b>：若业务执行时间超过租约，
 * 锁会自动释放、另一个进程可以拿到同一把锁，于是<b>两个线程同时进入临界区</b>；
 * 而 {@code DistributedLock} 又明确不提供 fencing token（各后端并非都能提供，见其类注释），
 * 所以下游存储无法拒绝「过期持有者」的迟到写入。这正是 Martin Kleppmann 对
 * 「基于超时的分布式锁」的经典批评所指的场景。
 *
 * <p><b>规避建议</b>：
 * <ul>
 *   <li>{@code leaseTime} 设为业务正常耗时的 <b>3~5 倍</b>，并用
 *       {@link LockStats#totalLeaseExpired()} 指标盯着 —— 它持续偏高就说明租约配短了；</li>
 *   <li>临界区内<b>不要做无界 IO</b>（无超时的 HTTP 调用、无限重试等）；</li>
 *   <li>对正确性要求强到不能容忍上述场景的，别用「锁 + 超时」这条路，
 *       应当让下游存储承担互斥（唯一约束、乐观锁版本号、条件更新）。</li>
 * </ul>
 *
 * <h3>凭证必须在获取它的线程上释放</h3>
 * <p>Redisson 的 {@code RLock} 是<b>线程绑定</b>的。因此 {@link LockHandle} <b>不可跨线程传递</b>：
 * 必须在调用 {@link #tryLock} 的那个线程上 {@code close()}。try-with-resources 天然满足这一点。
 *
 * <h3>使用</h3>
 * <pre>{@code
 * Optional<LockHandle> acquired =
 *         lock.tryLock("order:123", Duration.ofSeconds(3), Duration.ofSeconds(30));
 * if (acquired.isEmpty()) {
 *     return Result.rejected("busy");
 * }
 * try (LockHandle held = acquired.get()) {
 *     // 临界区
 * }
 * }</pre>
 *
 * @since 2.1.0
 */
public final class RedissonLockAdapter implements ManagedResource, DistributedLock {

    private static final Logger log = LoggerFactory.getLogger(RedissonLockAdapter.class);

    /** 默认锁键前缀。多个应用共用一个 Redis 时不撞键，运维也能一眼看出这些键是谁的锁。 */
    static final String DEFAULT_KEY_PREFIX = "metapool:lock:";

    private final String name;
    private final String address;
    private final String password;
    private final int database;
    private final int connectionPoolSize;
    private final String keyPrefix;

    private volatile RedissonClient client;

    private final AtomicInteger held = new AtomicInteger();
    private final AtomicLong totalAcquired = new AtomicLong();
    private final AtomicLong totalTimeout = new AtomicLong();
    private final AtomicLong totalLeaseExpired = new AtomicLong();

    RedissonLockAdapter(String name, String address, String password, int database,
                        int connectionPoolSize, String keyPrefix) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.address = requireNonBlank(address, "address");
        if (database < 0) {
            throw new MetaPoolConfigException("database must not be negative, got " + database);
        }
        if (connectionPoolSize < 1) {
            throw new MetaPoolConfigException("connection-pool-size must be at least 1, got "
                    + connectionPoolSize);
        }
        this.password = (password == null || password.isBlank()) ? null : password;
        this.database = database;
        this.connectionPoolSize = connectionPoolSize;
        this.keyPrefix = keyPrefix == null ? DEFAULT_KEY_PREFIX : keyPrefix;
    }

    private static String requireNonBlank(String v, String field) {
        if (v == null || v.isBlank()) {
            throw new MetaPoolConfigException(field + " must not be blank");
        }
        return v;
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
        return ResourceTypes.LOCK;
    }

    // ==================== ManagedLifecycle ====================

    @Override
    public synchronized void start() {
        if (client != null) {
            return; // 幂等
        }
        Config config = new Config();
        SingleServerConfig server = config.useSingleServer()
                .setAddress(address)
                .setDatabase(database)
                .setConnectionPoolSize(connectionPoolSize);
        if (password != null) {
            server.setPassword(password);
        }
        try {
            client = Redisson.create(config);
        } catch (RuntimeException e) {
            // Redisson 连不上时抛的是自己的运行时异常；转成 MetaPool 契约（RULES §3.2 正向边界：
            // 「资源启动失败」是 MetaPool 自己的错误语义，不是底层库的既定生态契约）
            throw new MetaPoolException(ErrorCode.CONFIG_INVALID,
                    "lock '" + name + "' failed to connect to " + address, e);
        }
        log.info("[MetaPool] lock '{}' started (address={}, database={}, keyPrefix='{}')",
                name, address, database, keyPrefix);
    }

    /**
     * 停机。
     *
     * <p><b>刻意不强行解掉本进程仍持有的锁</b>：那些锁对应的业务可能还在临界区里跑，
     * 替它解锁等于制造并发。未释放的锁交给<b>租约</b>自然过期 —— 这正是
     * {@link DistributedLock} 把 {@code leaseTime} 定为必填的意义所在：
     * 没有租约的分布式锁，持有者进程一消失就是永久死锁。
     *
     * <p>停机时若仍有持有中的锁，记 WARN —— 这通常说明停机窗口短于业务临界区。
     */
    @Override
    public synchronized void stop(Duration graceful) {
        RedissonClient c = this.client;
        if (c == null) {
            return; // 幂等
        }
        int outstanding = held.get();
        if (outstanding > 0) {
            log.warn("[MetaPool] lock '{}' stopping with {} lock(s) still held by this process; "
                    + "they will be released by lease expiry, not by us", name, outstanding);
        }
        try {
            long timeoutMillis = (graceful == null || graceful.isNegative()) ? 0 : graceful.toMillis();
            c.shutdown(0, timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            log.warn("[MetaPool] lock '{}' shutdown raised, forcing close", name, e);
        } finally {
            // P-01：必须置空，否则 stop 之后再 start 会复用这个已关闭的客户端
            this.client = null;
        }
        log.info("[MetaPool] lock '{}' stopped", name);
    }

    /** 未启动为 DOWN；否则发一条 O(1) 的 EXISTS 探活，失败即 DOWN（后端不可用）。 */
    @Override
    public HealthStatus health() {
        RedissonClient c = this.client;
        if (c == null) {
            return HealthStatus.down("not started");
        }
        try {
            c.getBucket(keyPrefix + "__health__").isExists();
            return HealthStatus.up();
        } catch (RuntimeException e) {
            return HealthStatus.down("redis unreachable: " + e.getMessage());
        }
    }

    // ==================== MetricsSource ====================

    @Override
    public void bindTo(MeterRegistry registry) {
        Tags tags = Tags.of("metapool.resource", name, "metapool.type", type());
        Gauge.builder("metapool.lock.held", held, AtomicInteger::doubleValue)
                .tags(tags).register(registry);
        FunctionCounter.builder("metapool.lock.acquired.total", totalAcquired, AtomicLong::doubleValue)
                .tags(tags).register(registry);
        FunctionCounter.builder("metapool.lock.timeout.total", totalTimeout, AtomicLong::doubleValue)
                .tags(tags).register(registry);
        // 这条曲线持续上涨 = leaseTime 配短了，业务跑不完租约就到期
        FunctionCounter.builder("metapool.lock.lease.expired.total", totalLeaseExpired,
                        AtomicLong::doubleValue)
                .tags(tags).register(registry);
    }

    // ==================== DistributedLock ====================

    @Override
    public Optional<LockHandle> tryLock(String key, Duration waitTime, Duration leaseTime)
            throws InterruptedException {
        requireNonBlank(key, "key");
        Objects.requireNonNull(waitTime, "waitTime must not be null");
        Objects.requireNonNull(leaseTime, "leaseTime must not be null");
        if (waitTime.isNegative()) {
            throw new MetaPoolException(ErrorCode.CONFIG_INVALID,
                    "lock '" + name + "': waitTime must not be negative, got " + waitTime);
        }
        if (leaseTime.isZero() || leaseTime.isNegative()) {
            // 契约要求租约必须为正：没有租约的分布式锁，持有者崩溃即永久死锁
            throw new MetaPoolException(ErrorCode.CONFIG_INVALID,
                    "lock '" + name + "': leaseTime must be positive, got " + leaseTime);
        }

        RLock lock = requireStarted().getLock(keyPrefix + key);
        boolean acquired = lock.tryLock(waitTime.toMillis(), leaseTime.toMillis(), TimeUnit.MILLISECONDS);
        if (!acquired) {
            totalTimeout.incrementAndGet();
            return Optional.empty();
        }
        totalAcquired.incrementAndGet();
        held.incrementAndGet();
        return Optional.of(new RedissonLockHandle(key, lock));
    }

    /**
     * {@inheritDoc}
     *
     * <p>口径说明：{@code totalLeaseExpired} 是<b>近似值</b>。真正的租约到期发生在 Redis 侧，
     * 进程内观测不到；这里统计的是本地能观测到的最接近信号 —— {@code close()} 时发现
     * 该锁已不再由本线程持有。低估是可能的（拿到锁后从未 close 的情况数不到）。
     */
    @Override
    public LockStats lockStats() {
        return new LockStats(held.get(), totalAcquired.get(), totalTimeout.get(),
                totalLeaseExpired.get());
    }

    private RedissonClient requireStarted() {
        RedissonClient c = this.client;
        if (c == null) {
            throw new MetaPoolException(ErrorCode.INTERNAL, "lock '" + name + "' not started");
        }
        return c;
    }

    /**
     * 持有凭证。释放锁的唯一途径，且<b>必须在获取它的线程上</b>调用 —— Redisson 的
     * {@code RLock} 是线程绑定的。
     */
    private final class RedissonLockHandle implements LockHandle {

        private final String key;
        private final RLock lock;
        private final AtomicBoolean closed = new AtomicBoolean();

        RedissonLockHandle(String key, RLock lock) {
            this.key = key;
            this.lock = lock;
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public boolean isHeld() {
            // 本地视角的尽力而为判断（见 LockHandle javadoc）：远端此刻是否仍认为我们持有，
            // 网络分区 / GC 停顿 / 时钟漂移都可能让它失真。只适合日志与指标。
            return !closed.get() && lock.isHeldByCurrentThread();
        }

        /**
         * 释放锁。<b>幂等</b>：重复调用、或租约已到期后调用，均静默返回。
         *
         * <p>本方法绝不抛异常 —— 它最常出现在 try-with-resources / finally 里，
         * 在那里抛异常只会掩盖业务的原始异常。
         */
        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;   // 幂等：重复 close 直接返回
            }
            held.decrementAndGet();
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                } else {
                    // 本地能观测到的、最接近「租约已到期」的信号：还没 close 锁就不归我们了
                    totalLeaseExpired.incrementAndGet();
                    log.warn("[MetaPool] lock '{}' key '{}' was no longer held at close() — "
                            + "lease likely expired mid-critical-section; consider a longer leaseTime",
                            name, key);
                }
            } catch (IllegalMonitorStateException e) {
                // Redisson 在非持有线程/已过期时抛这个。同样计入近似的租约到期。
                totalLeaseExpired.incrementAndGet();
                log.warn("[MetaPool] lock '{}' key '{}' unlock rejected by backend: {}",
                        name, key, e.getMessage());
            } catch (RuntimeException e) {
                log.warn("[MetaPool] lock '{}' key '{}' release failed", name, key, e);
            }
        }
    }

    /** 极简 fluent 构建器。 */
    public static final class Builder {
        private String name;
        private String address;
        private String password;
        private int database = 0;
        private int connectionPoolSize = 64;   // Redisson 默认值，直通不改
        private String keyPrefix = DEFAULT_KEY_PREFIX;

        private Builder() {
        }

        public Builder named(String name) {
            this.name = name;
            return this;
        }

        /** 如 {@code redis://127.0.0.1:6379}。 */
        public Builder address(String address) {
            this.address = address;
            return this;
        }

        public Builder password(String password) {
            this.password = password;
            return this;
        }

        public Builder database(int database) {
            this.database = database;
            return this;
        }

        public Builder connectionPoolSize(int connectionPoolSize) {
            this.connectionPoolSize = connectionPoolSize;
            return this;
        }

        public Builder keyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
            return this;
        }

        public RedissonLockAdapter build() {
            if (name == null || name.isBlank()) {
                throw new MetaPoolConfigException("RedissonLockAdapter requires a non-blank name");
            }
            return new RedissonLockAdapter(name, address, password, database,
                    connectionPoolSize, keyPrefix);
        }
    }
}
