package com.metapool.adapter.netty;

import com.metapool.common.exception.MetaPoolConfigException;
import com.metapool.common.resource.ManagedResource;
import com.metapool.common.resource.ResourceTypes;
import com.metapool.common.spi.ResourceAdapterFactory;
import com.metapool.common.spi.ResourceDefinition;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code memory} 类型的适配器工厂：从 {@link ResourceDefinition} 构建 {@link NettyByteBufAdapter}。
 *
 * <p>通过 JDK {@link java.util.ServiceLoader} 注册于
 * {@code META-INF/services/com.metapool.common.spi.ResourceAdapterFactory}。
 *
 * <table border="1">
 *   <caption>memory 配置项</caption>
 *   <tr><th>key</th><th>默认</th><th>说明</th></tr>
 *   <tr><td>{@code prefer-direct}</td><td>{@code true}</td>
 *       <td>用堆外内存（池化的价值所在）；<b>构造期设置，不可热调</b></td></tr>
 *   <tr><td>{@code default-capacity}</td><td>{@code 256}</td><td>borrow 时的初始容量，<b>可热调</b></td></tr>
 *   <tr><td>{@code max-capacity}</td><td>{@code Integer.MAX_VALUE}</td><td>单个 buf 上限，<b>可热调</b></td></tr>
 * </table>
 *
 * @since 2.4.0
 */
public final class NettyByteBufAdapterFactory implements ResourceAdapterFactory {

    @Override
    public String supportedType() {
        return ResourceTypes.MEMORY;
    }

    @Override
    public ManagedResource create(ResourceDefinition definition) {
        if (!supportedType().equals(definition.type())) {
            throw new MetaPoolConfigException(
                    "NettyByteBufAdapterFactory cannot handle type '" + definition.type() + "'");
        }
        Map<String, Object> props = definition.properties();
        String name = definition.name();

        Set<String> tunable = definition.tunableKeys().isEmpty()
                ? NettyByteBufAdapter.SUPPORTED_TUNABLE_KEYS
                : definition.tunableKeys();

        return NettyByteBufAdapter.builder()
                .named(name)
                .preferDirect(parseBoolean(name, props.get("prefer-direct")))
                .defaultCapacity(parseInt(name, NettyByteBufAdapter.KEY_DEFAULT_CAPACITY,
                        props.get(NettyByteBufAdapter.KEY_DEFAULT_CAPACITY), 256))
                .maxCapacity(parseInt(name, NettyByteBufAdapter.KEY_MAX_CAPACITY,
                        props.get(NettyByteBufAdapter.KEY_MAX_CAPACITY), Integer.MAX_VALUE))
                .tunable(tunable)
                .build();
    }

    /** 非法数值必须报 {@link MetaPoolConfigException}，不能漏出裸 NumberFormatException（RULES §3.2）。 */
    private static int parseInt(String name, String key, Object raw, int defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        if (raw instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new MetaPoolConfigException("memory '" + name + "' has invalid '" + key + "': " + raw, e);
        }
    }

    /**
     * 同 lettuce 适配器：<b>不用 {@code Boolean.parseBoolean}</b> ——
     * 它会把 {@code "yes"} 静默变成 {@code false}，于是使用方以为用的是堆外内存、实际是堆内，
     * 而这个差别只有在压测或 OOM 时才会暴露。
     */
    private static boolean parseBoolean(String name, Object raw) {
        if (raw == null) {
            return true;
        }
        if (raw instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if ("true".equals(s)) {
            return true;
        }
        if ("false".equals(s)) {
            return false;
        }
        throw new MetaPoolConfigException("memory '" + name
                + "' has invalid 'prefer-direct': " + raw + " (expected true/false)");
    }
}
