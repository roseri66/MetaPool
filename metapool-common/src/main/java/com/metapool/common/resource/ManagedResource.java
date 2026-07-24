package com.metapool.common.resource;

/**
 * 被治理资源的身份根 —— 所有纳管资源的公共契约。
 *
 * <p>它只承载「治理」所需的最小身份（{@link #name()} / {@link #type()}），并组合两个<em>普适</em>的
 * 治理能力：{@link ManagedLifecycle 生命周期} 与 {@link MetricsSource 可观测}。它<b>不含任何功能性
 * API</b>（无 acquire/release/tryLock）——那些是各资源自己的能力接口（见
 * {@link com.metapool.common.capability}）。
 *
 * <h3>为什么需要它</h3>
 * <p>控制面要能把一堆<em>异构</em>资源当成同一种东西去枚举、监控、优雅停机。没有它，就回到 1.0 的碎片化：
 * 每种资源一套监控 / 停机代码。
 *
 * <h3>可选能力</h3>
 * <p>资源可<em>额外</em>实现下列能力接口，由控制面通过 {@code instanceof} 探测后启用：
 * <ul>
 *   <li>{@link Tunable} —— 支持运行时动态调参</li>
 *   <li>{@link com.metapool.common.capability.Pool} —— 池化借出/归还（DB/Redis/对象）</li>
 *   <li>{@link com.metapool.common.capability.RateLimiter} —— 限流</li>
 * </ul>
 * 不具备某能力的资源<b>不实现</b>对应接口，从而永不出现「假装实现却抛 UnsupportedOperationException」。
 *
 * @since 2.0.0
 */
public interface ManagedResource extends ManagedLifecycle, MetricsSource {

    /**
     * 全局唯一名，用作指标 tag、日志标识、控制面查找 key。
     *
     * @return 资源名，非空
     */
    String name();

    /**
     * 资源类型标识，如 {@code "datasource"}、{@code "rate-limiter"}。
     *
     * <p>用 String 而非固定枚举，是为了不阻碍第三方通过 SPI 接入自定义资源类型。
     * 内置类型常量见 {@link ResourceTypes}。
     *
     * @return 类型标识，非空
     */
    String type();
}
