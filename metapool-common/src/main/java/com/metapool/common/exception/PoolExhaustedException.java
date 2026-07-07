package com.metapool.common.exception;

/**
 * 资源耗尽异常 — 池中无可用资源且等待队列已满或超时。
 *
 * @since 0.1.0
 */
public class PoolExhaustedException extends MetaPoolException {

    public PoolExhaustedException(String message) {
        super("POOL-001", message);
    }

    public PoolExhaustedException(String message, Throwable cause) {
        super("POOL-001", message, cause);
    }
}
