package com.smartpool.pool.thread;

import com.smartpool.common.pool.PoolConfig;

/**
 * 线程池配置，继承 {@link PoolConfig} 基类，增加线程池特有参数。
 *
 * @since 0.1.0
 */
public class ThreadPoolConfig extends PoolConfig {

    /** 核心线程数，默认 10 */
    private int corePoolSize = 10;

    /** 非核心线程空闲存活秒数，默认 60 */
    private long keepAliveSeconds = 60;

    /** 任务队列容量，默认 1000 */
    private int queueCapacity = 1000;

    /** 拒绝策略，默认 CALLER_RUNS */
    private RejectedPolicyEnum rejectedPolicy = RejectedPolicyEnum.CALLER_RUNS;

    /** 线程名前缀，默认 smartpool-worker- */
    private String threadNamePrefix = "smartpool-worker-";

    // ─── getters / setters ───

    public int getCorePoolSize() {
        return corePoolSize;
    }

    public void setCorePoolSize(int corePoolSize) {
        if (corePoolSize < 0) {
            throw new IllegalArgumentException("corePoolSize must be >= 0");
        }
        this.corePoolSize = corePoolSize;
    }

    public long getKeepAliveSeconds() {
        return keepAliveSeconds;
    }

    public void setKeepAliveSeconds(long keepAliveSeconds) {
        if (keepAliveSeconds < 0) {
            throw new IllegalArgumentException("keepAliveSeconds must be >= 0");
        }
        this.keepAliveSeconds = keepAliveSeconds;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be > 0");
        }
        this.queueCapacity = queueCapacity;
    }

    public RejectedPolicyEnum getRejectedPolicy() {
        return rejectedPolicy;
    }

    public void setRejectedPolicy(RejectedPolicyEnum rejectedPolicy) {
        this.rejectedPolicy = rejectedPolicy;
    }

    public String getThreadNamePrefix() {
        return threadNamePrefix;
    }

    public void setThreadNamePrefix(String threadNamePrefix) {
        this.threadNamePrefix = threadNamePrefix;
    }
}
