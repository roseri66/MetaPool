package com.smartpool.common.enums;

/**
 * 统一错误码枚举，所有异常均携带对应错误码。
 *
 * <p>格式：{域}-{三位数字}，域编码 POOL 表示资源池通用。
 *
 * @since 0.1.0
 */
public enum ErrorCode {

    /** 基础异常 */
    POOL_000("POOL-000", "SmartPool internal error"),

    /** 资源耗尽 */
    POOL_001("POOL-001", "Pool exhausted, no available resources"),

    /** 资源泄露 */
    POOL_002("POOL-002", "Resource leak detected"),

    /** 池初始化失败 */
    POOL_003("POOL-003", "Pool initialization failed");

    private final String code;
    private final String message;

    ErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
