package com.metapool.adapter.lettuce;

import com.metapool.common.exception.MetaPoolConfigException;
import com.metapool.common.resource.ManagedResource;
import com.metapool.common.resource.ResourceTypes;
import com.metapool.common.spi.ResourceAdapterFactory;
import com.metapool.common.spi.ResourceDefinition;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * {@code redis} 类型的适配器工厂：从 {@link ResourceDefinition} 构建 {@link LettuceAdapter}。
 *
 * <p>通过 JDK {@link java.util.ServiceLoader} 注册于
 * {@code META-INF/services/com.metapool.common.spi.ResourceAdapterFactory}。
 *
 * <p>识别的配置项：
 * <table border="1">
 *   <caption>redis 配置项</caption>
 *   <tr><th>key</th><th>默认</th><th>说明</th></tr>
 *   <tr><td>{@code uri}</td><td><b>必填</b></td>
 *       <td>{@code redis://[:password@]host:port[/db]}，直通 {@code RedisURI.create}</td></tr>
 *   <tr><td>{@code command-timeout}</td><td>{@code 60s}</td><td><b>可热调</b></td></tr>
 *   <tr><td>{@code auto-reconnect}</td><td>{@code true}</td>
 *       <td>关掉后连接断开即报 DOWN 而非 DEGRADED</td></tr>
 * </table>
 *
 * @since 2.3.0
 */
public final class LettuceAdapterFactory implements ResourceAdapterFactory {

    @Override
    public String supportedType() {
        return ResourceTypes.REDIS;
    }

    @Override
    public ManagedResource create(ResourceDefinition definition) {
        if (!supportedType().equals(definition.type())) {
            throw new MetaPoolConfigException(
                    "LettuceAdapterFactory cannot handle type '" + definition.type() + "'");
        }
        Map<String, Object> props = definition.properties();
        String name = definition.name();

        Object uriRaw = props.get("uri");
        if (uriRaw == null || String.valueOf(uriRaw).isBlank()) {
            throw new MetaPoolConfigException("redis '" + name
                    + "' requires 'uri' (e.g. redis://127.0.0.1:6379)");
        }

        Set<String> tunable = definition.tunableKeys().isEmpty()
                ? LettuceAdapter.SUPPORTED_TUNABLE_KEYS
                : definition.tunableKeys();

        return LettuceAdapter.builder()
                .named(name)
                .uri(String.valueOf(uriRaw).trim())
                .commandTimeout(props.containsKey(LettuceAdapter.KEY_COMMAND_TIMEOUT)
                        ? parseDuration(props.get(LettuceAdapter.KEY_COMMAND_TIMEOUT))
                        : Duration.ofSeconds(60))
                .autoReconnect(parseBoolean(name, props.get("auto-reconnect")))
                .tunable(tunable)
                .build();
    }

    /** 非法布尔值必须报 {@link MetaPoolConfigException}，不能靠 parseBoolean 静默变成 false。 */
    private static boolean parseBoolean(String name, Object raw) {
        if (raw == null) {
            return true;
        }
        if (raw instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(raw).trim().toLowerCase(java.util.Locale.ROOT);
        if ("true".equals(s)) {
            return true;
        }
        if ("false".equals(s)) {
            return false;
        }
        // Boolean.parseBoolean("yes") 会静默返回 false —— 拼错的配置不该被当成「关闭」
        throw new MetaPoolConfigException("redis '" + name
                + "' has invalid 'auto-reconnect': " + raw + " (expected true/false)");
    }

    /**
     * 支持 {@code "60s"} / {@code "500ms"} / {@code "2m"} / 纯毫秒数字 / ISO-8601（{@code PT1M}）。
     *
     * <p>这是本项目第四处相同的 duration 解析（另三处在 bucket4j / jdk-executor / commons-pool2
     * 的工厂里）。当初立的约定是「第三个 adapter 再需要就抽取」，现在已经第四处了 ——
     * <b>抽取到 {@code metapool-common} 已经该做，但那是已发布公开契约层加 API，
     * 应单独设计并让用户拍板，不在适配器提交里顺手做。已记入待办。</b>
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
            throw new MetaPoolConfigException("invalid command-timeout '" + s + "'", e);
        }
    }
}
