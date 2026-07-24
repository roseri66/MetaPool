package com.metapool.common.spi;

import com.metapool.common.resource.ManagedResource;

/**
 * 适配器工厂 —— 把一个成熟库接入治理面的 SPI 扩展点。
 *
 * <p>每个工厂声明它支持的 {@link #supportedType() 资源类型}，并知道如何从 {@link ResourceDefinition}
 * 构建对应的 {@link ManagedResource}（内部包装 HikariCP / Bucket4j / Lettuce…）。
 *
 * <h3>发现机制</h3>
 * <p>通过 JDK {@link java.util.ServiceLoader} 注册于
 * {@code META-INF/services/com.metapool.common.spi.ResourceAdapterFactory}。控制面按
 * {@link #supportedType()} 建立 type → factory 的映射并缓存。这是「横向扩展到更多资源类型」的机制：
 * 新增一种资源 = 写一个工厂，核心零改动。
 *
 * <p>（本接口取代 1.0 的 {@code @SPI}/{@code ExtensionLoader}——后者无缓存无命名，弱于裸 ServiceLoader。）
 *
 * @since 2.0.0
 */
public interface ResourceAdapterFactory {

    /**
     * 本工厂支持的资源类型，如 {@code "datasource"}。
     *
     * @return 类型标识，非空且在进程内唯一
     */
    String supportedType();

    /**
     * 依据定义构建一个（尚未启动的）被治理资源。
     *
     * @param definition 资源定义
     * @return 构建好的资源
     * @throws com.metapool.common.exception.MetaPoolConfigException 配置非法
     */
    ManagedResource create(ResourceDefinition definition);
}
