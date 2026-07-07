package com.smartpool.pool.memory;

/**
 * 内存页，包装一个固定大小的 {@code byte[]} 数据块。
 *
 * <p>每个内存页大小等于 {@link MemoryPoolConfig#getPageSizeKB()} × 1024 字节。
 * 由 {@link MemoryPool} 统一分配和回收。</p>
 *
 * <h3>线程安全</h3>
 * <p>不可变对象，线程安全。</p>
 *
 * @since 0.1.0
 */
public class MemoryPage {

    /** 内存页数据 */
    private final byte[] data;

    /** 页大小（KB） */
    private final int pageSizeKB;

    /** 创建时间戳（毫秒） */
    private final long creationTime;

    /**
     * 创建指定大小的内存页。
     *
     * @param pageSizeKB 页大小（KB），用于创建 data 数组和元数据记录
     */
    public MemoryPage(int pageSizeKB) {
        this.pageSizeKB = pageSizeKB;
        this.data = new byte[pageSizeKB * 1024];
        this.creationTime = System.currentTimeMillis();
    }

    /**
     * 获取底层字节数组。
     *
     * @return 页数据，长度 = pageSizeKB × 1024
     */
    public byte[] getData() {
        return data;
    }

    /**
     * 页大小（KB）。
     */
    public int getPageSizeKB() {
        return pageSizeKB;
    }

    /**
     * 页大小（字节）。
     */
    public int getPageSizeBytes() {
        return pageSizeKB * 1024;
    }

    /**
     * 该页创建时间（毫秒时间戳）。
     */
    public long getCreationTime() {
        return creationTime;
    }

    @Override
    public String toString() {
        return "MemoryPage{pageSizeKB=" + pageSizeKB + ", age="
                + (System.currentTimeMillis() - creationTime) + "ms}";
    }
}
