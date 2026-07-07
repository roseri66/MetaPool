package com.smartpool.pool.thread;

/**
 * 拒绝策略枚举。
 */
public enum RejectedPolicyEnum {

    /** 由调用者线程直接执行 */
    CALLER_RUNS,

    /** 抛 RejectedExecutionException 异常 */
    ABORT,

    /** 静默丢弃 */
    DISCARD
}
