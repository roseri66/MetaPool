package com.metapool.adapter.redisson;

import com.metapool.common.exception.MetaPoolConfigException;
import com.metapool.common.resource.ManagedResource;
import com.metapool.common.resource.ResourceTypes;
import com.metapool.common.spi.ResourceAdapterFactory;
import com.metapool.common.spi.ResourceDefinition;

import java.util.Map;

/**
 * {@code lock} 类型的适配器工厂：从 {@link ResourceDefinition} 构建 {@link RedissonLockAdapter}。
 *
 * <p>通过 JDK {@link java.util.ServiceLoader} 注册于
 * {@code META-INF/services/com.metapool.common.spi.ResourceAdapterFactory}。
 *
 * <p>识别的配置项：
 * <table border="1">
 *   <caption>lock 配置项</caption>
 *   <tr><th>key</th><th>默认</th><th>说明</th></tr>
 *   <tr><td>{@code address}</td><td><b>必填</b></td><td>如 {@code redis://127.0.0.1:6379}</td></tr>
 *   <tr><td>{@code password}</td><td>无</td><td>留空即不认证</td></tr>
 *   <tr><td>{@code database}</td><td>{@code 0}</td><td>Redis 库序号</td></tr>
 *   <tr><td>{@code connection-pool-size}</td><td>{@code 64}</td><td>直通 Redisson 同名参数</td></tr>
 *   <tr><td>{@code key-prefix}</td><td>{@code metapool:lock:}</td>
 *       <td>多应用共用一个 Redis 时避免撞键</td></tr>
 * </table>
 *
 * <p><b>注意本类型没有 {@code tunable} 参数</b>：Redisson 锁的 {@code waitTime} / {@code leaseTime}
 * 是每次调用传入的，不是配置项，因此适配器不实现 {@code Tunable}
 * （理由见 {@link RedissonLockAdapter} 类注释）。配置里声明 {@code tunable} 会被忽略。
 *
 * @since 2.1.0
 */
public final class RedissonLockAdapterFactory implements ResourceAdapterFactory {

    @Override
    public String supportedType() {
        return ResourceTypes.LOCK;
    }

    @Override
    public ManagedResource create(ResourceDefinition definition) {
        if (!supportedType().equals(definition.type())) {
            throw new MetaPoolConfigException(
                    "RedissonLockAdapterFactory cannot handle type '" + definition.type() + "'");
        }
        Map<String, Object> props = definition.properties();
        String name = definition.name();

        Object addressRaw = props.get("address");
        if (addressRaw == null || String.valueOf(addressRaw).isBlank()) {
            throw new MetaPoolConfigException("lock '" + name + "' requires 'address' "
                    + "(e.g. redis://127.0.0.1:6379)");
        }

        return RedissonLockAdapter.builder()
                .named(name)
                .address(String.valueOf(addressRaw).trim())
                .password(props.containsKey("password") ? String.valueOf(props.get("password")) : null)
                .database(parseInt(name, "database", props.get("database"), 0))
                .connectionPoolSize(parseInt(name, "connection-pool-size",
                        props.get("connection-pool-size"), 64))
                .keyPrefix(props.containsKey("key-prefix")
                        ? String.valueOf(props.get("key-prefix"))
                        : RedissonLockAdapter.DEFAULT_KEY_PREFIX)
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
            throw new MetaPoolConfigException("lock '" + name + "' has invalid '" + key + "': " + raw, e);
        }
    }
}
