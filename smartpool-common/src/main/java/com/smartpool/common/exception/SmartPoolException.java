package com.smartpool.common.exception;

/**
 * 智能资源池统一异常基类。
 *
 * @since 0.1.0
 */
public class SmartPoolException extends RuntimeException {

    private final String errorCode;

    public SmartPoolException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public SmartPoolException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
