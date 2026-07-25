package com.metapool.adapter.bucket4j;

import com.metapool.common.capability.RateLimiter;
import com.metapool.common.exception.MetaPoolConfigException;
import com.metapool.common.exception.MetaPoolException;
import com.metapool.common.resource.ManagedResource;
import com.metapool.common.resource.ResourceTypes;
import com.metapool.common.resource.Tunable;
import com.metapool.common.stats.HealthStatus;
import com.metapool.common.stats.RateLimiterStats;
import com.metapool.common.stats.TuneResult;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.Refill;
import io.github.bucket4j.TokensInheritanceStrategy;
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
import java.util.concurrent.atomic.AtomicLong;

/**
 * 把 Bucket4j 令牌桶限流器纳入 MetaPool 治理面的适配器。
 *
 * <p>限流<b>不是池</b>——它<b>不实现</b> {@link com.metapool.common.capability.Pool}：令牌没有「归还」语义。
 * 这正是对 1.0「令牌桶被硬套 acquire/release 资源池」错误的纠正，也验证了 2.0 治理抽象对「非池资源」同样成立。
 *
 * <h3>能力落点</h3>
 * <ul>
 *   <li>{@link com.metapool.common.resource.ManagedLifecycle} — start 建桶；stop 无需 drain（无在用资源）</li>
 *   <li>{@link com.metapool.common.resource.MetricsSource} — 注册 {@code metapool.ratelimiter.*} 指标，打统一 tag</li>
 *   <li>{@link RateLimiter} — tryAcquire 直通 Bucket4j；带超时版用 {@code asBlocking().tryConsume}</li>
 *   <li>{@link Tunable} — 经 {@code replaceConfiguration} 热调 {@code limit-for-period}</li>
 * </ul>
 *
 * <h3>使用（编程式）</h3>
 * <pre>{@code
 * Bucket4jAdapter rl = Bucket4jAdapter.builder()
 *         .named("order-api").limitForPeriod(100).refillPeriod(Duration.ofSeconds(1)).build();
 * rl.start();
 * if (rl.tryAcquire(1)) { ... }
 * rl.stop(Duration.ZERO);
 * }</pre>
 *
 * @since 2.0.0
 */
public final class Bucket4jAdapter implements ManagedResource, RateLimiter, Tunable {

    private static final Logger log = LoggerFactory.getLogger(Bucket4jAdapter.class);

    /** 内置可热调参数（kebab-case，与 YAML/tunable 声明一致）。 */
    static final String KEY_LIMIT_FOR_PERIOD = "limit-for-period";

    private final String name;
    private final Duration refillPeriod;
    private final Set<String> tunableKeys;

    /** 当前每周期令牌上限；调参时更新 */
    private volatile long limitForPeriod;
    private volatile Bucket bucket;

    private final AtomicLong totalAcquired = new AtomicLong();
    private final AtomicLong totalRejected = new AtomicLong();
    private volatile boolean metricsBound;

