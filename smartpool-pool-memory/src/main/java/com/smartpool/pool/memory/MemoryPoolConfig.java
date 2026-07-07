package com.smartpool.pool.memory;

import com.smartpool.common.pool.PoolConfig;

/**
 * 内存资源池配置，继承 {@link PoolConfig} 基类。
 *
 * <h3>配置示例</h3>
 * <pre>{@code
 * smartpool.memory:
 *   max-direct-memory-mb: 256
 *   page-size-kb: 64
 *   idle-timeout-seconds: 300
 * }</pre>
 *
 * @since 0.1.0
 */
public class MemoryPoolConfig extends PoolConfig {

    /** 最大内存上限（MB），覆盖堆内堆外总和，默认 256 MB */
    private long maxDirectMemoryMB = 256;

    /** 内存页大小（KB），控制每页粒度，默认 64 KB */
    private int pageSizeKB = 64;

    public long getMaxDirectMemoryMB() {
        return maxDirectMemoryMB;
    }

    public void setMaxDirectMemoryMB(long maxDirectMemoryMB) {
        if (maxDirectMemoryMB <= 0) {
            throw new IllegalArgumentException("maxDirectMemoryMB must be positive");
        }
        this.maxDirectMemoryMB = maxDirectMemoryMB;
    }

    public int getPageSizeKB() {
        return pageSizeKB;
    }

    public void setPageSizeKB(int pageSizeKB) {
        if (pageSizeKB <= 0) {
            throw new IllegalArgumentException("pageSizeKB must be positive");
        }
        this.pageSizeKB = pageSizeKB;
    }
}
