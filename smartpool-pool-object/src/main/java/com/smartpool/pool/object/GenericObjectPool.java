package com.smartpool.pool.object;

import com.smartpool.common.enums.PoolStatus;
import com.smartpool.common.exception.PoolExhaustedException;
import com.smartpool.common.exception.PoolInitializationException;
import com.smartpool.common.pool.AbstractResourcePool;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

/**
 * 通用对象池，继承 {@link AbstractResourcePool}，通过 {@link ObjectFactory} 泛型工厂
 * 自定义对象的创建、销毁和验证逻辑。
 *
 * <h3>功能</h3>
 * <ul>
 *   <li><b>泛型支持</b> — {@code GenericObjectPool<T>}，通过 {@link ObjectFactory} 解耦</li>
 *   <li><b>FIFO / LIFO</b> — 通过 {@link ObjectPoolConfig#isLifo()} 配置队列策略</li>
 *   <li><b>驱逐策略</b> — 空闲超时 + 数量上限（继承自基类）</li>
 *   <li><b>归还验证</b> — 对象归还时调用 {@link ObjectFactory#validate(Object)}，
 *       无效对象自动销毁</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <p>{@link #acquire()} / {@link #acquire(long, TimeUnit)} / {@link #release(Object)}
 * 支持多线程并发。</p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * ObjectPoolConfig config = new ObjectPoolConfig();
 * config.setMaxPoolSize(32).setMinIdle(2).setLifo(true);
 *
 * ObjectFactory<MyObj> factory = new ObjectFactory<>() { ... };
 * GenericObjectPool<MyObj> pool = new GenericObjectPool<>(config, factory);
 * pool.init();
 *
 * MyObj obj = pool.acquire();
 * try { ... } finally { pool.release(obj); }
 * pool.destroy();
 * }</pre>
 *
 * @param <T> 池中管理的对象类型
 * @since 0.1.0
 */
public class GenericObjectPool<T> extends AbstractResourcePool<T> {

    private final ObjectFactory<T> factory;
    private final boolean lifo;

    /** LIFO 模式下的空闲对象双端队列（仅 lifo=true 时使用） */
    private final Deque<T> lifoDeque;

    /** LIFO 模式下的等待线程计数（基类 pendingCount 为 private） */
    private int lifoPendingCount;

    /**
     * 创建通用对象池。
     *
     * @param config  池配置
     * @param factory 对象工厂
     * @throws IllegalArgumentException 参数为 null
     */
    public GenericObjectPool(ObjectPoolConfig config, ObjectFactory<T> factory) {
        super(config);
        if (factory == null) {
            throw new IllegalArgumentException("factory must not be null");
        }
        this.factory = factory;
        this.lifo = config.isLifo();
        this.lifoDeque = lifo ? new ArrayDeque<>() : null;
    }

    /**
     * 获取对象工厂。
     */
    public ObjectFactory<T> getFactory() {
        return factory;
    }

    /**
     * 是否使用 LIFO 策略。
     */
    public boolean isLifo() {
        return lifo;
    }

    // ==================== 模板方法钩子 ====================

    @Override
    protected T createResource() throws Exception {
        return factory.create();
    }

    @Override
    protected void destroyResource(T resource) {
        factory.destroy(resource);
    }

    @Override
    protected boolean validateResource(T resource) {
        return factory.validate(resource);
    }

    // ==================== LIFO acquire 覆写 ====================

