package com.smartpool.common.pool;

/**
 * 资源池配置基类，定义所有资源池共用的配置参数。
 *
 * <p>各资源池模块继承此类，添加自身特有参数（如线程池的 corePoolSize、连接池的 validationTimeout 等）。
 *
 * @since 0.1.0
 */
public class PoolConfig {

    /** 最小空闲资源数，默认 2 */
    private int minIdle = 2;

    /** 最大池大小，默认 16 */
    private int maxPoolSize = 16;

    /** 空闲资源超时回收秒数，默认 300 */
    private long idleTimeoutSeconds = 300;

    /** 资源泄露检测阈值秒数，借出超过此时长未归还视为泄露，默认 180 */
    private long leakDetectionThresholdSeconds = 180;

    /** 资源池名称，用于日志标识和 JMX 注册 */
    private String poolName = "smartpool";

    public int getMinIdle() {
        return minIdle;
    }

    public void setMinIdle(int minIdle) {
        this.minIdle = minIdle;
    }

    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    public void setMaxPoolSize(int maxPoolSize) {
        this.maxPoolSize = maxPoolSize;
    }

    public long getIdleTimeoutSeconds() {
        return idleTimeoutSeconds;
    }

    public void setIdleTimeoutSeconds(long idleTimeoutSeconds) {
        this.idleTimeoutSeconds = idleTimeoutSeconds;
    }

    public long getLeakDetectionThresholdSeconds() {
        return leakDetectionThresholdSeconds;
    }

    public void setLeakDetectionThresholdSeconds(long leakDetectionThresholdSeconds) {
        this.leakDetectionThresholdSeconds = leakDetectionThresholdSeconds;
    }

    public String getPoolName() {
        return poolName;
    }

    public void setPoolName(String poolName) {
        this.poolName = poolName;
    }
}
