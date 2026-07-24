package com.metapool.common.resource;

/**
 * 内置资源类型常量。
 *
 * <p>{@link ManagedResource#type()} 返回 String 以便第三方扩展；这里集中声明官方内置类型的字符串常量，
 * 避免各处硬编码字面量。第三方 adapter 可自定义类型字符串，不受此列表限制。
 *
 * @since 2.0.0
 */
public final class ResourceTypes {

    /** 数据库连接池（如 HikariCP）。 */
    public static final String DATASOURCE = "datasource";

    /** 限流器（如 Bucket4j）。 */
    public static final String RATE_LIMITER = "rate-limiter";

    /** Redis 连接池（如 Lettuce）。 */
    public static final String REDIS = "redis";

    /** 通用对象池（如 Commons Pool2）。 */
    public static final String OBJECT = "object";

    /** 线程池（如 JDK ThreadPoolExecutor）。 */
    public static final String EXECUTOR = "executor";

    /** 分布式锁（如 Redisson）。 */
    public static final String LOCK = "lock";

    /** 堆外内存池（如 Netty）。 */
    public static final String MEMORY = "memory";

    private ResourceTypes() {
    }
}
