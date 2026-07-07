package com.smartpool.agent.model;

/**
 * 资源池类型枚举，覆盖 SmartPool 全部 7 类资源池。
 *
 * @since 0.1.0
 */
public enum PoolType {

    /** 自研线程池 */
    THREAD("thread"),

    /** 数据库连接池 */
    DB("db"),

    /** Redis 连接池 */
    REDIS("redis"),

    /** 通用对象池 */
    OBJECT("object"),

    /** 内存资源池 */
    MEMORY("memory"),

    /** 令牌桶限流器 */
    RATE_LIMIT("rate-limit"),

    /** 分布式锁 */
    LOCK("lock");

    private final String label;

    PoolType(String label) {
        this.label = label;
    }

    /**
     * 返回短标签，用于指标 key 前缀构造。
     */
    public String getLabel() {
        return label;
    }
}
