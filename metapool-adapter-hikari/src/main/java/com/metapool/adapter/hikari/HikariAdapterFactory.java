package com.metapool.adapter.hikari;

import com.metapool.common.exception.MetaPoolConfigException;
import com.metapool.common.resource.ManagedResource;
import com.metapool.common.resource.ResourceTypes;
import com.metapool.common.spi.ResourceAdapterFactory;
import com.metapool.common.spi.ResourceDefinition;
import com.zaxxer.hikari.HikariConfig;

import java.util.Map;
import java.util.Properties;

/**
 * {@code datasource} 类型的适配器工厂：从 {@link ResourceDefinition} 构建 {@link HikariAdapter}。
 *
 * <p>通过 JDK {@link java.util.ServiceLoader} 注册于
 * {@code META-INF/services/com.metapool.common.spi.ResourceAdapterFactory}。
 *
 * <p>配置直通：{@link ResourceDefinition#properties()} 的 kebab-case key（如 {@code maximum-pool-size}）
 * 转成 HikariCP 的 camelCase 属性名（{@code maximumPoolSize}），交由 {@link HikariConfig#HikariConfig(Properties)}
 * 完成类型转换与赋值——MetaPool 不发明第二套参数命名。
 *
 * @since 2.0.0
 */
public final class HikariAdapterFactory implements ResourceAdapterFactory {

    @Override
    public String supportedType() {
        return ResourceTypes.DATASOURCE;
    }

    @Override
    public ManagedResource create(ResourceDefinition definition) {
        if (!supportedType().equals(definition.type())) {
            throw new MetaPoolConfigException(
                    "HikariAdapterFactory cannot handle type '" + definition.type() + "'");
        }
        try {
            HikariConfig config = new HikariConfig(toHikariProperties(definition.properties()));
            return HikariAdapter.from(config)
                    .named(definition.name())
                    .tunable(definition.tunableKeys().isEmpty()
                            ? java.util.Set.of(HikariAdapter.KEY_MAX_POOL_SIZE, HikariAdapter.KEY_CONNECTION_TIMEOUT)
                            : definition.tunableKeys())
                    .build();
        } catch (RuntimeException e) {
            throw new MetaPoolConfigException(
                    "Invalid datasource config for '" + definition.name() + "': " + e.getMessage(), e);
        }
    }

    private static Properties toHikariProperties(Map<String, Object> props) {
        Properties p = new Properties();
        props.forEach((k, v) -> {
            if (v != null) {
                p.setProperty(kebabToCamel(k), String.valueOf(v));
            }
        });
        return p;
    }

    /** {@code maximum-pool-size} → {@code maximumPoolSize}；{@code jdbc-url} → {@code jdbcUrl}。 */
    static String kebabToCamel(String key) {
        if (key.indexOf('-') < 0) {
            return key;
        }
        StringBuilder sb = new StringBuilder(key.length());
        boolean upper = false;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '-') {
                upper = true;
            } else {
                sb.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return sb.toString();
    }
}
