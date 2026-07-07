package com.smartpool.spi.ai;

import com.smartpool.common.spi.SPI;

/**
 * AI 诊断服务 SPI 接口。
 *
 * <p>接收资源池指标快照，输出诊断结论和操作建议。
 * 实现方可通过接入大模型（如 DeepSeek、Ollama）或规则引擎来生成诊断结果。
 *
 * <h3>线程安全</h3>
 * <p>实现类必须保证 {@link #diagnose(PoolMetricsSnapshot)} 方法的线程安全，
 * 允许多个线程并发调用。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * AiDiagnosisService service = ExtensionLoader.getExtension(AiDiagnosisService.class);
 * if (service != null) {
 *     DiagnosisResult result = service.diagnose(snapshot);
 *     // 处理诊断结果
 * }
 * }</pre>
 *
 * @see PoolMetricsSnapshot
 * @see DiagnosisResult
 * @since 0.1.0
 */
@SPI
public interface AiDiagnosisService {

    /**
     * 对给定的资源池指标快照进行诊断，输出诊断结论。
     *
     * <p>调用方应对返回 {@code null} 做判空兜底——
     * 当 {@link com.smartpool.common.spi.ExtensionLoader} 未找到实现且无默认实现时返回 {@code null}。
     *
     * @param snapshot 资源池指标快照，不能为 {@code null}
     * @return 诊断结果；无可用实现时返回 {@code null}
     */
    DiagnosisResult diagnose(PoolMetricsSnapshot snapshot);
}
