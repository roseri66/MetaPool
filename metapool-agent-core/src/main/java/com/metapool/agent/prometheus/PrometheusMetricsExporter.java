package com.metapool.agent.prometheus;

import com.metapool.agent.MetaPoolMetrics;
import com.metapool.agent.model.PoolMetricsSnapshot;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MetaPool → Micrometer Prometheus 指标桥接器。
 *
 * <p>从 {@link MetaPoolMetrics} 全局注册表拉取所有资源池的快照，按池类型
 * 和 pool_id 标签注册为 Micrometer Gauge / FunctionCounter，供 Prometheus
 * 抓取。
 *
 * <h3>指标命名规范</h3>
 * <p>Micrometer 名称格式：{@code metapool.{type}.{snake_case_name}}。
 * 拦截器原始名称（如 {@code executeCount}）在注册前通过 {@link #camelToSnake(String)}
 * 转为 {@code execute_count}，确保 Prometheus 输出符合"全小写+下划线"规范。
 *
 * <h3>Label 安全</h3>
 * <p>仅使用 {@code pool_id} 一个标签（值为 {@code "ClassName@identityHashCode"}），
 * 无高基数动态标签，符合 Prometheus Label 白名单规范。
 *
 * <h3>线程安全</h3>
 * <p>后台同步线程定时拉取快照并注册新增 Meter。已注册的 Meter 通过
 * {@link FunctionCounter} / {@link Gauge} 回调实时读取，零锁竞争。
 *
 * @since 0.1.0
 */
public final class PrometheusMetricsExporter {

    /**
     * 默认同步间隔（秒）。
     */
    static final int DEFAULT_SYNC_INTERVAL_SECONDS = 10;

    private final PrometheusMeterRegistry registry;
    private final ScheduledExecutorService scheduler;
    private final Set<String> registeredMeterKeys;
    private final AtomicBoolean started;

    public PrometheusMetricsExporter() {
        this.registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory("metapool-metrics-sync"));
        this.registeredMeterKeys = ConcurrentHashMap.newKeySet();
        this.started = new AtomicBoolean(false);
    }

    // ==================== 生命周期 ====================

    /**
     * 启动后台定时同步任务。
     *
     * <p>立即执行一次同步，之后每隔 {@value #DEFAULT_SYNC_INTERVAL_SECONDS} 秒
     * 同步一次，确保新创建的池实例的 Meter 被及时注册。
     */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        scheduler.scheduleAtFixedRate(
                this::syncMetrics,
                0,
                DEFAULT_SYNC_INTERVAL_SECONDS,
                TimeUnit.SECONDS);
    }

    /**
     * 停止后台同步任务并关闭 Registry。
     */
    public void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        registry.close();
    }

    // ==================== 抓取 ====================

    /**
     * 获取 Prometheus 文本格式的指标数据。
     *
     * <p>在执行抓取前立即同步一次，确保返回最新数据。
     *
     * @return Prometheus text format 字符串
     */
    public String scrape() {
        syncMetrics();
        return registry.scrape();
    }

    /**
     * 获取底层的 Micrometer MeterRegistry（供 Spring Boot Actuator 集成）。
     */
    public MeterRegistry getRegistry() {
        return registry;
    }

    /**
     * 返回 Priometheus MeterRegistry（供测试校验）。
     */
    PrometheusMeterRegistry getPrometheusRegistry() {
        return registry;
    }

    // ==================== 内部同步逻辑 ====================

    /**
     * 从 {@link MetaPoolMetrics} 拉取快照，将尚未注册的指标注册为 Micrometer Meter。
     *
     * <p>已注册的 Meter 无需重复注册 — 它们通过 {@link FunctionCounter} /
     * {@link Gauge} 回调实时读取 {@link MetaPoolMetrics} 中的最新值，Prometheus
     * 每次抓取时自动触发回调。
     */
    private void syncMetrics() {
        MetaPoolMetrics metrics = MetaPoolMetrics.getInstance();
        List<PoolMetricsSnapshot> snapshots = metrics.snapshot();
        if (snapshots.isEmpty()) {
            return;
        }

        for (PoolMetricsSnapshot snapshot : snapshots) {
            registerPoolMetrics(snapshot, metrics);
        }
    }

    /**
     * 为一个池实例注册所有 Meter。
     */
    private void registerPoolMetrics(PoolMetricsSnapshot snapshot, MetaPoolMetrics metrics) {
        String poolId = snapshot.getPoolId();
        String typeLabel = snapshot.getPoolType().getLabel();
        String prefix = "metapool." + typeLabel + ".";
        Tags tags = Tags.of("pool_id", poolId);

        // 注册 Gauge（瞬时值）
        for (String metricName : safeGauges(snapshot)) {
            String meterKey = "gauge:" + prefix + metricName + "|" + poolId;
            if (registeredMeterKeys.add(meterKey)) {
                String fullName = prefix + camelToSnake(metricName);
                Gauge.builder(fullName, metrics,
                                m -> (double) m.getGauge(poolId, metricName))
                        .tags(tags)
                        .description("MetaPool " + typeLabel + " " + metricName)
                        .register(registry);
            }
        }

        // 注册 FunctionCounter（单调递增计数器）
        for (String metricName : safeCounters(snapshot)) {
            String meterKey = "counter:" + prefix + metricName + "|" + poolId;
            if (registeredMeterKeys.add(meterKey)) {
                String fullName = prefix + camelToSnake(metricName);
                FunctionCounter.builder(fullName, metrics,
                                m -> (double) m.getCounter(poolId, metricName))
                        .tags(tags)
                        .description("MetaPool " + typeLabel + " " + metricName + " (total)")
                        .register(registry);
            }
        }
    }

    // ==================== 工具方法 ====================

    private static Iterable<String> safeGauges(PoolMetricsSnapshot snapshot) {
        return snapshot.getGauges() != null
                ? snapshot.getGauges().keySet()
                : Collections.emptySet();
    }

    private static Iterable<String> safeCounters(PoolMetricsSnapshot snapshot) {
        return snapshot.getCounters() != null
                ? snapshot.getCounters().keySet()
                : Collections.emptySet();
    }

    /**
     * 将驼峰命名转为蛇形命名（小写+下划线）。
     *
     * <p>Micrometer 1.14 的 PrometheusNamingConvention 仅做 dots→underscores，
     * 不做 camelCase→snake_case。此方法补上该转换，确保输出符合 Prometheus
     * 命名规范。
     *
     * <p>示例：{@code executeCount → execute_count}，
     * {@code directAllocatedBytes → direct_allocated_bytes}。
     *
     * @param camelCase 驼峰命名字符串
     * @return 蛇形命名字符串
     */
    static String camelToSnake(String camelCase) {
        if (camelCase == null || camelCase.isEmpty()) {
            return camelCase;
        }
        StringBuilder sb = new StringBuilder(camelCase.length() + 4);
        for (int i = 0; i < camelCase.length(); i++) {
            char c = camelCase.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static ThreadFactory daemonThreadFactory(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }
}
