package com.smartpool.pool.thread;

/**
 * 线程资源包装，用于 {@link ThreadResourcePool} 追踪单个工作线程。
 *
 * @since 0.1.0
 */
public class ThreadResource {

    private final Thread thread;
    private final long createdAt;

    public ThreadResource(Thread thread) {
        this.thread = thread;
        this.createdAt = System.currentTimeMillis();
    }

    /** 获取底层线程引用（用于 interrupt / join）。 */
    public Thread getThread() {
        return thread;
    }

    /** 资源创建时间戳（毫秒）。 */
    public long getCreatedAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        return "ThreadResource{thread=" + thread.getName() + "}";
    }
}
