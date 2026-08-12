package com.metapool.adapter.executor;

import com.metapool.common.exception.MetaPoolConfigException;
import com.metapool.common.resource.ManagedResource;
import com.metapool.common.resource.ResourceTypes;
import com.metapool.common.spi.ResourceAdapterFactory;
import com.metapool.common.spi.ResourceDefinition;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * {@code executor} 类型的适配器工厂：从 {@link ResourceDefinition} 构建 {@link JdkExecutorAdapter}。
 *
 * <p>通过 JDK {@link java.util.ServiceLoader} 注册于
 * {@code META-INF/services/com.metapool.common.spi.ResourceAdapterFactory}。
 *
 * <p>识别的配置项：
 * <table border="1">
 *   <caption>executor 配置项</caption>
 *   <tr><th>key</th><th>默认</th><th>说明</th></tr>
 *   <tr><td>{@code core-pool-size}</td><td><b>必填</b></td><td>核心线程数，&ge; 0</td></tr>
 *   <tr><td>{@code maximum-pool-size}</td><td>= core</td><td>最大线程数，&ge; 1</td></tr>
 *   <tr><td>{@code queue-capacity}</td><td>{@code Integer.MAX_VALUE}</td>
 *       <td>0 = 不排队（SynchronousQueue）；⚠️ 无界时 max 不生效，见 {@link JdkExecutorAdapter} 类注释</td></tr>
 *   <tr><td>{@code keep-alive}</td><td>{@code 60s}</td><td>如 {@code 30s} / {@code 500ms} / {@code PT1M} / 纯毫秒数</td></tr>
 *   <tr><td>{@code rejection-policy}</td><td>{@code abort}</td>
 *       <td>{@code abort} / {@code caller-runs} / {@code discard} / {@code discard-oldest}</td></tr>
 * </table>
 *
 * @since 2.1.0
 */
public final class JdkExecutorAdapterFactory implements ResourceAdapterFactory {

    @Override
    public String supportedType() {
        return ResourceTypes.EXECUTOR;
    }

    @Override
    public ManagedResource create(ResourceDefinition definition) {
        if (!supportedType().equals(definition.type())) {
            throw new MetaPoolConfigException(
                    "JdkExecutorAdapterFactory cannot handle type '" + definition.type() + "'");
        }
        Map<String, Object> props = definition.properties();
        String name = definition.name();

        Object coreRaw = props.get(JdkExecutorAdapter.KEY_CORE_POOL_SIZE);
        if (coreRaw == null) {
            throw new MetaPoolConfigException("executor '" + name + "' requires 'core-pool-size'");
        }
        int core = parseInt(name, JdkExecutorAdapter.KEY_CORE_POOL_SIZE, coreRaw);
        Object maxRaw = props.get(JdkExecutorAdapter.KEY_MAXIMUM_POOL_SIZE);
        int max = maxRaw == null ? core : parseInt(name, JdkExecutorAdapter.KEY_MAXIMUM_POOL_SIZE, maxRaw);
        Object queueRaw = props.get("queue-capacity");
        int queueCapacity = queueRaw == null ? Integer.MAX_VALUE : parseInt(name, "queue-capacity", queueRaw);
        Duration keepAlive = parseDuration(props.getOrDefault("keep-alive", "60s"));
        RejectionPolicy policy = RejectionPolicy.from(props.getOrDefault("rejection-policy", "abort"));

        Set<String> tunable = definition.tunableKeys().isEmpty()
                ? JdkExecutorAdapter.SUPPORTED_TUNABLE_KEYS
                : definition.tunableKeys();

        return JdkExecutorAdapter.builder()
                .named(name)
                .corePoolSize(core)
                .maximumPoolSize(max)
                .queueCapacity(queueCapacity)
                .keepAlive(keepAlive)
                .rejectionPolicy(policy)
                .tunable(tunable)
                .build();
    }

    /** 非法数值必须报 {@link MetaPoolConfigException}，不能漏出裸 NumberFormatException（RULES §3.2）。 */
    private static int parseInt(String name, String key, Object raw) {
        if (raw instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new MetaPoolConfigException("executor '" + name + "' has invalid '" + key + "': " + raw, e);
        }
    }

    /**
     * 支持 {@code "60s"} / {@code "500ms"} / {@code "2m"} / 纯毫秒数字 / ISO-8601（{@code PT1M}）。
     *
     * <p>与 {@code Bucket4jAdapterFactory.parseDuration} 目前是重复实现。刻意不提前抽取到
     * {@code metapool-common}：那是已发布的公开契约层，为两处重复就改它不划算。
     * <b>第三个 adapter 再需要 duration 解析时就抽取</b>（届时重复三处，收益才盖过成本）。
     */
    static Duration parseDuration(Object raw) {
        if (raw instanceof Number n) {
            return Duration.ofMillis(n.longValue());
        }
        String s = String.valueOf(raw).trim();
        try {
            if (s.startsWith("PT") || s.startsWith("pt")) {
                return Duration.parse(s);
            }
            if (s.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(s.substring(0, s.length() - 2).trim()));
            }
            if (s.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(s.substring(0, s.length() - 1).trim()));
            }
            if (s.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(s.substring(0, s.length() - 1).trim()));
            }
            return Duration.ofMillis(Long.parseLong(s));
        } catch (RuntimeException e) {
            throw new MetaPoolConfigException("invalid keep-alive '" + s + "'", e);
        }
    }
}
