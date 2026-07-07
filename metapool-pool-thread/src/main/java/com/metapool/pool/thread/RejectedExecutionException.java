package com.metapool.pool.thread;

/**
 * 任务被拒绝时抛出的运行时异常。
 *
 * @since 0.1.0
 */
public class RejectedExecutionException extends RuntimeException {

    public RejectedExecutionException(String message) {
        super(message);
    }
}
