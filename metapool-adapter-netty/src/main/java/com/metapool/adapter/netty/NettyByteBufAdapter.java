package com.metapool.adapter.netty;

import com.metapool.common.capability.Pool;
import com.metapool.common.exception.ErrorCode;
import com.metapool.common.exception.MetaPoolConfigException;
import com.metapool.common.exception.MetaPoolException;
import com.metapool.common.resource.ManagedResource;
import com.metapool.common.resource.ResourceTypes;
import com.metapool.common.resource.Tunable;
import com.metapool.common.stats.HealthStatus;
import com.metapool.common.stats.PoolStats;
import com.metapool.common.stats.TuneResult;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 把 Netty 的 {@link PooledByteBufAllocator}（池化堆外内存）纳入 MetaPool 治理面的适配器。
 *
 * <h3>⚠️ 它实现 {@link Pool}，但 {@code release()} 的语义比别的池<b>更强</b></h3>
 * <p>另外两个 {@code Pool} 实现（HikariCP、Commons Pool2）里，{@code release()} 就是「还给池」。
 * 这里不是：{@link ByteBuf} 用<b>引用计数</b>管理，{@code release()} 是<b>把计数减一</b>，
 * <b>减到 0 时才真正回池</b>。一个 buf 可以被 {@code retain()} 多次、由多个组件各自 release，
 * 最后一次才归还。
 *
 * <table border="1">
 *   <caption>与另外两个 Pool 实现的差别</caption>
 *   <tr><th></th><th>HikariCP / Commons Pool2</th><th>Netty ByteBuf</th></tr>
 *   <tr><td>release 的含义</td><td>还给池</td><td><b>引用计数减一</b>，到 0 才回池</td></tr>
 *   <tr><td>忘了 release</td><td>池耗尽 → 阻塞报错，<b>能发现</b></td>
 *       <td><b>堆外内存泄漏</b>，GC 不管，OOM 之前无声无息</td></tr>
 *   <tr><td>能被多方持有吗</td><td>不能</td><td>能（{@code retain()}）</td></tr>
 * </table>
 *
 * <h3>那为什么这次可以实现 {@code Pool}，而 lettuce 适配器不行</h3>
 * <p>Netty <b>确实存在</b> borrow/release 这个动作，只是语义更强（带引用计数）；
 * 而 Lettuce 的单连接多路复用<b>根本没有</b>这个动作（见 {@code adapter-lettuce}）。
 * <b>「语义更强」和「语义不存在」是两回事</b> —— 前者可以映射并在文档里注明，
 * 后者只能靠撒谎才能映射。
 *
 * <p>因此本类的做法是：实现 {@code Pool<ByteBuf>}，但<b>把差异写在最显眼处</b>，
 * 而不是让调用方按普通池的直觉去用。
 *
 * <h3>🎯 治理面在这里的真价值：让堆外泄漏在当天可见</h3>
 * <p>堆外内存泄漏平时<b>完全不可见</b> —— 不 OOM 就没人知道。本适配器把
 * {@code allocated.total} 与 {@code released.total} 两条曲线并排导出，
 * <b>两条一分叉就说明有人忘了 release</b>，当天就能看见，而不是三周后 OOM 时才发现。
 *
 * <p>这与 lettuce 适配器导出「断连次数」是同一个主题：
 * <b>底层库为了好用而隐藏的东西，治理面负责让它重新可见。</b>
 *
 * @since 2.4.0
 */
public final class NettyByteBufAdapter implements ManagedResource, Pool<ByteBuf>, Tunable {

    private static final Logger log = LoggerFactory.getLogger(NettyByteBufAdapter.class);

    /** 内置可热调参数（kebab-case，与 YAML/tunable 声明一致）。 */
    static final String KEY_DEFAULT_CAPACITY = "default-capacity";
    static final String KEY_MAX_CAPACITY = "max-capacity";

    /**
     * 本适配器能够热调的全部参数。声明的 {@code tunable} 白名单必须是它的子集，
     * 否则构建期即失败（fail-fast，坑 P-13）。
     *
     * <p>{@code prefer-direct} <b>不在其中</b>：它是 {@code PooledByteBufAllocator} 的构造期设置，
     * 运行时改不了。<b>底层做不到的事不进白名单</b> —— 与 jdk-executor 的 {@code queue-capacity}、
     * lettuce 的 {@code auto-reconnect} 同一条判据。
     */
    static final Set<String> SUPPORTED_TUNABLE_KEYS = Set.of(KEY_DEFAULT_CAPACITY, KEY_MAX_CAPACITY);

    private final String name;
    private final boolean preferDirect;
    private final Set<String> tunableKeys;

