package com.smartpool.pool.thread;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 自研线程池执行器，对标 JDK {@link java.util.concurrent.ThreadPoolExecutor}。
 *
 * <h3>调度逻辑</h3>
 * <ol>
 *   <li>任务提交 → 核心线程处理 → 队列缓冲 → 扩容至最大线程 → 拒绝策略</li>
 * </ol>
 *
 * <h3>线程安全</h3>
 * <p>所有公开方法均为线程安全。</p>
 *
 * @since 0.1.0
 */
public class SmartThreadPoolExecutor {

    private final ThreadPoolConfig config;
    private final BlockingQueue<Runnable> workQueue;
    private final Set<Worker> workers = new HashSet<>();
    private final ReentrantLock mainLock = new ReentrantLock();

    /**
     * 当前 worker 线程总数，通过原子变量跟踪避免 execute() 快速路径中的锁竞争。
     * get-and-increment 在 addWorker()（持锁）中执行，
     * get-and-decrement 在 removeWorker() 中尽可能避免与 execute() 竞争。
     */
    private final AtomicInteger workerCount = new AtomicInteger(0);

    /** 拒绝策略 */
    private volatile RejectedPolicy rejectedPolicy;

    /** 运行时可变参数 */
    private volatile int corePoolSize;
    private volatile int maxPoolSize;
    private volatile long keepAliveSeconds;

    /** 指标 */
    private final AtomicInteger activeCount = new AtomicInteger(0);
    private final AtomicLong completedTasks = new AtomicLong(0);
    private final AtomicLong rejectedTasks = new AtomicLong(0);

    /** 状态 */
    private volatile boolean shutdown;
    private volatile boolean terminated;
    /** shutdown 时锁存的 worker 线程，用于 awaitTermination join */
    private volatile Thread[] shutdownWorkers;

    /**
     * 使用默认配置创建执行器。
     */
    public SmartThreadPoolExecutor() {
        this(new ThreadPoolConfig());
    }

    /**
     * 使用指定配置创建执行器。
     *
     * @param config 线程池配置，不可为 null
     */
    public SmartThreadPoolExecutor(ThreadPoolConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.config = config;
        this.corePoolSize = config.getCorePoolSize();
        this.maxPoolSize = config.getMaxPoolSize();
        this.keepAliveSeconds = config.getKeepAliveSeconds();
        this.workQueue = new LinkedBlockingQueue<>(config.getQueueCapacity());
        this.rejectedPolicy = resolveRejectedPolicy(config.getRejectedPolicy());
    }

    // ==================== 任务提交 ====================

    /**
     * 提交一个任务执行。
     *
     * <p>如果池已关闭，任务将被拒绝执行。
     *
     * @param task 待执行的任务
     * @throws RejectedExecutionException 任务被拒绝时（取决于拒绝策略）
     */
    public void execute(Runnable task) {
        if (task == null) {
            throw new NullPointerException("task must not be null");
        }
        if (shutdown) {
            reject(task);
            return;
        }

        // 1. 尝试直接分配 worker（未达核心线程数）
        if (addWorkerIfBelowCore(task)) {
            return;
        }

        // 2. 尝试入队
        if (workQueue.offer(task)) {
            // 入队成功，二次检查是否已关闭
            if (shutdown && workQueue.remove(task)) {
                reject(task);
            }
            return;
        }

        // 3. 尝试扩容到最大线程数
        if (addWorkerIfBelowMax(task)) {
            return;
        }

        // 4. 拒绝
        reject(task);
    }

    // ==================== Worker 管理 ====================

