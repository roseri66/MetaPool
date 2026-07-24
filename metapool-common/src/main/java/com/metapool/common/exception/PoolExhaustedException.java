package com.metapool.common.exception;

/**
 * 池类资源耗尽异常 — 无可用资源且已达上限（borrow 超时时抛出）。
 *
 * <p>仅由实现 {@link com.metapool.common.capability.Pool} 能力的资源抛出。
 *
 * @since 2.0.0
 */
public class PoolExhaustedException extends MetaPoolException {

    public PoolExhaustedException(String message) {
        super(ErrorCode.POOL_EXHAUSTED, message);
    }

    public PoolExhaustedException(String message, Throwable cause) {
        super(ErrorCode.POOL_EXHAUSTED, message, cause);
    }
}
