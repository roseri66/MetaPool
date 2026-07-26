package com.metapool.common.exception;

/**
 * 统一错误码。所有 {@link MetaPoolException} 均携带一个错误码。
 *
 * <p>格式：{@code {域}-{三位数字}}，域编码 {@code POOL} 表示资源治理通用。
 *
 * @since 2.0.0
 */
public enum ErrorCode {

    /** 内部错误（未归类）。 */
    INTERNAL("POOL-000", "MetaPool internal error"),

    /** 池类资源耗尽：无可用资源且已达上限。 */
    POOL_EXHAUSTED("POOL-001", "Pool exhausted, no available resource"),

    /** 配置非法：启动期校验失败（fail-fast）。 */
    CONFIG_INVALID("POOL-002", "Invalid configuration"),

    /** 按名查找资源失败。 */
    RESOURCE_NOT_FOUND("POOL-003", "Managed resource not found"),

    /** 动态调参被拒绝（参数不在白名单或校验失败）。 */
    TUNE_REJECTED("POOL-004", "Tune request rejected"),

    /** 资源正在优雅停机，不再接受新的使用请求。 */
    SHUTTING_DOWN("POOL-005", "Resource is shutting down");

    private final String code;
    private final String defaultMessage;

    ErrorCode(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    /** 机读错误码，如 {@code "POOL-001"}。 */
    public String code() {
        return code;
    }

    /** 默认人读描述。 */
    public String defaultMessage() {
        return defaultMessage;
    }
}
