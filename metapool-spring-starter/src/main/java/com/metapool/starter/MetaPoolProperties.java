package com.metapool.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MetaPool 全局配置属性绑定。
 *
 * <p>通过 {@code application.yml} / {@code application.properties} 中
 * {@code metapool.*} 命名空间配置所有资源池参数。
 *
 * <h3>使用方法</h3>
 * <pre>{@code
 * metapool:
 *   enabled: true
 *   thread:
 *     core-pool-size: 10
 *     max-pool-size: 50
 * }</pre>
 *
 * @since 0.1.0
 */
@ConfigurationProperties(prefix = "metapool")
public class MetaPoolProperties {

    /** 总开关，默认 true */
    private boolean enabled = true;

    /** 指标采集间隔（秒），默认 15 */
    private Metrics metrics = new Metrics();

    /** 健康检查间隔（秒），默认 30 */
    private Health health = new Health();

    /** 线程池配置 */
    private ThreadPool thread = new ThreadPool();

    /** 数据库连接池配置 */
    private DbPool db = new DbPool();

    /** Redis 连接池配置 */
    private RedisPool redis = new RedisPool();

    /** 通用对象池配置 */
    private ObjectPool object = new ObjectPool();

    /** 内存池配置 */
    private MemoryPool memory = new MemoryPool();

    /** 限流器配置 */
    private RateLimit rateLimit = new RateLimit();

    /** 分布式锁配置 */
    private Lock lock = new Lock();

    // ==================== Getters / Setters ====================

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Metrics getMetrics() {
        return metrics;
    }

    public void setMetrics(Metrics metrics) {
        this.metrics = metrics;
    }

    public Health getHealth() {
        return health;
    }

    public void setHealth(Health health) {
        this.health = health;
    }

    public ThreadPool getThread() {
        return thread;
    }

    public void setThread(ThreadPool thread) {
        this.thread = thread;
    }

    public DbPool getDb() {
        return db;
    }

    public void setDb(DbPool db) {
        this.db = db;
    }

    public RedisPool getRedis() {
        return redis;
    }

    public void setRedis(RedisPool redis) {
        this.redis = redis;
    }

    public ObjectPool getObject() {
        return object;
    }

    public void setObject(ObjectPool object) {
        this.object = object;
    }

    public MemoryPool getMemory() {
        return memory;
    }

    public void setMemory(MemoryPool memory) {
        this.memory = memory;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RateLimit rateLimit) {
        this.rateLimit = rateLimit;
    }

    public Lock getLock() {
        return lock;
    }

    public void setLock(Lock lock) {
        this.lock = lock;
    }

    // ==================== 内嵌配置类 ====================

    /** 指标采集通用配置 */
    public static class Metrics {
        /** 指标采集间隔（秒） */
        private int exportIntervalSeconds = 15;

        public int getExportIntervalSeconds() {
            return exportIntervalSeconds;
        }

        public void setExportIntervalSeconds(int exportIntervalSeconds) {
            this.exportIntervalSeconds = exportIntervalSeconds;
        }
    }

    /** 健康检查通用配置 */
    public static class Health {
        /** 健康检查间隔（秒） */
        private int checkIntervalSeconds = 30;

        public int getCheckIntervalSeconds() {
            return checkIntervalSeconds;
        }

        public void setCheckIntervalSeconds(int checkIntervalSeconds) {
            this.checkIntervalSeconds = checkIntervalSeconds;
        }
    }

    /** 线程池配置 */
    public static class ThreadPool {
        private boolean enabled = true;
        private String poolName = "metapool-thread";
        private int corePoolSize = 10;
        private int maxPoolSize = 50;
        private int keepAliveSeconds = 60;
        private int queueCapacity = 1000;
        /** 拒绝策略：ABORT / CALLER_RUNS / DISCARD */
        private String rejectedPolicy = "CALLER_RUNS";
        private String threadNamePrefix = "metapool-worker-";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getPoolName() { return poolName; }
        public void setPoolName(String poolName) { this.poolName = poolName; }
        public int getCorePoolSize() { return corePoolSize; }
        public void setCorePoolSize(int corePoolSize) { this.corePoolSize = corePoolSize; }
        public int getMaxPoolSize() { return maxPoolSize; }
        public void setMaxPoolSize(int maxPoolSize) { this.maxPoolSize = maxPoolSize; }
        public int getKeepAliveSeconds() { return keepAliveSeconds; }
        public void setKeepAliveSeconds(int keepAliveSeconds) { this.keepAliveSeconds = keepAliveSeconds; }
        public int getQueueCapacity() { return queueCapacity; }
        public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
        public String getRejectedPolicy() { return rejectedPolicy; }
        public void setRejectedPolicy(String rejectedPolicy) { this.rejectedPolicy = rejectedPolicy; }
        public String getThreadNamePrefix() { return threadNamePrefix; }
        public void setThreadNamePrefix(String threadNamePrefix) { this.threadNamePrefix = threadNamePrefix; }
    }

