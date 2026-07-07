package com.smartpool.spi.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AI 诊断结果，包含对资源池当前状态的诊断结论和操作建议。
 *
 * <p>不可变对象，通过 {@link Builder} 构造。
 *
 * @since 0.1.0
 */
public final class DiagnosisResult {

    /** 诊断严重级别 */
    public enum Severity {
        /** 运行正常，无需关注 */
        NORMAL,
        /** 存在潜在风险，建议关注 */
        WARNING,
        /** 需要立即处理 */
        CRITICAL
    }

    /** 诊断严重级别 */
    private final Severity severity;

    /** 诊断结论标题（简短概括） */
    private final String title;

    /** 诊断详细描述 */
    private final String description;

    /** 操作建议列表 */
    private final List<String> suggestions;

    private DiagnosisResult(Builder builder) {
        this.severity = builder.severity;
        this.title = builder.title;
        this.description = builder.description;
        this.suggestions = Collections.unmodifiableList(new ArrayList<>(builder.suggestions));
    }

    public Severity getSeverity() {
        return severity;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public List<String> getSuggestions() {
        return suggestions;
    }

    @Override
    public String toString() {
        return "DiagnosisResult{severity=" + severity
                + ", title='" + title + '\''
                + ", description='" + description + '\''
                + ", suggestions=" + suggestions + '}';
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Severity severity = Severity.NORMAL;
        private String title;
        private String description;
        private final List<String> suggestions = new ArrayList<>();

        private Builder() {
        }

        public Builder severity(Severity severity) {
            this.severity = severity;
            return this;
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder addSuggestion(String suggestion) {
            this.suggestions.add(suggestion);
            return this;
        }

        public DiagnosisResult build() {
            if (title == null || title.isEmpty()) {
                throw new IllegalArgumentException("title must not be null or empty");
            }
            return new DiagnosisResult(this);
        }
    }
}