    /**
     * 获取对象。
     *
     * <p>FIFO 模式委托基类；LIFO 模式从双端队列头部获取最近归还的对象。
     */
    @Override
    public T acquire() throws InterruptedException {
        if (!lifo) {
            return super.acquire();
        }
        ensureRunning();
        lock.lockInterruptibly();
        try {
            return acquireLifoInternal(-1, null);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 获取对象（带超时）。
     *
     * <p>FIFO 模式委托基类；LIFO 模式从双端队列头部获取。
     */
    @Override
    public T acquire(long timeout, TimeUnit unit) throws InterruptedException {
        if (!lifo) {
            return super.acquire(timeout, unit);
        }
        ensureRunning();
        long timeoutNanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            return acquireLifoInternal(timeoutNanos, TimeUnit.NANOSECONDS);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 归还对象。
     *
     * <p>委托基类处理后，LIFO 模式下将对象压入双端队列头部。
     */
    @Override
    public void release(T resource) {
        super.release(resource);
        if (lifo && resource != null) {
            lock.lock();
            try {
                // 仅当基类验证通过且对象已进入 idleMap 时才入队
                if (idleMap.containsKey(resource)) {
                    lifoDeque.addFirst(resource);
                }
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * 销毁池，清理 LIFO 队列。
     */
    @Override
    public void destroy() {
        super.destroy();
        if (lifoDeque != null) {
            lifoDeque.clear();
        }
    }

    // ==================== LIFO 核心逻辑 ====================

    /**
     * LIFO 模式的核心获取逻辑（仿 AbstractResourcePool.acquireInternal 但使用 LIFO 队列）。
     *
     * @param timeoutNanos 超时纳秒，负数 = 无限等待
     * @param unit         时间单位
     */
    @SuppressWarnings("DuplicatedCode")
    private T acquireLifoInternal(long timeoutNanos, TimeUnit unit) throws InterruptedException {
        long deadlineNanos = timeoutNanos >= 0 ? System.nanoTime() + timeoutNanos : Long.MAX_VALUE;

        while (true) {
            ensureRunning();

            // 1. 从 LIFO 队列头部取有效空闲对象
            T idleResource = pollValidFromLifoDeque();
            if (idleResource != null) {
                activeMap.put(idleResource, System.currentTimeMillis());
                totalAcquired.incrementAndGet();
                onResourceAcquired(idleResource);
                return idleResource;
            }

            // 2. 未达上限，创建新对象（锁外创建，锁内检查）
            int poolSize = idleMap.size() + activeMap.size();
            if (poolSize < config.getMaxPoolSize()) {
                T newResource = createResourceOutsideLock();
                if (newResource != null) {
                    return newResource;
                }
                continue;
            }

            // 3. 池已满，等待归还
            lifoPendingCount++;
            if (timeoutNanos >= 0) {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    lifoPendingCount--;
                    throw new PoolExhaustedException(
                            "Pool exhausted: poolSize=" + poolSize
                                    + ", maxPoolSize=" + config.getMaxPoolSize()
                                    + ", poolName=" + config.getPoolName());
                }
                boolean signaled = notEmpty.await(remaining, TimeUnit.NANOSECONDS);
                lifoPendingCount--;
                if (!signaled) {
                    throw new PoolExhaustedException(
                            "Acquire timeout: poolSize=" + poolSize
                                    + ", maxPoolSize=" + config.getMaxPoolSize()
                                    + ", poolName=" + config.getPoolName());
                }
            } else {
                notEmpty.await();
                lifoPendingCount--;
            }
        }
    }

    /**
     * 从 LIFO 双端队列头部取出有效空闲对象。
     * 无效对象（不在 idleMap 中或验证失败）会被跳过并清理。
     */
    private T pollValidFromLifoDeque() {
        while (!lifoDeque.isEmpty()) {
            T resource = lifoDeque.pollFirst();

            // 对象可能已被基类扫描线程回收（idleMap 中已移除）
            if (!idleMap.containsKey(resource)) {
                continue;
            }

            // 从 idleMap 中移除（基类 pollValidIdleResource 也会做这件事）
            idleMap.remove(resource);

            // 验证对象有效性
            if (validateResource(resource)) {
                return resource;
            }

            // 无效对象：销毁
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
     * 在锁外创建对象，重新加锁后检查状态、决定是否入池。
     *
     * <p>仿 AbstractResourcePool.createResourceOutsideLock 逻辑。
     */
    private T createResourceOutsideLock() throws InterruptedException {
        lifoPendingCount++;
        lock.unlock();
        try {
            T resource = createResource();
            lock.lockInterruptibly();
            lifoPendingCount--;

            int poolSize = idleMap.size() + activeMap.size();
            if (poolSize < config.getMaxPoolSize() && status == PoolStatus.RUNNING) {
                activeMap.put(resource, System.currentTimeMillis());
                totalAcquired.incrementAndGet();
                onResourceAcquired(resource);
                return resource;
            }

            // 竞争失败，丢弃
            destroyResource(resource);
            return null;
        } catch (InterruptedException e) {
            lock.lockInterruptibly();
            lifoPendingCount--;
            throw e;
        } catch (Exception e) {
            lock.lockInterruptibly();
            lifoPendingCount--;
            throw new PoolInitializationException("Failed to create resource", e);
        }
    }

    /**
     * 确保池处于运行状态（仿 AbstractResourcePool.checkRunning）。
     */
    private void ensureRunning() {
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
}
