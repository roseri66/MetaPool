package com.metapool.common.spi;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 资源定义 —— 声明式接入的中间表示，不可变。
 *
 * <p>由配置绑定层（如 spring-starter 从 YAML）产出，交给 {@link ResourceAdapterFactory} 构建
 * {@link com.metapool.common.resource.ManagedResource}。{@code properties} 直通底层库的原生参数名
 * （如 HikariCP 的 {@code maximum-pool-size}），MetaPool 不发明第二套参数命名。
 *
 * @param name         资源全局唯一名
 * @param type         资源类型（见 {@link com.metapool.common.resource.ResourceTypes}）
 * @param properties   直通底层库的原始配置
 * @param tunableKeys  声明可运行时热调的参数白名单
 * @since 2.0.0
 */
public record ResourceDefinition(String name, String type,
                                 Map<String, Object> properties, Set<String> tunableKeys) {

    public ResourceDefinition {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(type, "type must not be null");
        properties = properties == null ? Map.of() : Map.copyOf(properties);
        tunableKeys = tunableKeys == null ? Set.of() : Set.copyOf(tunableKeys);
    }
}
