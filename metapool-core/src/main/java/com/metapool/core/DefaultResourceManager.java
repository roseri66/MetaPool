package com.metapool.core;

import com.metapool.common.exception.ErrorCode;
import com.metapool.common.exception.MetaPoolException;
import com.metapool.common.manager.ResourceManager;
import com.metapool.common.resource.ManagedResource;
import com.metapool.common.resource.Tunable;
import com.metapool.common.stats.HealthStatus;
import com.metapool.common.stats.TuneResult;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * {@link ResourceManager} 的默认实现 —— MetaPool 控制面。
 *
 * <p>一个进程内对象：注册表（按注册顺序）+ 启停编排 + 聚合健康 + 调参路由。<b>不含</b>任何分布式设施。
 *
 * <h3>编排顺序</h3>
 * <ul>
 *   <li>{@link #start()} 按注册顺序启动</li>
 *   <li>{@link #close()} 按<b>逆序</b>优雅停机（后注册的先关，符合依赖方向）</li>
 * </ul>
 *
 * <h3>聚合健康</h3>
 * <p>任一资源 DOWN → 整体 DOWN；否则任一 DEGRADED → 整体 DEGRADED；否则 UP。
 *
 * <h3>线程安全</h3>
 * <p>注册/编排方法以内部锁保护；启停回调在锁外执行，避免长时间持锁。
 *
 * @since 2.0.0
 */
public final class DefaultResourceManager implements ResourceManager {

    private static final Logger log = LoggerFactory.getLogger(DefaultResourceManager.class);
    private static final Duration DEFAULT_GRACEFUL = Duration.ofSeconds(30);

    /** 保持注册顺序 */
    private final Map<String, ManagedResource> resources = new LinkedHashMap<>();
    private final Object lock = new Object();
    private final Duration shutdownGraceful;

    public DefaultResourceManager() {
        this(DEFAULT_GRACEFUL);
    }

    public DefaultResourceManager(Duration shutdownGraceful) {
        this.shutdownGraceful = shutdownGraceful == null ? DEFAULT_GRACEFUL : shutdownGraceful;
    }

    @Override
    public <R extends ManagedResource> R register(R resource) {
        String name = resource.name();
        synchronized (lock) {
            if (resources.containsKey(name)) {
                throw new MetaPoolException(ErrorCode.CONFIG_INVALID,
                        "resource name conflict: '" + name + "' already registered");
            }
            resources.put(name, resource);
        }
        log.info("[MetaPool] registered {} '{}'", resource.type(), name);
        return resource;
    }

    @Override
    public ManagedResource get(String name) {
        return find(name).orElseThrow(() -> new MetaPoolException(
                ErrorCode.RESOURCE_NOT_FOUND, "no managed resource named '" + name + "'"));
    }

    @Override
    public Optional<ManagedResource> find(String name) {
        synchronized (lock) {
            return Optional.ofNullable(resources.get(name));
        }
    }

    @Override
    public Collection<ManagedResource> resources() {
        return snapshot();
    }

    @Override
    public void start() {
        for (ManagedResource r : snapshot()) {
            r.start();
        }
        log.info("[MetaPool] control plane started, {} resource(s)", resources.size());
    }

    @Override
    public void bindMetrics(MeterRegistry registry) {
        for (ManagedResource r : snapshot()) {
            r.bindTo(registry);
        }
        log.info("[MetaPool] metrics bound for {} resource(s)", resources.size());
    }

    @Override
    public HealthStatus health() {
        boolean anyDown = false;
        boolean anyDegraded = false;
        StringJoiner problems = new StringJoiner(", ");
        for (ManagedResource r : snapshot()) {
            HealthStatus h = r.health();
            switch (h.status()) {
                case DOWN -> {
                    anyDown = true;
                    problems.add(r.name() + ":DOWN(" + h.detail() + ")");
                }
                case DEGRADED -> {
                    anyDegraded = true;
                    problems.add(r.name() + ":DEGRADED(" + h.detail() + ")");
                }
                case UP -> { /* ok */ }
            }
        }
        // 优先级：任一 DOWN → DOWN；否则任一 DEGRADED → DEGRADED；否则 UP
        if (anyDown) {
            return HealthStatus.down(problems.toString());
        }
        if (anyDegraded) {
            return HealthStatus.degraded(problems.toString());
        }
        return HealthStatus.up();
    }

    @Override
    public TuneResult tune(String name, Map<String, Object> patch) {
        ManagedResource r = get(name);
        if (!(r instanceof Tunable tunable)) {
            throw new MetaPoolException(ErrorCode.TUNE_REJECTED,
                    "resource '" + name + "' (" + r.type() + ") is not tunable");
        }
        return tunable.apply(patch);
    }

    @Override
    public void close() {
        List<ManagedResource> reversed = snapshot();
        java.util.Collections.reverse(reversed);
        for (ManagedResource r : reversed) {
            try {
                r.stop(shutdownGraceful);
            } catch (RuntimeException e) {
                log.warn("[MetaPool] error stopping '{}': {}", r.name(), e.getMessage());
            }
        }
        log.info("[MetaPool] control plane closed");
    }

    private List<ManagedResource> snapshot() {
        synchronized (lock) {
            return new ArrayList<>(resources.values());
        }
    }
}
