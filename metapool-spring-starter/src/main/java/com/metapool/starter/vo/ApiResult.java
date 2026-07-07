package com.metapool.starter.vo;

/**
 * 统一 API 返回体，包装所有 HTTP 响应。
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li>泛型包装，兼容所有返回类型</li>
 *   <li>静态工厂方法，禁止直接 new</li>
 *   <li>包含 traceId 用于链路追踪</li>
 *   <li>时间戳精确到毫秒</li>
 * </ul>
 *
 * @param <T> 响应数据类型
 * @since 0.1.0
 */
public final class ApiResult<T> {

    /** 业务状态码（0 = 成功） */
    private final int code;

    /** 提示信息 */
    private final String message;

    /** 响应数据 */
    private final T data;

    /** 链路追踪 ID（MDC 自动获取） */
    private final String traceId;

    /** 毫秒时间戳 */
    private final long timestamp;

    private ApiResult(int code, String message, T data, String traceId) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.traceId = traceId;
        this.timestamp = System.currentTimeMillis();
    }

    // ==================== 静态工厂方法 ====================

    /** 成功（无数据） */
    public static <T> ApiResult<T> success() {
        return new ApiResult<>(0, "success", null, resolveTraceId());
    }

    /** 成功（有数据） */
    public static <T> ApiResult<T> success(T data) {
        return new ApiResult<>(0, "success", data, resolveTraceId());
    }

    /** 成功（自定义消息 + 数据） */
    public static <T> ApiResult<T> success(String message, T data) {
        return new ApiResult<>(0, message, data, resolveTraceId());
    }

    /** 失败（错误码 + 消息） */
    public static <T> ApiResult<T> fail(int code, String message) {
        return new ApiResult<>(code, message, null, resolveTraceId());
    }

    /** 失败（仅消息，默认错误码 -1） */
    public static <T> ApiResult<T> fail(String message) {
        return new ApiResult<>(-1, message, null, resolveTraceId());
    }

    // ==================== Getters ====================

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public T getData() {
        return data;
    }

    public String getTraceId() {
        return traceId;
    }

    public long getTimestamp() {
        return timestamp;
    }

    // ==================== 内部方法 ====================

    private static String resolveTraceId() {
        try {
            String traceId = org.slf4j.MDC.get("traceId");
            return traceId != null ? traceId : "";
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public String toString() {
        return "ApiResult{code=" + code + ", message='" + message + "', traceId='" + traceId + "'}";
    }
}
