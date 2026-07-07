package com.metapool.common.enums;

/**
 * 资源类型枚举，覆盖本系统管理的全部七类资源。
 *
 * @since 0.1.0
 */
public enum ResourceType {

    /** 线程池 */
    THREAD,

    /** 数据库连接池 */
    DB,

    /** Redis 连接池 */
    REDIS,

    /** 通用对象池 */
    OBJECT,

    /** 内存资源池 */
    MEMORY,

    /** 限流器 */
    RATE_LIMIT,

    /** 分布式锁 */
    LOCK
}
