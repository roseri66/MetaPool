package com.metapool.common.lifecycle;

/**
 * 资源池运行时统计快照。
 *
 * <p>由 {@link ResourceLifecycle#stats()} 返回，反映调用时刻的瞬时状态。
 * 各字段之间不保证原子一致性。
 *
 * @since 0.1.0
 */
public final class PoolStats {

    /** 当前活跃（已借出）资源数 */
    private final int activeCount;

    /** 当前空闲（可借出）资源数 */
    private final int idleCount;

    /** 当前等待获取资源的请求数 */
    private final int pendingCount;

    /** 历史累计获取次数 */
    private final long totalAcquired;

    /** 历史累计归还次数 */
    private final long totalReleased;

    /** 当前检测到的泄露资源数 */
    private final int leakDetected;

    private PoolStats(Builder builder) {
        this.activeCount = builder.activeCount;
        this.idleCount = builder.idleCount;
        this.pendingCount = builder.pendingCount;
        this.totalAcquired = builder.totalAcquired;
        this.totalReleased = builder.totalReleased;
        this.leakDetected = builder.leakDetected;
    }

    public int getActiveCount() {
        return activeCount;
    }

    public int getIdleCount() {
        return idleCount;
    }

    public int getPendingCount() {
        return pendingCount;
    }

    public long getTotalAcquired() {
        return totalAcquired;
    }

    public long getTotalReleased() {
        return totalReleased;
    }

    public int getLeakDetected() {
        return leakDetected;
    }

    /** 返回当前池总大小（活跃 + 空闲） */
    public int getPoolSize() {
        return activeCount + idleCount;
    }

    @Override
    public String toString() {
        return "PoolStats{active=" + activeCount
                + ", idle=" + idleCount
                + ", pending=" + pendingCount
                + ", totalAcquired=" + totalAcquired
                + ", totalReleased=" + totalReleased
                + ", leakDetected=" + leakDetected + '}';
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int activeCount;
        private int idleCount;
        private int pendingCount;
        private long totalAcquired;
        private long totalReleased;
        private int leakDetected;

        private Builder() {
        }

        public Builder activeCount(int activeCount) {
            this.activeCount = activeCount;
            return this;
        }

        public Builder idleCount(int idleCount) {
            this.idleCount = idleCount;
            return this;
        }

        public Builder pendingCount(int pendingCount) {
            this.pendingCount = pendingCount;
            return this;
        }

        public Builder totalAcquired(long totalAcquired) {
            this.totalAcquired = totalAcquired;
            return this;
        }

        public Builder totalReleased(long totalReleased) {
            this.totalReleased = totalReleased;
            return this;
        }

        public Builder leakDetected(int leakDetected) {
            this.leakDetected = leakDetected;
            return this;
        }

        public PoolStats build() {
            return new PoolStats(this);
        }
    }
}
