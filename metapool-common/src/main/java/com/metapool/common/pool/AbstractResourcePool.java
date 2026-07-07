package com.metapool.common.pool;

import com.metapool.common.enums.PoolStatus;
import com.metapool.common.exception.PoolExhaustedException;
import com.metapool.common.exception.PoolInitializationException;
import com.metapool.common.lifecycle.PoolStats;
import com.metapool.common.lifecycle.ResourceLifecycle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 资源池抽象基类，使用模板方法模式封装资源池通用逻辑。
 *
 * <p>七类资源池均继承此基类，仅需覆写 {@link #createResource()} 和 {@link #destroyResource(Object)}
 * 两个钩子方法即可获得完整的池化管理能力。
 *
 * <h3>内置能力</h3>
 * <ul>
 *   <li>空闲资源定时回收（{@link PoolConfig#getIdleTimeoutSeconds()} 驱动）</li>
 *   <li>资源借出超时检测 → 触发 {@link #onResourceLeaked(Object)} 告警</li>
 *   <li>并发 acquire/release 线程安全（ReentrantLock + Condition）</li>
 *   <li>资源借出前/归还后自动验证（{@link #validateResource(Object)}）</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <p>{@link #acquire()} / {@link #acquire(long, TimeUnit)} / {@link #release(Object)} / {@link #stats()}
 * 支持多线程并发调用。{@link #init()} 和 {@link #destroy()} 由调用方保证单线程。
 *
 * @param <T> 池中管理的资源类型
 * @since 0.1.0
 */
public abstract class AbstractResourcePool<T> implements ResourceLifecycle<T> {

    protected final PoolConfig config;

    /** 池当前状态 */
    protected volatile PoolStatus status = PoolStatus.NEW;

    /** 主锁，保护所有资源操作 */
    protected final ReentrantLock lock = new ReentrantLock();

    /** 等待条件：有资源归还时 signal */
    protected final Condition notEmpty = lock.newCondition();

    /** 空闲资源队列，按归还时间排序（FIFO），key=资源, value=归还时间戳(ms) */
    protected final LinkedHashMap<T, Long> idleMap = new LinkedHashMap<>();

    /** 已借出资源，key=资源, value=借出时间戳(ms) */
    protected final Map<T, Long> activeMap = new HashMap<>();

    /** 累计获取次数 */
    protected final AtomicLong totalAcquired = new AtomicLong(0);

    /** 累计归还次数 */
    protected final AtomicLong totalReleased = new AtomicLong(0);

    /** 当前泄露资源数 */
    protected final AtomicInteger leakDetected = new AtomicInteger(0);

    /** 当前等待 acquire 的线程数（由 lock 保护） */
    private int pendingCount;

    /** 后台定时任务执行器 */
    protected ScheduledExecutorService scheduler;

    /** 扫描间隔，取 idleTimeout 和 leakDetectionThreshold 中较小值的一半，至少 1 秒 */
    private long scanIntervalSeconds;

    // ==================== 构造器 ====================

    protected AbstractResourcePool(PoolConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.config = config;
    }

    // ==================== 模板方法钩子 ====================

    /**
     * 创建新资源。
     *
     * <p>调用时机：池需要扩容或初始化时。子类必须覆写。
     *
     * @return 新创建的资源
     * @throws Exception 创建失败
     */
    protected abstract T createResource() throws Exception;

    /**
     * 销毁资源，释放底层物理资源（如关闭连接、释放内存）。
     *
     * <p>调用时机：资源被淘汰、空闲超时回收、池销毁时。子类必须覆写。
     *
     * @param resource 要销毁的资源
     */
    protected abstract void destroyResource(T resource);

    /**
     * 验证资源有效性。
     *
     * <p>调用时机：资源借出前和归还后。默认返回 true。子类可覆写以实现
     * 连接探测（如 SELECT 1、PING）等。
     *
     * @param resource 待验证的资源
     * @return true 表示资源有效可用
     */
    protected boolean validateResource(T resource) {
        return true;
    }

    /**
     * 资源成功借出后的回调。
     *
     * <p>子类可覆写以实现指标采集（如记录借出时间、更新 Prometheus 指标）。
     * 默认空实现。
     *
     * @param resource 被借出的资源
     */
    protected void onResourceAcquired(T resource) {
    }

    /**
     * 资源归还后的回调。
     *
     * <p>子类可覆写以实现指标采集。默认空实现。
     *
     * @param resource 被归还的资源
     */
    protected void onResourceReleased(T resource) {
    }

    /**
     * 检测到资源泄露时的回调。
     *
     * <p>子类可覆写以实现告警。默认空实现。
     *
     * @param resource 疑似泄露的资源
     */
    protected void onResourceLeaked(T resource) {
    }

    // ==================== ResourceLifecycle 实现 ====================

    @Override
    public void init() {
        if (status == PoolStatus.RUNNING) {
            return;
        }
        if (status != PoolStatus.NEW && status != PoolStatus.DESTROYED) {
            throw new PoolInitializationException("Cannot init pool in status: " + status);
        }

        status = PoolStatus.INITIALIZING;
        lock.lock();
        try {
            int toCreate = Math.min(config.getMinIdle(), config.getMaxPoolSize());
            for (int i = 0; i < toCreate; i++) {
                try {
                    T resource = createResource();
                    idleMap.put(resource, System.currentTimeMillis());
                } catch (Exception e) {
                    // 回滚已创建的资源
                    for (T r : idleMap.keySet()) {
                        destroyResource(r);
                    }
                    idleMap.clear();
                    status = PoolStatus.NEW;
                    throw new PoolInitializationException(
                            "Failed to create initial resource " + i + "/" + toCreate, e);
                }
            }

            startScheduler();
            status = PoolStatus.RUNNING;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public T acquire() throws InterruptedException {
        checkRunning();
        lock.lockInterruptibly();
        try {
            return acquireInternal(-1, null);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public T acquire(long timeout, TimeUnit unit) throws InterruptedException {
        checkRunning();
        long remaining = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            return acquireInternal(remaining, TimeUnit.NANOSECONDS);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void release(T resource) {
        if (resource == null) {
            return;
        }

        lock.lock();
        try {
            if (!activeMap.containsKey(resource)) {
                // 资源不属于此池或已被归还，静默丢弃
                return;
            }

            activeMap.remove(resource);
            totalReleased.incrementAndGet();

            if (status == PoolStatus.DESTROYED || status == PoolStatus.SHUTTING_DOWN) {
                lock.unlock();
                destroyResource(resource);
                lock.lock();
                notEmpty.signal();
                return;
            }

            if (validateResource(resource)) {
                idleMap.put(resource, System.currentTimeMillis());
            } else {
                lock.unlock();
                destroyResource(resource);
                lock.lock();
            }

            notEmpty.signal();
        } finally {
            lock.unlock();
        }

        onResourceReleased(resource);
    }

    @Override
    public void destroy() {
        if (status == PoolStatus.DESTROYED) {
            return;
        }

        status = PoolStatus.SHUTTING_DOWN;
        shutdownScheduler();

        lock.lock();
        try {
            // 销毁所有空闲资源
            for (T resource : idleMap.keySet()) {
                destroyResourceSafely(resource);
            }
            idleMap.clear();

            // 销毁所有仍在借出的资源（强制回收）
            for (T resource : activeMap.keySet()) {
                destroyResourceSafely(resource);
            }
            activeMap.clear();

            status = PoolStatus.DESTROYED;
            // 唤醒所有等待线程
            notEmpty.signalAll();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public PoolStats stats() {
        lock.lock();
        try {
            int poolSize = idleMap.size() + activeMap.size();
            return PoolStats.builder()
                    .activeCount(activeMap.size())
                    .idleCount(idleMap.size())
                    .pendingCount(pendingCount)
                    .totalAcquired(totalAcquired.get())
                    .totalReleased(totalReleased.get())
                    .leakDetected(leakDetected.get())
                    .build();
        } finally {
            lock.unlock();
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 核心获取逻辑，在持有锁时调用。
     *
     * @param timeoutNanos 超时纳秒数，负数表示无限等待
     * @param unit         时间单位
     * @return 获取到的资源
     */
    @SuppressWarnings("DuplicatedCode")
    private T acquireInternal(long timeoutNanos, TimeUnit unit) throws InterruptedException {
        long deadlineNanos = timeoutNanos >= 0 ? System.nanoTime() + timeoutNanos : Long.MAX_VALUE;

        while (true) {
            checkRunning();

            // 1. 尝试从空闲队列获取有效资源
            T idleResource = pollValidIdleResource();
            if (idleResource != null) {
                activeMap.put(idleResource, System.currentTimeMillis());
                totalAcquired.incrementAndGet();
                onResourceAcquired(idleResource);
                return idleResource;
            }

            // 2. 未达上限，尝试创建新资源
            int poolSize = idleMap.size() + activeMap.size();
            if (poolSize < config.getMaxPoolSize()) {
                T newResource = createResourceOutsideLock();
                if (newResource != null) {
                    return newResource;
                }
                // createResource 失败或发生竞争，重试
                continue;
            }

            // 3. 池已满，等待归还
            pendingCount++;
            if (timeoutNanos >= 0) {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    pendingCount--;
                    throw new PoolExhaustedException(
                            "Pool exhausted: poolSize=" + poolSize
                                    + ", maxPoolSize=" + config.getMaxPoolSize()
                                    + ", poolName=" + config.getPoolName());
                }
                boolean signaled = notEmpty.await(remaining, TimeUnit.NANOSECONDS);
                pendingCount--;
                if (!signaled) {
                    throw new PoolExhaustedException(
                            "Acquire timeout: poolSize=" + poolSize
                                    + ", maxPoolSize=" + config.getMaxPoolSize()
                                    + ", poolName=" + config.getPoolName());
                }
            } else {
                notEmpty.await();
                pendingCount--;
            }
        }
    }

    /**
     * 从空闲队列中获取第一个有效资源（FIFO）。
     * 无效资源会被销毁并从队列移除。
     */
    private T pollValidIdleResource() {
        Iterator<Map.Entry<T, Long>> it = idleMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<T, Long> entry = it.next();
            T resource = entry.getKey();
            it.remove();

            if (validateResource(resource)) {
                return resource;
            }

            // 资源无效，销毁
            lock.unlock();
            try {
                destroyResource(resource);
            } finally {
                lock.lock();
            }
        }
        return null;
    }

    /**
     * 在锁外调用 createResource，获得锁后再检查状态、决定是否入池。
     *
     * @return 成功创建的新资源，若发生竞争或状态变更返回 null
     */
    private T createResourceOutsideLock() throws InterruptedException {
        pendingCount++;
        lock.unlock();
        try {
            T resource = createResource();
            lock.lockInterruptibly();
            pendingCount--;

            int poolSize = idleMap.size() + activeMap.size();
            if (poolSize < config.getMaxPoolSize() && status == PoolStatus.RUNNING) {
                activeMap.put(resource, System.currentTimeMillis());
                totalAcquired.incrementAndGet();
                onResourceAcquired(resource);
                return resource;
            }

            // 发生竞争或状态变更，丢弃多余资源
            destroyResource(resource);
            return null;

        } catch (InterruptedException e) {
            lock.lockInterruptibly();
            pendingCount--;
            throw e;
        } catch (RuntimeException e) {
            lock.lockInterruptibly();
            pendingCount--;
            throw e;
        } catch (Exception e) {
            lock.lockInterruptibly();
            pendingCount--;
            throw new PoolInitializationException("Failed to create resource", e);
        }
    }

    // ==================== 调度器管理 ====================

    private void startScheduler() {
        long idleMs = config.getIdleTimeoutSeconds() * 1000;
        long leakMs = config.getLeakDetectionThresholdSeconds() * 1000;
        long intervalMs = Math.max(1000, Math.min(idleMs, leakMs) / 2);
        this.scanIntervalSeconds = intervalMs / 1000;

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, config.getPoolName() + "-scanner");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleWithFixedDelay(
                this::scanIdleExpired,
                scanIntervalSeconds,
                scanIntervalSeconds,
                TimeUnit.SECONDS);

        scheduler.scheduleWithFixedDelay(
                this::scanLeak,
                scanIntervalSeconds,
                scanIntervalSeconds,
                TimeUnit.SECONDS);
    }

    private void shutdownScheduler() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
            try {
                scheduler.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 扫描并回收空闲超时的资源 */
    private void scanIdleExpired() {
        List<T> expired = new ArrayList<>();
        lock.lock();
        try {
            long now = System.currentTimeMillis();
            long idleThresholdMs = config.getIdleTimeoutSeconds() * 1000;
            Iterator<Map.Entry<T, Long>> it = idleMap.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<T, Long> entry = it.next();
                if (now - entry.getValue() >= idleThresholdMs) {
                    expired.add(entry.getKey());
                    it.remove();
                }
            }
        } finally {
            lock.unlock();
        }

        for (T resource : expired) {
            destroyResourceSafely(resource);
        }
    }

    /** 扫描借出超时的资源（泄露检测） */
    private void scanLeak() {
        lock.lock();
        try {
            long now = System.currentTimeMillis();
            long leakThresholdMs = config.getLeakDetectionThresholdSeconds() * 1000;
            for (Map.Entry<T, Long> entry : activeMap.entrySet()) {
                if (now - entry.getValue() >= leakThresholdMs) {
                    leakDetected.incrementAndGet();
                    onResourceLeaked(entry.getKey());
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /** 安全销毁资源，忽略销毁过程中的异常 */
    private void destroyResourceSafely(T resource) {
        try {
            destroyResource(resource);
        } catch (Exception ignored) {
        }
    }

    // ==================== 状态检查 ====================

    private void checkRunning() {
        if (status == PoolStatus.NEW || status == PoolStatus.INITIALIZING) {
            throw new PoolExhaustedException(
                    "Pool not ready: status=" + status + ", poolName=" + config.getPoolName());
        }
        if (status == PoolStatus.PAUSED) {
            throw new PoolExhaustedException("Pool is paused: " + config.getPoolName());
        }
        if (status == PoolStatus.SHUTTING_DOWN || status == PoolStatus.DESTROYED) {
            throw new PoolExhaustedException("Pool is " + status + ": " + config.getPoolName());
        }
    }

    /** JMX MBean 注册预留点。子类可在 init() 后调用。 */
    protected void registerMBean(String name, Object mbean) {
        // V1 预留，后续实现 JMX 注册
    }
}
