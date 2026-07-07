package com.smartpool.common.exception;

/**
 * 资源泄露异常 — 资源借出超过阈值时间未归还。
 *
 * @since 0.1.0
 */
public class ResourceLeakException extends SmartPoolException {

    public ResourceLeakException(String message) {
        super("POOL-002", message);
    }

    public ResourceLeakException(String message, Throwable cause) {
        super("POOL-002", message, cause);
    }
}
