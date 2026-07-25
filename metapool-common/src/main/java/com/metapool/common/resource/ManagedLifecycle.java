package com.metapool.common.resource;

import com.metapool.common.stats.HealthStatus;

import java.time.Duration;

/**
 * 统一生命周期契约。
 *
 * <p>这是所有被治理资源<em>真正共有</em>的能力——都能启动、优雅关闭、报告健康。它是 MetaPool 2.0
 * 抽象体系的支点：把功能性 API（借出/归还、限流、加锁）从统一契约中剥离，只保留生命周期，
 * 从而根除 1.0 中「用一个 acquire/release 接口硬套所有资源」导致的里氏替换破坏。
 *
 * <h3>线程安全</h3>
 * <ul>
 *   <li>{@link #start()} / {@link #stop(Duration)} 由调用方（控制面）保证单线程调用。</li>
 *   <li>{@link #health()} 必须支持多线程并发调用，返回瞬时快照。</li>
 * </ul>
 *
 * @since 2.0.0
 */
public interface ManagedLifecycle {

    /**
     * 启动资源（建池 / 建 bucket / 预热等）。
     *
     * <p><b>幂等：</b>已启动时应无操作。启动失败抛
     * {@link com.metapool.common.exception.MetaPoolException}。
     */
    void start();

    /**
     * 优雅停机。
     *
     * <p>在 {@code graceful} 期内等待在用资源归还（drain）；超时后强制释放底层物理资源。
     * 停机后资源不再接受使用请求。<b>幂等：</b>已停机时应无操作。
     *
     * <p>本方法修正了 1.0 {@code destroy()} 直接强杀在用资源的缺陷。
     *
     * @param graceful 优雅等待上限；{@link Duration#ZERO} 表示立即释放
     */
    void stop(Duration graceful);

    /**
     * 当前健康快照。
     *
     * <p>多线程安全，返回调用时刻的瞬时值。
     *
     * @return 健康状态，绝不为 null
     */
    HealthStatus health();
}
