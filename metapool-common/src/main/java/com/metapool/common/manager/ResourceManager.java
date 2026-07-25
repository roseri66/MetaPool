package com.metapool.common.manager;

import com.metapool.common.resource.ManagedResource;
import com.metapool.common.stats.HealthStatus;
import com.metapool.common.stats.TuneResult;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * 资源治理控制面 —— 注册表 + 编排器。用户与 Actuator 端点访问 MetaPool 的<b>唯一入口</b>。
 *
 * <h3>为什么需要它</h3>
 * <p>治理是横切的：要按依赖顺序启动、逆序优雅停机、聚合全局健康、统一绑定指标。这些需要一个中心编排者。
 * 没有它，「统一」就退化成一堆各自为政的适配器。
 *
 * <h3>边界纪律</h3>
 * <p>这是一个<b>进程内对象</b>（本质是 {@code Map<String, ManagedResource>} + 编排逻辑），
 * <b>不是</b>微服务、消息队列或分布式注册中心。绝不引入任何分布式基础设施。
 *
 * <p>实现 {@link AutoCloseable}，{@link #close()} 执行逆序优雅停机，可配合 try-with-resources。
 *
 * @since 2.0.0
 */
public interface ResourceManager extends AutoCloseable {

    /**
     * 注册一个资源（尚未启动）。
     *
     * @param resource 被治理资源
     * @param <R>      资源具体类型
     * @return 传入的同一实例，便于链式使用
     * @throws com.metapool.common.exception.MetaPoolException 名字冲突
     */
    <R extends ManagedResource> R register(R resource);

    /**
     * 按名获取资源。
     *
     * @throws com.metapool.common.exception.MetaPoolException 不存在（{@code RESOURCE_NOT_FOUND}）
     */
    ManagedResource get(String name);

    /** 按名查找资源，不存在返回空 Optional。 */
    Optional<ManagedResource> find(String name);

    /** 所有已注册资源的只读视图。 */
    Collection<ManagedResource> resources();

    /** 按注册顺序启动全部资源。已启动的资源被幂等跳过。 */
    void start();

    /** 把全部资源的指标绑定到给定 registry（统一 tag）。 */
    void bindMetrics(MeterRegistry registry);

    /** 聚合健康：任一资源 DOWN 则整体 DOWN；任一 DEGRADED 则整体 DEGRADED。 */
    HealthStatus health();

    /**
     * 对指定资源动态调参。
     *
     * @param name  资源名
     * @param patch 参数名 → 新值
     * @return 调参结果
     * @throws com.metapool.common.exception.MetaPoolException 资源不存在，或该资源不支持
     *         {@link com.metapool.common.resource.Tunable}
     */
    TuneResult tune(String name, Map<String, Object> patch);

    /** 逆序优雅停机全部资源。 */
    @Override
    void close();
}
