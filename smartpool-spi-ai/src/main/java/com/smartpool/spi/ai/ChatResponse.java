package com.smartpool.spi.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AI 运维问答响应，包含对用户问题的回答及相关参考信息。
 *
 * <p>由 {@link AiChatService#chat} 返回。不可变对象，通过 {@link Builder} 构造。
 *
 * @since 0.1.0
 */
public final class ChatResponse {

    /** AI 生成的回答内容 */
    private final String answer;

    /**
     * 回答置信度，取值范围 [0.0, 1.0]。
     * 1.0 表示完全确定，0.0 表示纯猜测。
     */
    private final double confidence;

    /** 参考信息列表（如相关文档链接、日志片段等） */
    private final List<String> references;

    private ChatResponse(Builder builder) {
        this.answer = builder.answer;
        this.confidence = builder.confidence;
        this.references = Collections.unmodifiableList(new ArrayList<>(builder.references));
    }

    public String getAnswer() {
        return answer;
    }

    public double getConfidence() {
        return confidence;
    }

    public List<String> getReferences() {
        return references;
    }

    @Override
    public String toString() {
        return "ChatResponse{answer='" + answer
                + "', confidence=" + confidence
                + ", references=" + references + '}';
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String answer;
        private double confidence = 0.5;
        private final List<String> references = new ArrayList<>();

        private Builder() {
        }

        public Builder answer(String answer) {
            this.answer = answer;
            return this;
        }

        public Builder confidence(double confidence) {
            if (confidence < 0.0 || confidence > 1.0) {
                throw new IllegalArgumentException("confidence must be in [0.0, 1.0], got: " + confidence);
            }
            this.confidence = confidence;
            return this;
        }

        public Builder addReference(String reference) {
            this.references.add(reference);
            return this;
        }

        public ChatResponse build() {
            if (answer == null || answer.isEmpty()) {
                throw new IllegalArgumentException("answer must not be null or empty");
            }
            if (confidence < 0.0 || confidence > 1.0) {
                throw new IllegalArgumentException("confidence must be in [0.0, 1.0]");
            }
            return new ChatResponse(this);
        }
    }
}
