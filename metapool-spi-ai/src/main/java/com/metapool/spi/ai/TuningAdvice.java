package com.metapool.spi.ai;

/**
 * AI 生成的参数调优建议。
 *
 * <p>由 {@link AiTuningAdvisor#advise} 返回，针对资源池的某个配置参数
 * 给出调整建议。不可变对象，通过 {@link Builder} 构造。
 *
 * @since 0.1.0
 */
public final class TuningAdvice {

    /** 待调整的参数名称，如 {@code "maxPoolSize"}、{@code "idleTimeoutSeconds"} */
    private final String parameterName;

    /** 当前参数值 */
    private final String currentValue;

    /** 建议调整到的值 */
    private final String suggestedValue;

    /** 调整理由 */
    private final String reason;

    /**
     * 建议置信度，取值范围 [0.0, 1.0]。
     * 1.0 表示完全确定，0.0 表示纯猜测。
     */
    private final double confidence;

    private TuningAdvice(Builder builder) {
        this.parameterName = builder.parameterName;
        this.currentValue = builder.currentValue;
        this.suggestedValue = builder.suggestedValue;
        this.reason = builder.reason;
        this.confidence = builder.confidence;
    }

    public String getParameterName() {
        return parameterName;
    }

    public String getCurrentValue() {
        return currentValue;
    }

    public String getSuggestedValue() {
        return suggestedValue;
    }

    public String getReason() {
        return reason;
    }

    public double getConfidence() {
        return confidence;
    }

    @Override
    public String toString() {
        return "TuningAdvice{parameterName='" + parameterName
                + "', currentValue='" + currentValue
                + "', suggestedValue='" + suggestedValue
                + "', reason='" + reason
                + "', confidence=" + confidence + '}';
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String parameterName;
        private String currentValue;
        private String suggestedValue;
        private String reason;
        private double confidence = 0.5;

        private Builder() {
        }

        public Builder parameterName(String parameterName) {
            this.parameterName = parameterName;
            return this;
        }

        public Builder currentValue(String currentValue) {
            this.currentValue = currentValue;
            return this;
        }

        public Builder suggestedValue(String suggestedValue) {
            this.suggestedValue = suggestedValue;
            return this;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public Builder confidence(double confidence) {
            if (confidence < 0.0 || confidence > 1.0) {
                throw new IllegalArgumentException("confidence must be in [0.0, 1.0], got: " + confidence);
            }
            this.confidence = confidence;
            return this;
        }

        public TuningAdvice build() {
            if (parameterName == null || parameterName.isEmpty()) {
                throw new IllegalArgumentException("parameterName must not be null or empty");
            }
            if (confidence < 0.0 || confidence > 1.0) {
                throw new IllegalArgumentException("confidence must be in [0.0, 1.0]");
            }
            return new TuningAdvice(this);
        }
    }
}