    Bucket4jAdapter(String name, long limitForPeriod, Duration refillPeriod, Set<String> tunableKeys) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        if (limitForPeriod <= 0) {
            throw new MetaPoolConfigException("limit-for-period must be positive, got " + limitForPeriod);
        }
        this.refillPeriod = Objects.requireNonNull(refillPeriod, "refillPeriod must not be null");
        if (refillPeriod.isZero() || refillPeriod.isNegative()) {
            throw new MetaPoolConfigException("refill-period must be positive, got " + refillPeriod);
        }
        this.limitForPeriod = limitForPeriod;
        this.tunableKeys = Set.copyOf(tunableKeys);
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
        return ResourceTypes.RATE_LIMITER;
    }

    // ==================== ManagedLifecycle ====================

    @Override
    public synchronized void start() {
        if (bucket != null) {
            return; // 幂等
        }
        bucket = Bucket.builder().addLimit(bandwidth(limitForPeriod)).build();
        log.info("[MetaPool] rate-limiter '{}' started (limitForPeriod={}/{}ms)",
                name, limitForPeriod, refillPeriod.toMillis());
    }

    @Override
    public void stop(Duration graceful) {
        // 限流器无在用资源，无需 drain；直接停用
        bucket = null;
        log.info("[MetaPool] rate-limiter '{}' stopped", name);
    }

    @Override
    public HealthStatus health() {
        return bucket != null ? HealthStatus.up() : HealthStatus.down("not started");
    }

    private Bandwidth bandwidth(long tokens) {
        return Bandwidth.classic(tokens, Refill.greedy(tokens, refillPeriod));
    }

    // ==================== MetricsSource ====================

    @Override
    public synchronized void bindTo(MeterRegistry registry) {
        if (metricsBound) {
            return;
        }
        Tags tags = Tags.of("metapool.resource", name, "metapool.type", type());
        Gauge.builder("metapool.ratelimiter.available.tokens", this, Bucket4jAdapter::sampleAvailable)
                .tags(tags).register(registry);
        FunctionCounter.builder("metapool.ratelimiter.acquired.total", totalAcquired, AtomicLong::doubleValue)
                .tags(tags).register(registry);
        FunctionCounter.builder("metapool.ratelimiter.rejected.total", totalRejected, AtomicLong::doubleValue)
                .tags(tags).register(registry);
        metricsBound = true;
    }

    private double sampleAvailable() {
        Bucket b = this.bucket;
        return b != null ? b.getAvailableTokens() : 0d;
    }

    // ==================== RateLimiter ====================

    @Override
    public boolean tryAcquire(int permits) {
        boolean ok = requireStarted().tryConsume(permits);
        record(ok);
        return ok;
    }

    @Override
    public boolean tryAcquire(int permits, Duration timeout) throws InterruptedException {
        boolean ok = requireStarted().asBlocking().tryConsume(permits, timeout);
        record(ok);
        return ok;
    }

    @Override
    public RateLimiterStats limiterStats() {
        Bucket b = this.bucket;
        long available = b != null ? b.getAvailableTokens() : 0L;
        return new RateLimiterStats(available, totalAcquired.get(), totalRejected.get());
    }

    private void record(boolean acquired) {
        (acquired ? totalAcquired : totalRejected).incrementAndGet();
    }

    // ==================== Tunable ====================

    @Override
    public Set<String> tunableKeys() {
        return tunableKeys;
    }

    @Override
    public TuneResult apply(Map<String, Object> patch) {
        Bucket b = this.bucket;
        Map<String, String> rejected = new LinkedHashMap<>();
        Set<String> applied = new LinkedHashSet<>();

        if (b == null) {
            patch.keySet().forEach(k -> rejected.put(k, "resource not started"));
            return TuneResult.partial(Set.of(), rejected);
        }

        for (Map.Entry<String, Object> e : patch.entrySet()) {
            String key = e.getKey();
            if (!tunableKeys.contains(key)) {
                rejected.put(key, "not in tunable whitelist " + tunableKeys);
                continue;
            }
            if (KEY_LIMIT_FOR_PERIOD.equals(key)) {
                try {
                    long v = requirePositiveLong(e.getValue());
                    BucketConfiguration newCfg = BucketConfiguration.builder()
                            .addLimit(bandwidth(v)).build();
                    // ADDITIVE：调高上限时立即把增量额度授予当前桶（运维直觉——放宽限流即时生效）；
                    // 调低时相应扣减。相较 PROPORTIONALLY 更适合「运行时放宽/收紧限流」的治理语义。
                    b.replaceConfiguration(newCfg, TokensInheritanceStrategy.ADDITIVE);
                    this.limitForPeriod = v;
                    applied.add(key);
                } catch (IllegalArgumentException iae) {
                    rejected.put(key, iae.getMessage());
                }
            } else {
                rejected.put(key, "unsupported by Bucket4jAdapter");
            }
        }
        if (!applied.isEmpty()) {
            log.info("[MetaPool] rate-limiter '{}' tuned limitForPeriod={}", name, limitForPeriod);
        }
        return TuneResult.partial(applied, rejected);
    }

    // ==================== helpers ====================

    private Bucket requireStarted() {
        Bucket b = this.bucket;
        if (b == null) {
            throw new MetaPoolException(com.metapool.common.exception.ErrorCode.INTERNAL,
                    "rate-limiter '" + name + "' not started");
        }
        return b;
    }

    private static long requirePositiveLong(Object v) {
        long l = (v instanceof Number n) ? n.longValue() : Long.parseLong(String.valueOf(v));
        if (l <= 0) {
            throw new IllegalArgumentException("must be positive, got " + l);
        }
        return l;
    }

    /** 极简 fluent 构建器。 */
    public static final class Builder {
        private String name;
        private long limitForPeriod = -1;
        private Duration refillPeriod = Duration.ofSeconds(1);
        private Set<String> tunableKeys = Set.of(KEY_LIMIT_FOR_PERIOD);

        private Builder() {
        }

        public Builder named(String name) {
            this.name = name;
            return this;
        }

        public Builder limitForPeriod(long limitForPeriod) {
            this.limitForPeriod = limitForPeriod;
            return this;
        }

        public Builder refillPeriod(Duration refillPeriod) {
            this.refillPeriod = refillPeriod;
            return this;
        }

        public Builder tunable(Set<String> keys) {
            this.tunableKeys = keys;
            return this;
        }

        public Bucket4jAdapter build() {
            if (name == null || name.isBlank()) {
                throw new MetaPoolConfigException("Bucket4jAdapter requires a non-blank name");
            }
            return new Bucket4jAdapter(name, limitForPeriod, refillPeriod, tunableKeys);
        }
    }
}