    /** 调参时同时更新（{@link #start()} 不用它们重建分配器，但 borrow 每次都读，故 volatile）。 */
    private volatile int defaultCapacity;
    private volatile int maxCapacity;

    private volatile PooledByteBufAllocator allocator;

    private final AtomicLong totalAllocated = new AtomicLong();
    private final AtomicLong totalReleased = new AtomicLong();
    private final AtomicLong totalLeaked = new AtomicLong();
    /** 本适配器借出、尚未经本适配器释放的数量。见 {@link #poolStats()} 关于「近似」的说明。 */
    private final AtomicInteger outstanding = new AtomicInteger();

    NettyByteBufAdapter(String name, boolean preferDirect, int defaultCapacity, int maxCapacity,
                        Set<String> tunableKeys) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        if (defaultCapacity <= 0) {
            throw new MetaPoolConfigException("default-capacity must be positive, got " + defaultCapacity);
        }
        if (maxCapacity < defaultCapacity) {
            throw new MetaPoolConfigException("max-capacity(" + maxCapacity
                    + ") must not be less than default-capacity(" + defaultCapacity + ")");
        }
        this.preferDirect = preferDirect;
        this.defaultCapacity = defaultCapacity;
        this.maxCapacity = maxCapacity;
        this.tunableKeys = validateTunableKeys(name, tunableKeys);
    }

    private static Set<String> validateTunableKeys(String name, Set<String> keys) {
        Objects.requireNonNull(keys, "tunableKeys must not be null");
        Set<String> unsupported = new LinkedHashSet<>(keys);
        unsupported.removeAll(SUPPORTED_TUNABLE_KEYS);
        if (!unsupported.isEmpty()) {
            throw new MetaPoolConfigException("memory '" + name + "' declares unsupported tunable key(s) "
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
        return ResourceTypes.MEMORY;
    }

    // ==================== ManagedLifecycle ====================

    @Override
    public synchronized void start() {
        if (allocator != null) {
            return; // 幂等
        }
        allocator = new PooledByteBufAllocator(preferDirect);
        log.info("[MetaPool] memory '{}' started (preferDirect={}, defaultCapacity={}, maxCapacity={})",
                name, preferDirect, defaultCapacity, maxCapacity);
    }

    /**
     * 停机：<b>检漏但不强行释放</b>。
     *
     * <p>若仍有借出未释放的 buf，记 WARN 并计入 {@code leaked.total}，
     * <b>但绝不替调用方 release</b> —— 那块内存可能正被别处使用（{@code retain()} 过），
     * 强行释放会造成 use-after-free，<b>比泄漏更危险</b>。
     *
     * <p>{@code PooledByteBufAllocator} 本身没有 close()：它的内存随 JVM 回收，
     * 所以这里只置空引用并把泄漏计数落定。
     */
    @Override
    public synchronized void stop(Duration graceful) {
        if (allocator == null) {
            return; // 幂等
        }
        int leaked = outstanding.get();
        if (leaked > 0) {
            totalLeaked.addAndGet(leaked);
            log.warn("[MetaPool] memory '{}' stopping with {} buffer(s) borrowed but not released "
                    + "— NOT releasing them on your behalf (they may still be in use elsewhere); "
                    + "this is a leak, check allocated/released divergence", name, leaked);
        }
        // P-01：置空，保证 stop→start 能重建
        this.allocator = null;
        log.info("[MetaPool] memory '{}' stopped", name);
    }

    /**
     * 只有 UP / DOWN 两态。
     *
     * <p><b>刻意没有 DEGRADED</b>：内存分配器不存在「饱和但仍在工作」这个中间态 ——
     * 它要么分配成功，要么直接抛 {@code OutOfMemoryError}。为了和别的适配器「看起来一致」
     * 而硬造一个三态，就是在治理面上制造假象。泄漏该看指标，不该伪装成健康状态。
     */
    @Override
    public HealthStatus health() {
        return allocator != null ? HealthStatus.up() : HealthStatus.down("not started");
    }

    // ==================== MetricsSource ====================

    @Override
    public void bindTo(MeterRegistry registry) {
        Tags tags = Tags.of("metapool.resource", name, "metapool.type", type());
        Gauge.builder("metapool.memory.outstanding", outstanding, AtomicInteger::doubleValue)
                .tags(tags).register(registry);
        // 🎯 这两条并排看：一分叉就说明有人忘了 release，堆外泄漏当天可见
        FunctionCounter.builder("metapool.memory.allocated.total", totalAllocated, AtomicLong::doubleValue)
                .tags(tags).register(registry);
        FunctionCounter.builder("metapool.memory.released.total", totalReleased, AtomicLong::doubleValue)
                .tags(tags).register(registry);
        FunctionCounter.builder("metapool.memory.leaked.total", totalLeaked, AtomicLong::doubleValue)
                .tags(tags).register(registry);
        // 来自 Netty 自身的用量统计
        Gauge.builder("metapool.memory.used.direct.bytes", this,
                        a -> a.sampleMetric(true)).tags(tags).register(registry);
        Gauge.builder("metapool.memory.used.heap.bytes", this,
                        a -> a.sampleMetric(false)).tags(tags).register(registry);
    }

    /** 采样网关：未启动时返回 0，绝不抛异常（指标读取不该把调用方带崩）。 */
    private double sampleMetric(boolean direct) {
        PooledByteBufAllocator a = this.allocator;
        if (a == null) {
            return 0d;
        }
        try {
            return direct ? a.metric().usedDirectMemory() : a.metric().usedHeapMemory();
        } catch (RuntimeException e) {
            return 0d;
        }
    }

    // ==================== Pool<ByteBuf> ====================

    @Override
    public ByteBuf borrow() {
        PooledByteBufAllocator a = requireStarted();
        ByteBuf buf = a.buffer(defaultCapacity, maxCapacity);
        totalAllocated.incrementAndGet();
        outstanding.incrementAndGet();
        return buf;
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>阻抗说明：{@code timeout} 在本适配器上没有意义，会被忽略。</b>
     * 内存分配<b>不排队</b> —— 它要么立刻成功，要么直接抛 {@code OutOfMemoryError}，
     * 不存在「等一会儿就有了」的情形，因此没有可以限时的等待。
     *
     * <p>本项目里 {@code borrow(Duration)} 至此有<b>三种</b>语义强度，三边 javadoc 均写明：
     * <ul>
     *   <li>{@code CommonsPool2Adapter} —— <b>真超时</b>，按传入值限时</li>
     *   <li>{@code HikariAdapter} —— 以配置的 {@code connectionTimeout} 为界，参数仅作提示</li>
     *   <li><b>本适配器</b> —— 无等待语义，参数被忽略</li>
     * </ul>
     * 三者都不违反契约（契约是「最多等这么久」的上界），但调用方需要知道差别。
     */
    @Override
    public ByteBuf borrow(Duration timeout) {
        return borrow();
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>这里的 release 是「引用计数减一」，不是「还给池」</b>（见类注释）。
     * 只有当计数减到 0 时内存才真正回到 Netty 的池里。
     *
     * <p>归还 {@code null}、或引用计数已为 0 的 buf 时<b>静默忽略并记 WARN</b>（契约要求）——
     * 若放任 Netty 的 {@code IllegalReferenceCountException} 冒出去，会在使用方的
     * {@code finally} 块里掩盖掉业务的原始异常。
     */
    @Override
    public void release(ByteBuf resource) {
        if (resource == null) {
            return;
        }
        try {
            if (resource.refCnt() <= 0) {
                log.warn("[MetaPool] memory '{}' release ignored: buffer already fully released "
                        + "(refCnt=0)", name);
                return;
            }
            resource.release();
            totalReleased.incrementAndGet();
            outstanding.decrementAndGet();
        } catch (RuntimeException e) {
            log.warn("[MetaPool] memory '{}' release failed: {}", name, e.getMessage());
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>口径必须说清，否则会被误读：</b>
     * <ul>
     *   <li>{@code active} —— 本适配器借出、尚未经本适配器 {@link #release} 的数量。
     *       <b>这是近似值</b>：调用方 {@code retain()} 之后由别处 release 的，我们观测不到
     *       （坑 P-12 的同类问题：观测不到的事不该硬记）。</li>
     *   <li>{@code idle} —— <b>恒为 0</b>。Netty 的池以 arena/chunk 组织，
     *       没有「可数的空闲对象」这个概念，硬编一个数字只会是假的。
     *       空闲内存量请看 {@code metapool.memory.used.*.bytes} 指标。</li>
     *   <li>{@code pending} —— <b>恒为 0</b>。分配不排队（见 {@link #borrow(Duration)}）。</li>
     * </ul>
     */
    @Override
    public PoolStats poolStats() {
        return new PoolStats(outstanding.get(), 0, 0, totalAllocated.get(), totalReleased.get());
    }

    private PooledByteBufAllocator requireStarted() {
        PooledByteBufAllocator a = this.allocator;
        if (a == null) {
            throw new MetaPoolException(ErrorCode.INTERNAL, "memory '" + name + "' not started");
        }
        return a;
    }

    /** 取底层分配器，用于本适配器未覆盖的高级用法（composite buffer、ioBuffer 等）。 */
    public PooledByteBufAllocator unwrap() {
        return requireStarted();
    }

    // ==================== Tunable ====================

    @Override
    public Set<String> tunableKeys() {
        return tunableKeys;
    }

    /**
     * 热调容量。
     *
     * <p>与 jdk-executor 一样存在<b>互相约束</b>（{@code max >= default}），因此同样是
     * <b>先整组算出目标值并校验，非法则整组拒绝</b>，不留下「只改了一半」的配置（对照坑 P-19）。
     * 区别在于这里的两个值只是本适配器自己的字段、不调用底层 setter，所以不存在中间态非法的问题，
     * 无需按方向排序。
     */
    @Override
    public TuneResult apply(Map<String, Object> patch) {
        Objects.requireNonNull(patch, "patch must not be null");
        Map<String, String> rejected = new LinkedHashMap<>();
        Set<String> applied = new LinkedHashSet<>();

        if (allocator == null) {
            patch.keySet().forEach(k -> rejected.put(k, "resource not started"));
            return TuneResult.partial(Set.of(), rejected);
        }

        Integer newDefault = null;
        Integer newMax = null;
        for (Map.Entry<String, Object> e : patch.entrySet()) {
            String key = e.getKey();
            if (!tunableKeys.contains(key)) {
                rejected.put(key, "not in tunable whitelist " + tunableKeys);
                continue;
            }
            try {
                int v = parsePositiveInt(e.getValue());
                if (KEY_DEFAULT_CAPACITY.equals(key)) {
                    newDefault = v;
                } else if (KEY_MAX_CAPACITY.equals(key)) {
                    newMax = v;
                } else {
                    rejected.put(key, "unsupported by NettyByteBufAdapter");
                }
            } catch (IllegalArgumentException iae) {
                rejected.put(key, iae.getMessage());
            }
        }

        int targetDefault = newDefault != null ? newDefault : this.defaultCapacity;
        int targetMax = newMax != null ? newMax : this.maxCapacity;
        if (targetMax < targetDefault) {
            String reason = "resulting max-capacity(" + targetMax
                    + ") would be less than default-capacity(" + targetDefault + ")";
            if (newDefault != null) {
                rejected.put(KEY_DEFAULT_CAPACITY, reason);
            }
            if (newMax != null) {
                rejected.put(KEY_MAX_CAPACITY, reason);
            }
            return TuneResult.partial(applied, rejected);
        }

        if (newDefault != null) {
            this.defaultCapacity = targetDefault;
            applied.add(KEY_DEFAULT_CAPACITY);
        }
        if (newMax != null) {
            this.maxCapacity = targetMax;
            applied.add(KEY_MAX_CAPACITY);
        }
        if (!applied.isEmpty()) {
            log.info("[MetaPool] memory '{}' tuned defaultCapacity={}, maxCapacity={}",
                    name, defaultCapacity, maxCapacity);
        }
        return TuneResult.partial(applied, rejected);
    }

    /** 当前生效的容量配置（供测试与诊断）。 */
    public int defaultCapacity() {
        return defaultCapacity;
    }

    public int maxCapacity() {
        return maxCapacity;
    }

    private static int parsePositiveInt(Object v) {
        int i;
        try {
            i = (v instanceof Number n) ? n.intValue() : Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException nfe) {
            throw new IllegalArgumentException("not an integer: " + v);
        }
        if (i <= 0) {
            throw new IllegalArgumentException("must be positive, got " + i);
        }
        return i;
    }

    /** 极简 fluent 构建器。 */
    public static final class Builder {
        private String name;
        private boolean preferDirect = true;
        private int defaultCapacity = 256;
        private int maxCapacity = Integer.MAX_VALUE;
        private Set<String> tunableKeys = SUPPORTED_TUNABLE_KEYS;

        private Builder() {
        }

        public Builder named(String name) {
            this.name = name;
            return this;
        }

        /** {@code true} 用堆外内存（Netty 默认，也是池化真正的价值所在）。 */
        public Builder preferDirect(boolean preferDirect) {
            this.preferDirect = preferDirect;
            return this;
        }

        public Builder defaultCapacity(int defaultCapacity) {
            this.defaultCapacity = defaultCapacity;
            return this;
        }

        public Builder maxCapacity(int maxCapacity) {
            this.maxCapacity = maxCapacity;
            return this;
        }

        public Builder tunable(Set<String> keys) {
            this.tunableKeys = keys;
            return this;
        }

        public NettyByteBufAdapter build() {
            if (name == null || name.isBlank()) {
                throw new MetaPoolConfigException("NettyByteBufAdapter requires a non-blank name");
            }
            return new NettyByteBufAdapter(name, preferDirect, defaultCapacity, maxCapacity, tunableKeys);
        }
    }
}
