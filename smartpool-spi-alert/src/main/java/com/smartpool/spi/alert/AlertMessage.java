package com.smartpool.spi.alert;

/**
 * 告警消息模型，包含告警的全部上下文信息。
 *
 * <p>由 {@link AlertChannel#send(AlertMessage)} 接收，实现方根据
 * {@code sourcePool} 和 {@code severity} 路由到对应通知渠道。
 *
 * <p>不可变对象，通过 {@link Builder} 构造。
 *
 * @since 0.1.0
 */
public final class AlertMessage {

    /** 告警严重级别 */
    private final AlertSeverity severity;

    /** 告警标题，简短概括 */
    private final String title;

    /** 告警详细内容，可包含指标数值、阈值、时间范围等上下文 */
    private final String content;

    /** 告警产生时间戳（epoch 毫秒） */
    private final long timestamp;

    /** 告警来源资源池名称，便于定位 */
    private final String sourcePool;

    private AlertMessage(Builder builder) {
        this.severity = builder.severity;
        this.title = builder.title;
        this.content = builder.content;
        this.timestamp = builder.timestamp;
        this.sourcePool = builder.sourcePool;
    }

    public AlertSeverity getSeverity() {
        return severity;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getSourcePool() {
        return sourcePool;
    }

    @Override
    public String toString() {
        return "AlertMessage{severity=" + severity
                + ", title='" + title + '\''
                + ", content='" + content + '\''
                + ", timestamp=" + timestamp
                + ", sourcePool='" + sourcePool + '\''
                + '}';
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private AlertSeverity severity = AlertSeverity.INFO;
        private String title;
        private String content;
        private long timestamp;
        private String sourcePool;

        private Builder() {
        }

        public Builder severity(AlertSeverity severity) {
            this.severity = severity;
            return this;
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder timestamp(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder sourcePool(String sourcePool) {
            this.sourcePool = sourcePool;
            return this;
        }

        public AlertMessage build() {
            if (title == null || title.isEmpty()) {
                throw new IllegalArgumentException("title must not be null or empty");
            }
            if (content == null || content.isEmpty()) {
                throw new IllegalArgumentException("content must not be null or empty");
            }
            if (sourcePool == null || sourcePool.isEmpty()) {
                throw new IllegalArgumentException("sourcePool must not be null or empty");
            }
            return new AlertMessage(this);
        }
    }
}
