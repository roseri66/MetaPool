package com.metapool.pool.memory;

import com.metapool.common.enums.PoolStatus;
import com.metapool.common.exception.PoolExhaustedException;
import com.metapool.common.pool.AbstractResourcePool;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存资源池，管理固定大小的堆内内存页（{@code byte[]}）并提供堆外内存（{@link ByteBuffer#allocateDirect}）预留。
 *
 * <h3>功能</h3>
 * <ul>
 *   <li><b>堆内内存池</b> — 基于 {@code byte[]} 分页管理，每页大小 = {@link MemoryPoolConfig#getPageSizeKB()} KB，
 *       通过 {@link #acquire()} / {@link #release(MemoryPage)} 借还，减少频繁 GC。</li>
 *   <li><b>堆外预留</b> — {@link #allocateDirect(int)} / {@link #freeDirect(ByteBuffer)} 抽象方法，
 *       V1 由 JDK {@link java.lang.ref.Cleaner} 兜底清理。</li>
 *   <li><b>内存上限</b> — {@link MemoryPoolConfig#getMaxDirectMemoryMB()} 硬限制，涵盖堆内页 + 堆外分配总和，
 *       超限抛 {@link PoolExhaustedException}，不 OOM。</li>
 *   <li><b>空闲回收</b> — 空闲内存页超 {@link MemoryPoolConfig#getIdleTimeoutSeconds()} 自动回收，
 *       释放堆内存。</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <p>{@link #acquire()} / {@link #acquire(long, java.util.concurrent.TimeUnit)} / {@link #release(MemoryPage)}
 * / {@link #allocateDirect(int)} / {@link #freeDirect(ByteBuffer)} 支持多线程并发。</p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * MemoryPoolConfig config = new MemoryPoolConfig();
 * config.setMaxPoolSize(16).setMinIdle(2).setMaxDirectMemoryMB(256).setPageSizeKB(64);
 *
 * MemoryPool pool = new MemoryPool(config);
 * pool.init();
 *
 * MemoryPage page = pool.acquire();
 * try {
 *     byte[] data = page.getData();
 *     // 读写数据...
 * } finally {
 *     pool.release(page);
 * }
 *
 * // 堆外分配
 * ByteBuffer direct = pool.allocateDirect(4096);
 * // ... 使用 direct buffer ...
 * pool.freeDirect(direct);
 *
 * pool.destroy();
 * }</pre>
 *
 * @since 0.1.0
 */
public class MemoryPool extends AbstractResourcePool<MemoryPage> {

    private final MemoryPoolConfig memConfig;

    /** 堆内内存已用量（字节），含 active + idle 中的所有页 */
    private final AtomicLong heapMemoryUsed = new AtomicLong(0);

    /** 堆外直接内存已用量（字节） */
    private final AtomicLong directMemoryUsed = new AtomicLong(0);

    /**
     * 创建内存池。
     *
     * @param config 内存池配置
     */
    public MemoryPool(MemoryPoolConfig config) {
        super(config);
        this.memConfig = config;
    }

    /**
     * 获取内存池配置。
     */
    public MemoryPoolConfig getMemoryConfig() {
        return memConfig;
    }

    // ==================== 模板方法钩子 ====================

    /**
     * 创建新内存页。
     *
     * <p>在堆上分配 {@code byte[pageSizeKB * 1024]}，并检查总内存（堆内 + 堆外）是否超过
     * {@link MemoryPoolConfig#getMaxDirectMemoryMB()} 上限。</p>
     *
     * @return 新创建的内存页
     * @throws PoolExhaustedException 内存超限
     */
    @Override
    protected MemoryPage createResource() {
        long pageBytes = memConfig.getPageSizeKB() * 1024L;
        long maxBytes = memConfig.getMaxDirectMemoryMB() * 1024L * 1024L;

        // 检查内存上限
        long currentTotal = heapMemoryUsed.get() + directMemoryUsed.get();
        if (currentTotal + pageBytes > maxBytes) {
            throw new PoolExhaustedException(
                    "Memory limit exceeded: currentTotal=" + currentTotal
                            + " bytes (" + (currentTotal / (1024.0 * 1024.0)) + " MB)"
                            + ", pageBytes=" + pageBytes
                            + ", maxBytes=" + maxBytes
                            + " (" + memConfig.getMaxDirectMemoryMB() + " MB)"
                            + ", poolName=" + config.getPoolName());
        }

        MemoryPage page = new MemoryPage(memConfig.getPageSizeKB());
        heapMemoryUsed.addAndGet(pageBytes);
        return page;
    }

    /**
     * 销毁内存页，释放堆内存。
     *
     * <p>页内 {@code byte[]} 由 GC 自动回收。仅从内部计数器扣除该页占用的字节数。</p>
     *
     * @param resource 待销毁的内存页
     */
    @Override
    protected void destroyResource(MemoryPage resource) {
        if (resource != null) {
            heapMemoryUsed.addAndGet(-resource.getPageSizeBytes());
            // byte[] 由 GC 自动回收，无需显式清理
        }
    }

    /**
     * 验证内存页有效性。
     *
     * <p>堆内 {@code byte[]} 始终有效，直接返回 true。</p>
     *
     * @param resource 待验证的内存页
     * @return 始终返回 true
     */
    @Override
    protected boolean validateResource(MemoryPage resource) {
        return resource != null;
    }

    // ==================== 堆外内存预留 ====================

    /**
     * 分配堆外直接内存。
     *
     * <p>通过 {@link ByteBuffer#allocateDirect(int)} 分配，受
     * {@link MemoryPoolConfig#getMaxDirectMemoryMB()} 总上限约束。
     * V1 由 JDK {@link java.lang.ref.Cleaner} 兜底清理，调用方应主动调用
     * {@link #freeDirect(ByteBuffer)} 释放。</p>
     *
     * @param capacity 分配容量（字节）
     * @return 新分配的 {@link ByteBuffer}
     * @throws IllegalArgumentException capacity 非正数
     * @throws PoolExhaustedException    超出内存上限
     */
    public ByteBuffer allocateDirect(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }

        long maxBytes = memConfig.getMaxDirectMemoryMB() * 1024L * 1024L;
        long currentTotal = heapMemoryUsed.get() + directMemoryUsed.get();

        if (currentTotal + capacity > maxBytes) {
            throw new PoolExhaustedException(
                    "Direct memory allocation denied: currentTotal=" + currentTotal
                            + " bytes, requested=" + capacity
                            + ", maxBytes=" + maxBytes
                            + " (" + memConfig.getMaxDirectMemoryMB() + " MB)"
                            + ", poolName=" + config.getPoolName());
        }

        try {
            ByteBuffer buffer = ByteBuffer.allocateDirect(capacity);
            directMemoryUsed.addAndGet(capacity);
            return buffer;
        } catch (OutOfMemoryError e) {
            throw new PoolExhaustedException(
                    "Direct memory allocation failed (OOM): requested=" + capacity
                            + " bytes, maxDirectMemoryMB=" + memConfig.getMaxDirectMemoryMB()
                            + ", poolName=" + config.getPoolName(), e);
        }
    }

    /**
     * 释放堆外直接内存。
     *
     * <p>从内部计数器扣减已用直接内存。底层 {@link ByteBuffer} 由 JDK {@link java.lang.ref.Cleaner} 实际回收。</p>
     *
     * @param buffer 待释放的 {@link ByteBuffer}，null 时静默返回
     */
    public void freeDirect(ByteBuffer buffer) {
        if (buffer == null) {
            return;
        }

        if (!buffer.isDirect()) {
            return;
        }

        int capacity = buffer.capacity();
        directMemoryUsed.addAndGet(-capacity);
        // Cleaner 在 ByteBuffer 不可达时自动释放本机内存
    }

    // ==================== 统计查询 ====================

    /**
     * 获取堆内内存当前使用量（字节）。
     */
    public long getHeapMemoryUsed() {
        return heapMemoryUsed.get();
    }

    /**
     * 获取堆外直接内存当前使用量（字节）。
     */
    public long getDirectMemoryUsed() {
        return directMemoryUsed.get();
    }

    /**
     * 获取总内存使用量（堆内 + 堆外，字节）。
     */
    public long getTotalMemoryUsed() {
        return heapMemoryUsed.get() + directMemoryUsed.get();
    }

    /**
     * 获取内存上限（字节）。
     */
    public long getMaxMemoryBytes() {
        return memConfig.getMaxDirectMemoryMB() * 1024L * 1024L;
    }

}
