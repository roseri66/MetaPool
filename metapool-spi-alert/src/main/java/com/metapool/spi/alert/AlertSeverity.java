package com.metapool.spi.alert;

/**
 * 告警严重级别，遵循 Prometheus AlertManager 标准。
 *
 * @since 0.1.0
 */
public enum AlertSeverity {

    /** 信息通知，无需立即处理 */
    INFO,

    /** 需要关注，建议尽快处理 */
    WARNING,

    /** 严重告警，需立即介入处理 */
    CRITICAL
}
