package com.metapool.common.resource;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 统一可观测契约（🎯 头牌能力之一）。
 *
 * <p>每个被治理资源把自身指标注册到给定的 Micrometer {@link MeterRegistry}，并打上统一 tag：
 * {@code metapool.resource=<name>}、{@code metapool.type=<type>}。统一 tag 规范是「一个 Grafana
 * 看板同时观察连接池 / 限流 / 线程池」这一头牌故事的技术地基。
 *
 * <p>包装成熟库时通常直接复用底层库已有的 Micrometer 指标（如 HikariCP、Bucket4j 自带的 binder），
 * 本方法只负责补齐统一 tag，无需字节码插桩。
 *
 * @since 2.0.0
 */
public interface MetricsSource {

    /**
     * 把本资源的指标绑定到给定 registry。
     *
     * <p><b>幂等：</b>对同一 registry 重复调用不得重复注册。无指标可暴露的资源实现为空操作。
     *
     * @param registry 目标 MeterRegistry，不为 null
     */
    void bindTo(MeterRegistry registry);
}
