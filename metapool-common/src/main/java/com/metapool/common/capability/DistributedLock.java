package com.metapool.common.capability;

import com.metapool.common.stats.LockStats;

import java.time.Duration;
import java.util.Optional;

/**
 * 分布式锁能力 —— <b>仅锁类资源实现</b>（如 Redisson 适配器）。
 *
 * <p>锁<b>不实现</b> {@link Pool}：它不是「借出一个对象、用完归还」，而是「在一段租约内独占一个键」。
 * 1.0 曾把锁建模成 {@code ResourceLifecycle<Boolean>}（即「借出一个 Boolean」），这是被明确否决的做法，
 * 也是本项目最重要的一条反面教材（见 {@code docs/RULES.md} 台账 P-07）。
 *
 * <h3>为什么只有 tryLock，没有 lock()</h3>
 * <p>本接口<b>刻意不提供</b>「无限等待」的重载：分布式环境下无限等待几乎总是故障的温床——调用方以为
 * 最多卡几秒，实际可能卡到线程池耗尽。{@code waitTime} 与 {@code leaseTime} 都是必填参数，
 * <b>强制调用方对「等多久」和「最多持有多久」表态</b>。没有租约的分布式锁，一旦持有者进程崩溃就是永久死锁。
 *
 * <h3>不属于本契约的能力</h3>
 * <p>下列能力<b>不在</b>统一接口中，因为各后端并非都能履约，放进来就会逼出
 * {@code UnsupportedOperationException}：
 * <ul>
 *   <li><b>可重入计数</b> —— Redisson 的 {@code RLock} 可重入，基于 {@code SETNX} 的简单实现不可。
 *       MetaPool <b>不承诺</b>可重入；底层若可重入是它自身的行为，由具体 adapter 文档说明。</li>
 *   <li><b>Fencing token</b> —— ZooKeeper/Curator 能提供单调递增 token，Redis 系普遍不能。
 *       将来如有需要，另立 {@code FencedLock extends DistributedLock} 可选子接口，谁能提供谁实现。</li>
 *   <li><b>公平锁 / 读写锁 / 联锁</b> —— 库特有能力，留给具体 adapter 的原生 API。</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <p>所有方法必须支持多线程并发调用。
 *
 * @since 2.1.0
 */
public interface DistributedLock {

    /**
     * 尝试获取 {@code key} 上的锁。
     *
     * @param key       锁键，非空
     * @param waitTime  最多等待多久；{@link Duration#ZERO} 表示不等待、立即返回
     * @param leaseTime 租约时长——持有超过它锁自动释放，防止持有者崩溃导致死锁；须为正
     * @return 获取成功返回持有凭证；在 {@code waitTime} 内未获得返回 {@link Optional#empty()}
     * @throws InterruptedException 等待期间线程被中断
     * @throws com.metapool.common.exception.MetaPoolException 资源未启动，或后端不可用
     */
    Optional<LockHandle> tryLock(String key, Duration waitTime, Duration leaseTime)
            throws InterruptedException;

    /** 当前锁资源统计快照。 */
    LockStats lockStats();
}
