package com.metapool.common;

import com.metapool.common.capability.DistributedLock;
import com.metapool.common.capability.LockHandle;
import com.metapool.common.capability.ManagedExecutor;
import com.metapool.common.capability.Pool;
import com.metapool.common.capability.RateLimiter;
import com.metapool.common.stats.ExecutorStats;
import com.metapool.common.stats.LockStats;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 能力隔离的结构性守护 —— 守住 2.1 设计里最容易被"好心"改回去的两条决策。
 *
 * <p>这些断言看似显然，但它们对应的正是台账 P-07（1.0 用一个接口硬套所有资源导致 LSP 破坏）
 * 和 2.1 设计中「不能有第二个停机入口」的结论。写成测试，让违反变成红灯而不是 code review 的运气。
 */
class CapabilityIsolationTest {

    /**
     * P-07 守护：非池资源一律不得实现 {@link Pool}。
     *
     * <p>1.0 的错误正是让线程池实现 {@code Pool} 语义的 {@code acquire()} 后抛
     * {@code UnsupportedOperationException}，以及把锁建模成「借出一个 Boolean」。
     */
    @Test
    void nonPoolCapabilities_mustNotExtendPool() {
        assertFalse(Pool.class.isAssignableFrom(ManagedExecutor.class),
                "线程池不是池：ManagedExecutor 不得继承 Pool（P-07）");
        assertFalse(Pool.class.isAssignableFrom(DistributedLock.class),
                "锁不是池：DistributedLock 不得继承 Pool（P-07）");
        assertFalse(Pool.class.isAssignableFrom(RateLimiter.class),
                "限流不是池：RateLimiter 不得继承 Pool（2.0 已确立）");
    }

    /**
     * ManagedExecutor 必须继承 {@link Executor}（零成本互操作），
     * 但<b>绝不能</b>继承 {@link ExecutorService} —— 后者会把 shutdown() 暴露给业务代码，
     * 开出第二个停机入口，使控制面状态与实际不符。
     */
    @Test
    void managedExecutor_extendsExecutor_butNeverExecutorService() {
        assertTrue(Executor.class.isAssignableFrom(ManagedExecutor.class),
                "应继承 Executor，才能直接传给 CompletableFuture / @Async");
        assertFalse(ExecutorService.class.isAssignableFrom(ManagedExecutor.class),
                "绝不能继承 ExecutorService：shutdown() 会成为绕过控制面的第二个停机入口");
    }

    /** 释放锁的唯一途径是凭证，且凭证必须能用于 try-with-resources。 */
    @Test
    void lockHandle_isAutoCloseable_andLockHasNoUnlockByKey() {
        assertTrue(AutoCloseable.class.isAssignableFrom(LockHandle.class),
                "LockHandle 应可用于 try-with-resources");
        boolean hasUnlockByKey = java.util.Arrays.stream(DistributedLock.class.getMethods())
                .anyMatch(m -> m.getName().equals("unlock"));
        assertFalse(hasUnlockByKey,
                "不得提供 unlock(key)：它无法判断调用方是否持有者，会导致误解他人的锁");
    }

    @Test
    void newStatsRecords_carryTheirValues() {
        LockStats lock = new LockStats(2, 100, 7, 1);
        assertEquals(2, lock.heldByThisProcess());
        assertEquals(7, lock.totalTimeout());

        ExecutorStats exec = new ExecutorStats(3, 8, 4, 16, 5, Integer.MAX_VALUE, 900, 2);
        assertEquals(3, exec.activeCount());
        assertEquals(Integer.MAX_VALUE, exec.queueRemainingCapacity(), "无界队列的约定值");
        assertEquals(2, exec.rejectedCount());
    }
}