    /**
     * 预热——预创建所有核心线程。
     */
    public void prestartCoreThreads() {
        mainLock.lock();
        try {
            int current = workerCount.get();
            int core = corePoolSize;
            for (int i = current; i < core && !shutdown; i++) {
                addWorker(null);
            }
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * 优雅关闭——不再接受新任务，等待已提交任务执行完毕。
     */
    public void shutdown() {
        shutdown = true;
        mainLock.lock();
        try {
            int count = workers.size();
            Thread[] snapshot = new Thread[count];
            int i = 0;
            for (Worker w : workers) {
                w.running = false;
                snapshot[i++] = w.thread;
            }
            shutdownWorkers = snapshot;

            // 中断所有 worker，唤醒正在 poll() 的线程
            for (Thread t : snapshot) {
                t.interrupt();
            }
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * 等待所有任务执行完毕或超时。
     *
     * @param timeout 最大等待时间
     * @param unit    时间单位
     * @return true 表示所有任务已完成，false 表示超时
     */
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        // 1. 等待队列清空
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (!workQueue.isEmpty()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return false;
            Thread.sleep(Math.min(50, TimeUnit.NANOSECONDS.toMillis(remaining)));
        }

        // 2. join 所有 worker 线程
        Thread[] snapshot = shutdownWorkers;
        if (snapshot != null) {
            for (Thread t : snapshot) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return false;
                t.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
                if (t.isAlive()) return false;
            }
        }

        // 3. 等待所有 worker 从集合中移除
        while (getPoolSize() > 0) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return false;
            Thread.sleep(10);
        }

        terminated = true;
        return true;
    }

    // ==================== 运行时调参 ====================

    /**
     * 动态修改核心线程数，即时生效。
     */
    public void setCorePoolSize(int corePoolSize) {
        if (corePoolSize < 0) {
            throw new IllegalArgumentException("corePoolSize must be >= 0");
        }
        int old = this.corePoolSize;
        this.corePoolSize = corePoolSize;

        // 如果新值大于旧值，预创建核心线程
        if (corePoolSize > old) {
            prestartCoreThreads();
        }
    }

    /**
     * 动态修改最大线程数，即时生效。
     */
    public void setMaxPoolSize(int maxPoolSize) {
        if (maxPoolSize < 0 || maxPoolSize < corePoolSize) {
            throw new IllegalArgumentException("maxPoolSize must be >= corePoolSize");
        }
        this.maxPoolSize = maxPoolSize;
    }

    /**
     * 动态修改非核心线程空闲存活时间。
     */
    public void setKeepAliveSeconds(long keepAliveSeconds) {
        if (keepAliveSeconds < 0) {
            throw new IllegalArgumentException("keepAliveSeconds must be >= 0");
        }
        this.keepAliveSeconds = keepAliveSeconds;
    }

    // ==================== 指标 ====================

    public int getActiveCount() {
        return activeCount.get();
    }

    /** 返回当前 worker 线程总数（无锁，近似值）。 */
    public int getPoolSize() {
        return workerCount.get();
    }

    public int getQueueSize() {
        return workQueue.size();
    }

    public int getQueueCapacity() {
        return workQueue.size() + workQueue.remainingCapacity();
    }

    public long getCompletedTasks() {
        return completedTasks.get();
    }

    public long getRejectedTasks() {
        return rejectedTasks.get();
    }

    public int getCorePoolSize() {
        return corePoolSize;
    }

    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    public boolean isShutdown() {
        return shutdown;
    }

    public boolean isTerminated() {
        return terminated;
    }

    // ==================== 内部方法 ====================

    /**
     * 当前 worker 数低于 corePoolSize 时添加 worker 并分配 firstTask。
     *
     * <p>性能优化：先用 {@link #workerCount} 做无锁快速判断（双检锁模式），
     * 避免每次 execute() 都竞争 mainLock。仅当可能需创建 worker 时才取锁。
     */
    private boolean addWorkerIfBelowCore(Runnable firstTask) {
        // 快速路径：无锁判断，避免 execute() 热路径上的锁竞争
        if (workerCount.get() >= corePoolSize || shutdown) {
            return false;
        }
        mainLock.lock();
        try {
            if (workerCount.get() < corePoolSize && !shutdown) {
                return addWorker(firstTask);
            }
            return false;
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * 当前 worker 数低于 maxPoolSize 时添加 worker 并分配 firstTask。
     *
     * <p>同上，先用 {@link #workerCount} 无锁判断。
     */
    private boolean addWorkerIfBelowMax(Runnable firstTask) {
        if (workerCount.get() >= maxPoolSize || shutdown) {
            return false;
        }
        mainLock.lock();
        try {
            if (workerCount.get() < maxPoolSize && !shutdown) {
                return addWorker(firstTask);
            }
            return false;
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * 在持有 mainLock 时调用，创建并启动 worker。
     */
    private boolean addWorker(Runnable firstTask) {
        Worker w = new Worker(firstTask, workerCount.get());
        workers.add(w);
        workerCount.incrementAndGet();
        w.thread.start();
        return true;
    }

    /**
     * 移除 worker（worker 线程退出时调用）。
     *
     * <p>先持锁从集合移除，再在锁外递减 workerCount，避免与 execute()
     * 快速路径竞争 AtomicInteger。
     */
    void removeWorker(Worker w) {
        mainLock.lock();
        try {
            workers.remove(w);
        } finally {
            mainLock.unlock();
        }
        workerCount.decrementAndGet();
    }

    private void reject(Runnable task) {
        rejectedTasks.incrementAndGet();
        rejectedPolicy.rejectedExecution(task, this);
    }

    private static RejectedPolicy resolveRejectedPolicy(RejectedPolicyEnum policy) {
        switch (policy) {
            case ABORT:
                return new AbortPolicy();
            case DISCARD:
                return new DiscardPolicy();
            case CALLER_RUNS:
            default:
                return new CallerRunsPolicy();
        }
    }

    @Override
    public String toString() {
        return "SmartThreadPoolExecutor[" +
                "poolSize=" + getPoolSize() +
                ", active=" + activeCount.get() +
                ", core=" + corePoolSize +
                ", max=" + maxPoolSize +
                ", queue=" + getQueueSize() +
                ", completed=" + completedTasks.get() +
                ", rejected=" + rejectedTasks.get() +
                ", shutdown=" + shutdown +
                ']';
    }

    // ==================== Worker ====================

    /**
     * 工作线程，循环从队列拉取任务执行。
     */
    private class Worker implements Runnable {

        private final Thread thread;
        private volatile boolean running = true;
        private Runnable firstTask;

        Worker(Runnable firstTask, int workerIndex) {
            this.firstTask = firstTask;
            this.thread = new Thread(this, config.getThreadNamePrefix() + (workerIndex + 1));
            this.thread.setDaemon(true);
        }

        @Override
        public void run() {
            try {
                while (running) {
                    Runnable task = firstTask;
                    firstTask = null; // firstTask 只使用一次

                    if (task == null) {
                        try {
                            task = workQueue.poll(keepAliveSeconds, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            if (shutdown) break;
                            continue;
                        }
                    }

                    if (task != null) {
                        activeCount.incrementAndGet();
                        try {
                            task.run();
                        } finally {
                            activeCount.decrementAndGet();
                            completedTasks.incrementAndGet();
                        }
                    } else {
                        // 空闲超时——非核心线程退出
                        if (getPoolSize() > corePoolSize) {
                            break;
                        }
                    }
                }
            } finally {
                removeWorker(this);
            }
        }
    }
}
