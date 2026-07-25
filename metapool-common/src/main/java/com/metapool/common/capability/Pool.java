package com.metapool.common.capability;

import com.metapool.common.exception.PoolExhaustedException;
import com.metapool.common.stats.PoolStats;

import java.time.Duration;

/**
 * 池化能力 —— <b>仅「真·池」类资源实现</b>（数据库连接池、Redis 连接池、对象池）。
 *
 * <p>限流、锁、线程池<b>不实现</b>本接口：它们没有「借出一个对象、用完归还」的语义。把它们排除在外，
 * 正是对 1.0 「令牌桶 / 锁被硬套 acquire/release」错误的纠正。
 *
 * <h3>线程安全</h3>
 * <p>{@link #borrow()} / {@link #borrow(Duration)} / {@link #release(Object)} / {@link #poolStats()}
 * 必须支持多线程并发调用。
 *
 * @param <T> 池中管理的资源类型（如 {@link java.sql.Connection}）
 * @since 2.0.0
 */
public interface Pool<T> {

    /**
     * 借出一个资源，阻塞等待直到可用或线程被中断。
     *
     * @return 可用资源，绝不为 null
     */
    T borrow() throws InterruptedException;

    /**
     * 借出一个资源（带超时）。
     *
     * @param timeout 最大等待时间
     * @return 可用资源，绝不为 null
     * @throws PoolExhaustedException 超时仍无可用资源
     */
    T borrow(Duration timeout) throws InterruptedException, PoolExhaustedException;

    /**
     * 归还资源。归还非本池或已归还的资源时静默忽略（记 WARN 日志）。
     *
     * @param resource 待归还资源
     */
    void release(T resource);

    /** 当前池统计快照。 */
    PoolStats poolStats();
}
