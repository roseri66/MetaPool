package com.metapool.common.exception;

/**
 * MetaPool 统一异常基类。所有本框架抛出的运行时异常继承此类，并携带 {@link ErrorCode}。
 *
 * @since 2.0.0
 */
public class MetaPoolException extends RuntimeException {

    private final ErrorCode errorCode;

    public MetaPoolException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public MetaPoolException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /** 关联的错误码，绝不为 null。 */
    public ErrorCode errorCode() {
        return errorCode;
    }
}
