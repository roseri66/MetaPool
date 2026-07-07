package com.metapool.pool.lock;

import com.metapool.common.lifecycle.PoolStats;
import com.metapool.common.lifecycle.ResourceLifecycle;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于 Redis 的可重入分布式锁，实现 {@link ResourceLifecycle} 接口。
 *
 * <h3>核心特性</h3>
 * <ul>
 *   <li><b>可重入</b> — 同一线程可重复加锁，Redis Hash 存储持有者标识 + 重入计数</li>
 *   <li><b>自动续期</b> — 后台守护线程周期性续期锁 TTL，防止业务未完成锁过期</li>
 *   <li><b>防死锁</b> — 续期线程异常时靠 Redis TTL 兜底，到期自动释放</li>
 *   <li><b>重试机制</b> — 获取失败后支持配置次数的重试 + 间隔</li>
 *   <li><b>Redis 故障降级</b> — Redis 不可用时降级为本地 {@link java.util.concurrent.locks.ReentrantLock}
 *       + WARN 日志</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <p>{@link #tryLock()} / {@link #tryLock(long, TimeUnit)} / {@link #unlock()} / {@link #stats()}
 * 支持多线程并发调用。{@link #init()} 和 {@link #destroy()} 由调用方保证单线程。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * RedisClient redisClient = RedisClient.create("redis://localhost:6379");
 * SmartReentrantLock lock = new SmartReentrantLock(new LockConfig(), redisClient, "order:12345");
 * lock.init();
 *
 * if (lock.tryLock(5, TimeUnit.SECONDS)) {
 *     try {
 *         // 临界区代码
 *     } finally {
 *         lock.unlock();
 *     }
 * }
 * lock.destroy();
 * }</pre>
 *
 * <h3>Lua 脚本（原子操作）</h3>
 * <p>锁在 Redis 中以 Hash 结构存储：{@code HSET lockKey holderId reentryCount}，
 * 加锁/解锁/续期均通过 Lua 脚本保证原子性。
 *
 * @see LockConfig
 * @since 0.1.0
 */
public class SmartReentrantLock implements ResourceLifecycle<Boolean> {

    // ==================== Lua 脚本 ====================

    /**
     * 获取锁 Lua 脚本。
     * <pre>
     * KEYS[1] = lockKey
     * ARGV[1] = holderId（持有者标识）
     * ARGV[2] = ttlMillis（锁 TTL 毫秒）
     *
     * 返回 1 = 获取成功，0 = 锁被其他持有者占用
     * </pre>
     */
    private static final String ACQUIRE_SCRIPT =
            "if redis.call('exists', KEYS[1]) == 0 then " +
            "  redis.call('hincrby', KEYS[1], ARGV[1], 1) " +
            "  redis.call('pexpire', KEYS[1], ARGV[2]) " +
            "  return 1 " +
            "elseif redis.call('hexists', KEYS[1], ARGV[1]) == 1 then " +
            "  redis.call('hincrby', KEYS[1], ARGV[1], 1) " +
            "  redis.call('pexpire', KEYS[1], ARGV[2]) " +
            "  return 1 " +
            "else " +
            "  return 0 " +
            "end";

    /**
     * 释放锁 Lua 脚本。
     * <pre>
     * KEYS[1] = lockKey
     * ARGV[1] = holderId
     * ARGV[2] = ttlMillis（仅在重入计数 > 0 时刷新 TTL）
     *
     * 返回 1 = 释放成功（计数 > 0 或 key 被删除），0 = 锁不属于当前持有者
     * </pre>
     */
    private static final String RELEASE_SCRIPT =
            "if redis.call('hexists', KEYS[1], ARGV[1]) == 0 then " +
            "  return 0 " +
            "end " +
            "local count = redis.call('hincrby', KEYS[1], ARGV[1], -1) " +
            "if count > 0 then " +
            "  redis.call('pexpire', KEYS[1], ARGV[2]) " +
            "  return 1 " +
            "else " +
            "  redis.call('del', KEYS[1]) " +
            "  return 1 " +
            "end";

    /**
     * 续期 Lua 脚本。
     * <pre>
     * KEYS[1] = lockKey
     * ARGV[1] = holderId
     * ARGV[2] = ttlMillis
     *
     * 返回 1 = 续期成功，0 = 锁不存在或不属于当前持有者
     * </pre>
     */
    private static final String RENEW_SCRIPT =
            "if redis.call('hexists', KEYS[1], ARGV[1]) == 1 then " +
            "  redis.call('pexpire', KEYS[1], ARGV[2]) " +
            "  return 1 " +
            "else " +
            "  return 0 " +
            "end";

    // ==================== 实例字段 ====================

    private final LockConfig config;
    private final String lockKey;

    /** 锁实例唯一标识（用于 holder ID 前缀，区分不同 JVM 的相同线程 ID） */
    private final String instanceId;

    /** ThreadLocal: 当前线程的持有者标识（hash field 名），null 表示未持有 */
    private final ThreadLocal<String> holderIdLocal = new ThreadLocal<>();

    /** ThreadLocal: 当前线程持有的重入计数（本地缓存，避免每次查 Redis） */
    private final ThreadLocal<Integer> holdCountLocal = ThreadLocal.withInitial(() -> 0);

    // ==================== Redis 连接 ====================

    /** Redis 客户端（外部传入，不负责关闭） */
    private final RedisClient redisClient;

    /** 锁模块自行管理的 Redis 连接 */
    private StatefulRedisConnection<String, String> connection;

    /** 同步命令接口 */
    private RedisCommands<String, String> redisCommands;

    // ==================== 降级锁 ====================

    /** 本地降级锁（Redis 不可用时使用）。forceUnlock 时会替换为新锁以强制释放 */
    private java.util.concurrent.locks.ReentrantLock localFallbackLock =
            new java.util.concurrent.locks.ReentrantLock();

    /** 是否处于降级模式 */
    private volatile boolean fallbackMode = false;

    /** 降级模式下的持有者线程引用（仅 fallback 模式使用） */
    private volatile Thread fallbackHolder;

    /** 降级模式下的重入计数 */
    private volatile int fallbackHoldCount = 0;

    // ==================== 续期调度 ====================

    /** 续期后台调度器（守护线程） */
    private ScheduledExecutorService renewalScheduler;

    /** 当前续期任务的 Future，null 表示无续期进行中 */
    private volatile ScheduledFuture<?> renewalFuture;

    // ==================== 运行状态 ====================

    /** 是否已初始化 */
    private volatile boolean initialized;

    /** 是否正常运行 */
    private volatile boolean running;

    // ==================== 指标 ====================

    /** 加锁成功总次数 */
    private final AtomicLong acquireCount = new AtomicLong(0);

    /** 加锁超时总次数 */
    private final AtomicLong timeoutCount = new AtomicLong(0);

    /** 当前等待获取锁的线程数（估计值） */
    private final AtomicInteger contentionCount = new AtomicInteger(0);

    /** 最近一次加锁成功的开始时间戳（毫秒），0 表示无持有 */
    private final AtomicLong holdStartTimeMs = new AtomicLong(0);

    // ==================== 构造器 ====================

    /**
     * 创建分布式锁实例。
     *
     * @param config      锁配置
     * @param redisClient Redis 客户端（调用方负责创建和关闭）
     * @param lockKey     锁在 Redis 中的 key 名称
     * @throws IllegalArgumentException 如果任一参数为 null 或 lockKey 为空
     */
    public SmartReentrantLock(LockConfig config, RedisClient redisClient, String lockKey) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        if (redisClient == null) {
            throw new IllegalArgumentException("redisClient must not be null");
        }
        if (lockKey == null || lockKey.trim().isEmpty()) {
            throw new IllegalArgumentException("lockKey must not be null or empty");
        }

        this.config = config;
        this.redisClient = redisClient;
        this.lockKey = lockKey.trim();
        this.instanceId = UUID.randomUUID().toString().substring(0, 8);
    }

    // ==================== ResourceLifecycle 实现 ====================

    /**
     * 初始化锁，建立 Redis 连接。
     *
     * <p>调用方保证单线程调用。幂等：已初始化时直接返回。
     */
    @Override
    public void init() {
        if (initialized) {
            return;
        }

        try {
            this.connection = redisClient.connect();
            this.redisCommands = connection.sync();
            // 验证连接可用
            redisCommands.ping();
        } catch (RuntimeException e) {
            // Redis 连接失败，进入降级模式
            activateFallback("init: Redis connection failed, entering fallback mode");
        }

        this.initialized = true;
        this.running = true;
    }

    /**
     * 获取锁（阻塞直到获取成功）。
     *
     * <p>多线程安全。内部使用自旋重试，每次失败后等待 {@link LockConfig#getRetryIntervalMillis()} 毫秒。
     *
     * @return true 表示获取成功
     * @throws InterruptedException 如果等待被中断
     */
    @Override
    public Boolean acquire() throws InterruptedException {
        while (running) {
            if (tryLockOnce()) {
                return true;
            }
            Thread.sleep(config.getRetryIntervalMillis());
        }
        return false;
    }

    /**
     * 获取锁（带超时 + 重试）。
     *
     * <p>在超时时间内多次重试获取锁，每次失败后等待 {@link LockConfig#getRetryIntervalMillis()} 毫秒。
     * 重试次数受 {@link LockConfig#getMaxRetryCount()} 限制。
     *
     * @param timeout 最大等待时间
     * @param unit    时间单位
     * @return true 表示获取成功，超时返回 false
     * @throws InterruptedException 如果等待被中断
     */
    @Override
    public Boolean acquire(long timeout, TimeUnit unit) throws InterruptedException {
        return tryLock(timeout, unit);
    }

    /**
     * 释放锁。
     *
     * <p>多线程安全。仅当前持有锁的线程可以成功释放。
     *
     * @param resource 忽略（锁不需要 acquire 的返回值）
     */
    @Override
    public void release(Boolean resource) {
        unlock();
    }

    /**
     * 销毁锁，释放所有资源。
     *
     * <p>调用方保证单线程调用。强制释放当前持有的锁，停止续期，关闭连接和调度器。
     */
    @Override
    public void destroy() {
        running = false;

        // 停止续期
        cancelRenewal();

        // 强制释放本地锁
        if (fallbackMode && fallbackHolder != null) {
            while (fallbackHoldCount > 0) {
                localFallbackLock.unlock();
                fallbackHoldCount--;
            }
            fallbackHolder = null;
        }

        // 关闭续期调度器
        shutdownRenewalScheduler();

        // 关闭 Redis 连接
        if (connection != null && connection.isOpen()) {
            connection.close();
        }

        initialized = false;
    }

    /**
     * 获取运行时统计快照。
     *
     * @return 当前锁的统计信息
     */
    @Override
    public PoolStats stats() {
        long active = (holdStartTimeMs.get() > 0) ? 1 : 0;
        long totalAcq = acquireCount.get();
        long totalRel = totalAcq - active;
        return PoolStats.builder()
                .activeCount((int) active)
                .idleCount((int) (1 - active))
                .pendingCount(contentionCount.get())
                .totalAcquired(totalAcq)
                .totalReleased(totalRel)
                .leakDetected(fallbackMode ? 1 : 0)
                .build();
    }

    // ==================== 公开 API ====================

    /**
     * 尝试获取锁（非阻塞，单次尝试）。
     *
     * <p>多线程安全。可重入：同一线程可重复获取。
     *
     * @return true 获取成功，false 锁被其他线程/进程持有
     */
    public boolean tryLock() {
        if (!running) {
            return false;
        }
        return tryLockOnce();
    }

    /**
     * 尝试获取锁（阻塞等待，带超时和重试）。
     *
     * <p>多线程安全。在超时时间内重试最多 {@link LockConfig#getMaxRetryCount()} 次，
     * 每次失败后等待 {@link LockConfig#getRetryIntervalMillis()} 毫秒。
     *
     * @param timeout 最大等待时间
     * @param unit    时间单位
     * @return true 获取成功，false 超时
     * @throws InterruptedException 等待被中断
     */
    public boolean tryLock(long timeout, TimeUnit unit) throws InterruptedException {
        if (!running) {
            return false;
        }

        long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
        int retryCount = 0;

        while (true) {
            // 1. 尝试获取
            contentionCount.incrementAndGet();
            try {
                if (tryLockOnce()) {
                    return true;
                }
            } finally {
                contentionCount.decrementAndGet();
            }

            // 2. 检查重试次数
            retryCount++;
            if (retryCount > config.getMaxRetryCount()) {
                timeoutCount.incrementAndGet();
                return false;
            }

            // 3. 检查剩余时间
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                timeoutCount.incrementAndGet();
                return false;
            }

            // 4. 等待后重试
            long waitMillis = Math.min(config.getRetryIntervalMillis(),
                    remainingNanos / 1_000_000);
            if (waitMillis <= 0) {
                // 剩余时间不足 1ms，直接最后尝试一次
                continue;
            }
            TimeUnit.MILLISECONDS.sleep(waitMillis);
        }
    }

    /**
     * 释放锁。
     *
     * <p>多线程安全。仅当前持有锁的线程可以成功释放。
     * 可重入：每次调用减少一次持有计数，计数归零时完全释放。
     */
    public void unlock() {
        String holderId = holderIdLocal.get();
        if (holderId == null) {
            // 当前线程未持有锁
            return;
        }

        int count = holdCountLocal.get();
        if (count <= 1) {
            // 最后一次释放
            cancelRenewal();
            holdStartTimeMs.set(0);

            if (fallbackMode) {
                fallbackUnlock();
            } else {
                try {
                    long ttlMillis = config.getDefaultTtlSeconds() * 1000;
                    redisCommands.eval(RELEASE_SCRIPT, ScriptOutputType.INTEGER,
                            new String[]{lockKey}, holderId, String.valueOf(ttlMillis));
                } catch (RuntimeException e) {
                    // Redis 异常：激活降级，然后本地释放
                    activateFallback("unlock: Redis error, activating fallback. key=" + lockKey);
                    fallbackUnlock();
                }
            }

            holderIdLocal.remove();
            holdCountLocal.set(0);
        } else {
            // 减少重入计数
            holdCountLocal.set(count - 1);

            if (fallbackMode) {
                fallbackHoldCount--;
                localFallbackLock.unlock();
            } else {
                try {
                    long ttlMillis = config.getDefaultTtlSeconds() * 1000;
                    redisCommands.eval(RELEASE_SCRIPT, ScriptOutputType.INTEGER,
                            new String[]{lockKey}, holderId, String.valueOf(ttlMillis));
                } catch (RuntimeException e) {
                    // 降级处理已在计数归零的路径中覆盖，
                    // 此处仅记日志，下次 unlock（最后一次）会触发
                }
            }
        }
    }

    /**
     * 检查当前线程是否持有锁。
     *
     * @return true 当前线程持有锁
     */
    public boolean isLocked() {
        return holderIdLocal.get() != null;
    }

    /**
     * 强制释放锁（管理员操作，无论持有者是谁）。
     *
     * <p>警告：此操作会破坏分布式锁的互斥语义，仅用于死锁恢复或运维操作。
     */
    public void forceUnlock() {
        cancelRenewal();
        holdStartTimeMs.set(0);

        if (fallbackMode) {
            if (localFallbackLock.isHeldByCurrentThread()) {
                while (localFallbackLock.isLocked()) {
                    localFallbackLock.unlock();
                }
            } else {
                // 锁被其他线程持有，无法直接 unlock。
                // 替换为新锁实例以强制释放（原锁随旧对象被 GC）。
                localFallbackLock = new java.util.concurrent.locks.ReentrantLock();
            }
            fallbackHolder = null;
            fallbackHoldCount = 0;
        } else {
            try {
                redisCommands.del(lockKey);
            } catch (RuntimeException e) {
                activateFallback("forceUnlock: Redis error, activating fallback. key=" + lockKey);
                if (localFallbackLock.isHeldByCurrentThread()) {
                    while (localFallbackLock.isLocked()) {
                        localFallbackLock.unlock();
                    }
                } else {
                    localFallbackLock = new java.util.concurrent.locks.ReentrantLock();
                }
                fallbackHolder = null;
                fallbackHoldCount = 0;
            }
        }

        holderIdLocal.remove();
        holdCountLocal.set(0);
    }

    /**
     * 是否处于降级模式（Redis 不可用，使用本地锁）。
     *
     * @return true 当前使用本地 ReentrantLock
     */
    public boolean isInFallbackMode() {
        return fallbackMode;
    }

    /**
     * 获取锁的持有时间（毫秒）。
     *
     * @return 持有毫秒数，-1 表示未持有锁
     */
    public long getHoldDurationMillis() {
        long startMs = holdStartTimeMs.get();
        if (startMs <= 0) {
            return -1;
        }
        return System.currentTimeMillis() - startMs;
    }

    // ==================== 内部方法 ====================

    /**
     * 单次尝试获取锁（非阻塞）。
     */
    private boolean tryLockOnce() {
        // 检查是否已持有（可重入）
        String existingHolder = holderIdLocal.get();
        if (existingHolder != null) {
            int count = holdCountLocal.get();
            holdCountLocal.set(count + 1);

            if (fallbackMode) {
                localFallbackLock.lock();
                fallbackHoldCount++;
            }
            // Redis 模式下重入已在首次获取时处理，不需要再访问 Redis
            acquireCount.incrementAndGet();
            return true;
        }

        // 首次获取
        String holderId = buildHolderId();

        if (fallbackMode) {
            return fallbackTryLock(holderId);
        }

        try {
            long ttlMillis = config.getDefaultTtlSeconds() * 1000;
            Long result = redisCommands.eval(ACQUIRE_SCRIPT, ScriptOutputType.INTEGER,
                    new String[]{lockKey}, holderId, String.valueOf(ttlMillis));

            if (result != null && result == 1) {
                // 加锁成功
                onLockAcquired(holderId, 1);
                return true;
            }
            // result == 0: 锁被其他持有者占用（正常情况，不降级）
            return false;
        } catch (RuntimeException e) {
            // R-CON-04: Redis 通信异常时降级为本地锁，而非在 SET NX 返回 false 时降级
            activateFallback("tryLockOnce: Redis communication error, activating fallback. key=" + lockKey);
            return fallbackTryLock(holderId);
        }
    }

    /**
     * 降级模式下的获取锁。
     */
    private boolean fallbackTryLock(String holderId) {
        boolean acquired = localFallbackLock.tryLock();
        if (acquired) {
            fallbackHolder = Thread.currentThread();
            fallbackHoldCount = 1;
            onLockAcquired(holderId, 1);
            return true;
        }
        return false;
    }

    /**
     * 降级模式下的释放锁。
     */
    private void fallbackUnlock() {
        fallbackHoldCount = 0;
        fallbackHolder = null;
        while (localFallbackLock.isLocked() && localFallbackLock.isHeldByCurrentThread()) {
            localFallbackLock.unlock();
        }
    }

    /**
     * 加锁成功后调用：设置 ThreadLocal、启动续期、更新指标。
     */
    private void onLockAcquired(String holderId, int count) {
        holderIdLocal.set(holderId);
        holdCountLocal.set(count);
        acquireCount.incrementAndGet();
        holdStartTimeMs.set(System.currentTimeMillis());

        // 启动自动续期（非降级模式）
        if (!fallbackMode) {
            startRenewal(holderId);
        }
    }

    /**
     * 构建当前线程的持有者标识。
     */
    private String buildHolderId() {
        return instanceId + ":" + Thread.currentThread().getId();
    }

    // ==================== 自动续期 ====================

    /**
     * 启动自动续期后台任务。
     */
    private void startRenewal(String holderId) {
        if (renewalScheduler == null || renewalScheduler.isShutdown()) {
            renewalScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "metapool-lock-renewal-" + lockKey);
                t.setDaemon(true);
                return t;
            });
        }

        long intervalSeconds = config.getRenewalIntervalSeconds();
        renewalFuture = renewalScheduler.scheduleWithFixedDelay(
                () -> renewOnce(holderId),
                intervalSeconds,
                intervalSeconds,
                TimeUnit.SECONDS);
    }

    /**
     * 单次续期操作。
     */
    private void renewOnce(String holderId) {
        if (fallbackMode) {
            return; // 降级模式不需要 Redis 续期
        }

        try {
            long ttlMillis = config.getDefaultTtlSeconds() * 1000;
            Long result = redisCommands.eval(RENEW_SCRIPT, ScriptOutputType.INTEGER,
                    new String[]{lockKey}, holderId, String.valueOf(ttlMillis));

            if (result == null || result != 1) {
                // 续期失败：锁可能已过期或被其他进程强制释放
                // 此时不降级（可能是正常的锁过期），仅停止续期
                cancelRenewal();
            }
        } catch (RuntimeException e) {
            // R-CON-04: Redis 通信异常时激活降级
            // 注意：此时锁仍在本地持有，降级为本地锁保证业务连续性
            activateFallback("renewOnce: Redis communication error during renewal, activating fallback. key="
                    + lockKey + " holder=" + holderId);
        }
    }

    /**
     * 取消续期任务。
     */
    private void cancelRenewal() {
        ScheduledFuture<?> future = renewalFuture;
        if (future != null && !future.isCancelled()) {
            future.cancel(false);
        }
        renewalFuture = null;
    }

    /**
     * 关闭续期调度器。
     */
    private void shutdownRenewalScheduler() {
        if (renewalScheduler != null && !renewalScheduler.isShutdown()) {
            renewalScheduler.shutdownNow();
            try {
                renewalScheduler.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ==================== 降级管理 ====================

    /**
     * 激活降级模式。
     *
     * <p>R-CON-04: 仅在 Redis 通信异常（RuntimeException）时调用，
     * 不由 SET NX 返回 false（锁被占用）触发。
     */
    private void activateFallback(String reason) {
        if (fallbackMode) {
            return; // 已在降级模式
        }
        fallbackMode = true;
        cancelRenewal();

        // 降级日志：生产环境 WARN 级别，提醒运维检查 Redis
        System.err.println("[WARN] SmartReentrantLock: " + reason + " — using local ReentrantLock fallback");
    }
}
