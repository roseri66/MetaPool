package com.metapool.common.exception;

/**
 * 资源池初始化失败异常。
 *
 * @since 0.1.0
 */
public class PoolInitializationException extends MetaPoolException {

    public PoolInitializationException(String message) {
        super("POOL-003", message);
    }

    public PoolInitializationException(String message, Throwable cause) {
        super("POOL-003", message, cause);
    }
}
