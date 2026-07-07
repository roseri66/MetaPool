package com.metapool.spi.ai;

import com.metapool.common.pool.PoolConfig;
import com.metapool.common.spi.SPI;

import java.util.List;

/**
 * AI 参数调优建议 SPI 接口。
 *
 * <p>基于当前池配置和历史指标数据，给出参数调整建议。
 * 实现方可接入大模型或基于历史数据的统计分析来生成建议。
 *
 * <h3>线程安全</h3>
 * <p>实现类必须保证 {@link #advise(PoolConfig, List)} 方法的线程安全，
 * 允许多个线程并发调用。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * AiTuningAdvisor advisor = ExtensionLoader.getExtension(AiTuningAdvisor.class);
 * if (advisor != null) {
 *     TuningAdvice advice = advisor.advise(config, history);
 *     // 根据建议调整参数
 * }
 * }</pre>
 *
 * @see PoolConfig
 * @see PoolMetricsSnapshot
 * @see TuningAdvice
 * @since 0.1.0
 */
@SPI
public interface AiTuningAdvisor {

    /**
     * 根据当前池配置和历史指标数据，生成参数调优建议。
     *
     * <p>调用方应对返回 {@code null} 做判空兜底——
     * 当 {@link com.metapool.common.spi.ExtensionLoader} 未找到实现且无默认实现时返回 {@code null}。
     *
     * @param config  当前资源池配置，不能为 {@code null}
     * @param history 历史指标快照列表（按时间升序排列），可能为空列表
     * @return 调优建议；无可用实现时返回 {@code null}
     */
    TuningAdvice advise(PoolConfig config, List<PoolMetricsSnapshot> history);
}
