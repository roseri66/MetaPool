package com.metapool.spi.ai;

import com.metapool.common.spi.SPI;

/**
 * AI 运维问答 SPI 接口。
 *
 * <p>接收自然语言问题，返回结构化的排障建议。
 * 实现方可接入大模型（如 DeepSeek、Ollama）来实现智能问答。
 *
 * <h3>线程安全</h3>
 * <p>实现类必须保证 {@link #chat(String)} 方法的线程安全，
 * 允许多个线程并发调用。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * AiChatService chatService = ExtensionLoader.getExtension(AiChatService.class);
 * if (chatService != null) {
 *     ChatResponse response = chatService.chat("为什么线程池队列满了？");
 *     // 展示回答
 * }
 * }</pre>
 *
 * @see ChatResponse
 * @since 0.1.0
 */
@SPI
public interface AiChatService {

    /**
     * 接收自然语言运维问题，返回结构化排障建议。
     *
     * <p>调用方应对返回 {@code null} 做判空兜底——
     * 当 {@link com.metapool.common.spi.ExtensionLoader} 未找到实现且无默认实现时返回 {@code null}。
     *
     * @param question 用户提出的运维问题，自然语言格式，不能为 {@code null} 或空字符串
     * @return 结构化回答；无可用实现时返回 {@code null}
     */
    ChatResponse chat(String question);
}