    /** 数据库连接池配置 */
    public static class DbPool {
        private boolean enabled = true;
        private String poolName = "metapool-db";
        private int minIdle = 5;
        private int maxPoolSize = 20;
        private int maxLifetimeMinutes = 30;
        private int idleTimeoutSeconds = 600;
        private int connectionTimeoutSeconds = 30;
        private int validationTimeoutSeconds = 5;
        private int leakDetectionThresholdSeconds = 60;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getPoolName() { return poolName; }
        public void setPoolName(String poolName) { this.poolName = poolName; }
        public int getMinIdle() { return minIdle; }
        public void setMinIdle(int minIdle) { this.minIdle = minIdle; }
        public int getMaxPoolSize() { return maxPoolSize; }
        public void setMaxPoolSize(int maxPoolSize) { this.maxPoolSize = maxPoolSize; }
        public int getMaxLifetimeMinutes() { return maxLifetimeMinutes; }
        public void setMaxLifetimeMinutes(int maxLifetimeMinutes) { this.maxLifetimeMinutes = maxLifetimeMinutes; }
        public int getIdleTimeoutSeconds() { return idleTimeoutSeconds; }
        public void setIdleTimeoutSeconds(int idleTimeoutSeconds) { this.idleTimeoutSeconds = idleTimeoutSeconds; }
        public int getConnectionTimeoutSeconds() { return connectionTimeoutSeconds; }
        public void setConnectionTimeoutSeconds(int connectionTimeoutSeconds) { this.connectionTimeoutSeconds = connectionTimeoutSeconds; }
        public int getValidationTimeoutSeconds() { return validationTimeoutSeconds; }
        public void setValidationTimeoutSeconds(int validationTimeoutSeconds) { this.validationTimeoutSeconds = validationTimeoutSeconds; }
        public int getLeakDetectionThresholdSeconds() { return leakDetectionThresholdSeconds; }
        public void setLeakDetectionThresholdSeconds(int leakDetectionThresholdSeconds) { this.leakDetectionThresholdSeconds = leakDetectionThresholdSeconds; }
    }

    /** Redis 连接池配置 */
    public static class RedisPool {
        private boolean enabled = true;
        private String poolName = "metapool-redis";
        private int minIdle = 5;
        private int maxPoolSize = 20;
        private int maxLifetimeMinutes = 30;
        private int idleTimeoutSeconds = 600;
        private int connectionTimeoutSeconds = 10;
        private int leakDetectionThresholdSeconds = 60;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getPoolName() { return poolName; }
        public void setPoolName(String poolName) { this.poolName = poolName; }
        public int getMinIdle() { return minIdle; }
        public void setMinIdle(int minIdle) { this.minIdle = minIdle; }
        public int getMaxPoolSize() { return maxPoolSize; }
        public void setMaxPoolSize(int maxPoolSize) { this.maxPoolSize = maxPoolSize; }
        public int getMaxLifetimeMinutes() { return maxLifetimeMinutes; }
        public void setMaxLifetimeMinutes(int maxLifetimeMinutes) { this.maxLifetimeMinutes = maxLifetimeMinutes; }
        public int getIdleTimeoutSeconds() { return idleTimeoutSeconds; }
        public void setIdleTimeoutSeconds(int idleTimeoutSeconds) { this.idleTimeoutSeconds = idleTimeoutSeconds; }
        public int getConnectionTimeoutSeconds() { return connectionTimeoutSeconds; }
        public void setConnectionTimeoutSeconds(int connectionTimeoutSeconds) { this.connectionTimeoutSeconds = connectionTimeoutSeconds; }
        public int getLeakDetectionThresholdSeconds() { return leakDetectionThresholdSeconds; }
        public void setLeakDetectionThresholdSeconds(int leakDetectionThresholdSeconds) { this.leakDetectionThresholdSeconds = leakDetectionThresholdSeconds; }
    }

