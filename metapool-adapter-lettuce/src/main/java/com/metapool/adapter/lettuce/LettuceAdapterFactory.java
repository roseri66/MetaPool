package com.metapool.adapter.lettuce;

import com.metapool.common.exception.MetaPoolConfigException;
import com.metapool.common.resource.ManagedResource;
import com.metapool.common.resource.ResourceTypes;
import com.metapool.common.spi.ConfigValues;
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
                        ? ConfigValues.duration(LettuceAdapter.KEY_COMMAND_TIMEOUT, props.get(LettuceAdapter.KEY_COMMAND_TIMEOUT))
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

}
