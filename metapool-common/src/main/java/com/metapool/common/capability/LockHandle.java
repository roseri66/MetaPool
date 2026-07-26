package com.metapool.common.capability;

/**
 * 锁持有凭证 —— 释放锁的<b>唯一</b>途径。
 *
 * <h3>为什么是凭证，而不是 unlock(key)</h3>
 * <p>{@code unlock(String key)} 无法判断调用方是否真的是持有者，会让下面这个分布式锁经典事故变得很容易发生：
 * <pre>
 *   线程 1 拿到锁 → 业务超时、租约到期 → 锁已被线程 2 获得
 *   → 线程 1 走到 finally { unlock(key) } → <b>把线程 2 的锁解了</b>
 * </pre>
 * 凭证携带持有者身份，释放只能通过它，从接口层面杜绝这类误解锁。
 *
 * <h3>推荐用法</h3>
 * <pre>{@code
 * Optional<LockHandle> acquired =
 *         lock.tryLock("order:123", Duration.ofSeconds(3), Duration.ofSeconds(30));
 * if (acquired.isEmpty()) {
 *     return Result.rejected("busy");
 * }
 * try (LockHandle held = acquired.get()) {
 *     // 临界区
 * }   // 自动释放
 * }</pre>
 *
 * @since 2.1.0
 */
public interface LockHandle extends AutoCloseable {

    /** 本凭证对应的锁键，非空。 */
    String key();

    /**
     * 本凭证是否仍然有效（未 {@link #close()} 且租约未到期）。
     *
     * <p><b>诚实说明</b>：这是<em>本地视角</em>的尽力而为判断，<b>不能</b>保证远端此刻仍认为你持有——
     * 网络分区、GC 停顿、时钟漂移都会让判断失真。它适合用于日志与指标，
     * <b>不适合</b>作为临界区正确性的依据。需要强正确性请用带 fencing token 的方案
     * （见 {@link DistributedLock} 关于「不属于本契约的能力」的说明）。
     */
    boolean isHeld();

    /**
     * 释放锁。
     *
     * <p><b>幂等</b>：重复调用、或租约已到期后调用，均静默返回，不抛异常——
     * 因为它最常出现在 {@code finally} / try-with-resources 里，在那里抛异常只会掩盖业务原始异常。
     */
    @Override
    void close();
}
