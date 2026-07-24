package com.metapool.core;

import com.metapool.common.exception.MetaPoolConfigException;
import com.metapool.common.resource.ManagedResource;
import com.metapool.common.spi.ResourceAdapterFactory;
import com.metapool.common.spi.ResourceDefinition;

import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * 适配器工厂加载器 —— 通过 JDK {@link ServiceLoader} 发现所有 {@link ResourceAdapterFactory}，
 * 建立 {@code type → factory} 映射并缓存，供声明式接入（配置 → {@link ResourceDefinition} → 资源）使用。
 *
 * <p>这是「横向扩展到更多资源类型」的落点：类路径上多一个 adapter jar，就自动多支持一种 {@code type}，
 * 核心零改动。取代 1.0 无缓存无命名的 {@code ExtensionLoader}。
 *
 * <h3>线程安全</h3>
 * <p>构造时一次性加载并冻结映射；此后只读，天然线程安全。
 *
 * @since 2.0.0
 */
public final class ResourceAdapterLoader {

    private final Map<String, ResourceAdapterFactory> factoriesByType;

    public ResourceAdapterLoader() {
        this(ServiceLoader.load(ResourceAdapterFactory.class));
    }

    /** 供测试注入自定义 ServiceLoader。 */
    ResourceAdapterLoader(ServiceLoader<ResourceAdapterFactory> loader) {
        Map<String, ResourceAdapterFactory> map = new HashMap<>();
        for (ResourceAdapterFactory f : loader) {
            String type = f.supportedType();
            ResourceAdapterFactory prev = map.putIfAbsent(type, f);
            if (prev != null) {
                throw new MetaPoolConfigException("duplicate ResourceAdapterFactory for type '" + type
                        + "': " + prev.getClass().getName() + " vs " + f.getClass().getName());
            }
        }
        this.factoriesByType = Map.copyOf(map);
    }

    /** 已发现的资源类型集合。 */
    public java.util.Set<String> supportedTypes() {
        return factoriesByType.keySet();
    }

    /**
     * 依据定义构建（尚未启动的）被治理资源。
     *
     * @throws MetaPoolConfigException 无支持该 {@code type} 的工厂
     */
    public ManagedResource create(ResourceDefinition definition) {
        ResourceAdapterFactory factory = factoriesByType.get(definition.type());
        if (factory == null) {
            throw new MetaPoolConfigException("no ResourceAdapterFactory for type '" + definition.type()
                    + "' (available: " + factoriesByType.keySet() + ")");
        }
        return factory.create(definition);
    }
}
