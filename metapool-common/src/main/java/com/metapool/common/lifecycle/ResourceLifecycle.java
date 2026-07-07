package com.metapool.common.lifecycle;

import java.util.concurrent.TimeUnit;

/**
 * 资源池统一生命周期接口。
 *
 * <p>所有资源池（线程池、数据库连接池、Redis 连接池、对象池、内存池、限流器、分布式锁）
 * 必须实现本接口，遵循统一的生命周期契约：初始化 → 获取 → 归还 → 销毁。
 *
 * <p>线程安全约束：
 * <ul>
 *   <li>{@link #init()} 和 {@link #destroy()} 调用方保证单线程调用</li>
 *   <li>{@link #acquire()} / {@link #acquire(long, TimeUnit)} / {@link #release(Object)}
 *       必须支持多线程并发调用</li>
 *   <li>{@link #stats()} 返回调用时刻的瞬时快照，不保证与其他操作的原子性</li>
 * </ul>
 *
 * @param <T> 池中管理的资源类型
 * @since 0.1.0
 */
public interface ResourceLifecycle<T> {

    /**
     * 初始化资源池，预创建最小空闲资源。
     *
     * <p>调用方保证单线程调用。幂等性由实现方决定。初始化失败应抛
     * {@code PoolInitializationException}。
     */
    void init();

    /**
     * 获取资源（阻塞等待直到资源可用）。
     *
     * <p>多线程安全。资源耗尽时阻塞等待，线程被中断时抛 {@code InterruptedException}。
     *
     * @return 可用的资源，不可返回 null
     */
    T acquire() throws InterruptedException;

    /**
     * 获取资源（带超时）。
     *
     * <p>多线程安全。超时后返回 null 或抛 {@code PoolExhaustedException}，由实现方约定。
     *
     * @param timeout 最大等待时间
     * @param unit    时间单位
     * @return 可用的资源，超时时返回 null
     */
    T acquire(long timeout, TimeUnit unit) throws InterruptedException;

    /**
     * 归还资源到池中。
     *
     * <p>多线程安全。归还已销毁池的资源时行为由实现方定义（建议静默丢弃 + WARN 日志）。
     *
     * @param resource 要归还的资源，不可为 null
     */
    void release(T resource);

    /**
     * 销毁资源池，回收全部资源。
     *
     * <p>调用方保证单线程调用。销毁后池不再接受 acquire 请求。优雅关闭时应等待已借出资源归还。
     */
    void destroy();

    /**
     * 获取运行时统计快照。
     *
     * <p>多线程安全。返回调用时刻的瞬时值，各字段之间不保证原子一致性。
     *
     * @return 当前池的统计快照
     */
    PoolStats stats();
}