    /** 通用对象池配置 */
    public static class ObjectPool {
        private boolean enabled = true;
        private String poolName = "metapool-object";
        private int minIdle = 2;
        private int maxPoolSize = 32;
        private int idleTimeoutSeconds = 300;
        private boolean lifo = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getPoolName() { return poolName; }
        public void setPoolName(String poolName) { this.poolName = poolName; }
        public int getMinIdle() { return minIdle; }
        public void setMinIdle(int minIdle) { this.minIdle = minIdle; }
        public int getMaxPoolSize() { return maxPoolSize; }
        public void setMaxPoolSize(int maxPoolSize) { this.maxPoolSize = maxPoolSize; }
        public int getIdleTimeoutSeconds() { return idleTimeoutSeconds; }
        public void setIdleTimeoutSeconds(int idleTimeoutSeconds) { this.idleTimeoutSeconds = idleTimeoutSeconds; }
        public boolean isLifo() { return lifo; }
        public void setLifo(boolean lifo) { this.lifo = lifo; }
    }

    /** 内存池配置 */
    public static class MemoryPool {
        private boolean enabled = true;
        private String poolName = "metapool-memory";
        private int minIdle = 2;
        private int maxPoolSize = 16;
        private int maxDirectMemoryMb = 256;
        private int pageSizeKb = 64;
        private int idleTimeoutSeconds = 300;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getPoolName() { return poolName; }
        public void setPoolName(String poolName) { this.poolName = poolName; }
        public int getMinIdle() { return minIdle; }
        public void setMinIdle(int minIdle) { this.minIdle = minIdle; }
        public int getMaxPoolSize() { return maxPoolSize; }
        public void setMaxPoolSize(int maxPoolSize) { this.maxPoolSize = maxPoolSize; }
        public int getMaxDirectMemoryMb() { return maxDirectMemoryMb; }
        public void setMaxDirectMemoryMb(int maxDirectMemoryMb) { this.maxDirectMemoryMb = maxDirectMemoryMb; }
        public int getPageSizeKb() { return pageSizeKb; }
        public void setPageSizeKb(int pageSizeKb) { this.pageSizeKb = pageSizeKb; }
        public int getIdleTimeoutSeconds() { return idleTimeoutSeconds; }
        public void setIdleTimeoutSeconds(int idleTimeoutSeconds) { this.idleTimeoutSeconds = idleTimeoutSeconds; }
    }

    /** 限流器配置 */
    public static class RateLimit {
        private boolean enabled = true;
        /** 算法：TOKEN_BUCKET / SLIDING_WINDOW */
        private String algorithm = "TOKEN_BUCKET";
        private int permitsPerSecond = 1000;
        private int warmUpSeconds = 10;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getAlgorithm() { return algorithm; }
        public void setAlgorithm(String algorithm) { this.algorithm = algorithm; }
        public int getPermitsPerSecond() { return permitsPerSecond; }
        public void setPermitsPerSecond(int permitsPerSecond) { this.permitsPerSecond = permitsPerSecond; }
        public int getWarmUpSeconds() { return warmUpSeconds; }
        public void setWarmUpSeconds(int warmUpSeconds) { this.warmUpSeconds = warmUpSeconds; }
    }

    /** 分布式锁配置 */
    public static class Lock {
        private boolean enabled = true;
        private int defaultTtlSeconds = 30;
        private int renewalIntervalSeconds = 10;
        private int maxRetryCount = 3;
        private int retryIntervalMillis = 100;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getDefaultTtlSeconds() { return defaultTtlSeconds; }
        public void setDefaultTtlSeconds(int defaultTtlSeconds) { this.defaultTtlSeconds = defaultTtlSeconds; }
        public int getRenewalIntervalSeconds() { return renewalIntervalSeconds; }
        public void setRenewalIntervalSeconds(int renewalIntervalSeconds) { this.renewalIntervalSeconds = renewalIntervalSeconds; }
        public int getMaxRetryCount() { return maxRetryCount; }
        public void setMaxRetryCount(int maxRetryCount) { this.maxRetryCount = maxRetryCount; }
        public int getRetryIntervalMillis() { return retryIntervalMillis; }
        public void setRetryIntervalMillis(int retryIntervalMillis) { this.retryIntervalMillis = retryIntervalMillis; }
    }
}
