package com.metapool.starter.endpoint;

import com.metapool.starter.MetaPoolProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MetaPool 自定义 Actuator 端点。
 *
 * <p>通过 {@code /actuator/metapool} 查看和修改各资源池运行参数。
 *
 * <h3>端点说明</h3>
 * <ul>
 *   <li>{@code GET /actuator/metapool} — 查看所有池的当前配置</li>
 *   <li>{@code GET /actuator/metapool/{poolType}} — 查看指定池的配置</li>
 *   <li>{@code POST /actuator/metapool/{poolType}} — 更新指定池的配置参数</li>
 * </ul>
 *
 * @since 0.1.0
 */
@Component
@Endpoint(id = "metapool")
@ConditionalOnClass(name = "org.springframework.boot.actuate.endpoint.annotation.Endpoint")
public class MetaPoolMetricsEndpoint {

    private static final Logger log = LoggerFactory.getLogger(MetaPoolMetricsEndpoint.class);

    private final MetaPoolProperties properties;

    public MetaPoolMetricsEndpoint(MetaPoolProperties properties) {
        this.properties = properties;
    }

    /**
     * 查看所有池的当前配置。
     */
    @ReadOperation
    public Map<String, Object> getAllConfigs() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", properties.isEnabled());
        result.put("metrics", mapMetrics());
        result.put("health", mapHealth());
        result.put("thread", mapThread());
        result.put("db", mapDb());
        result.put("redis", mapRedis());
        result.put("object", mapObject());
        result.put("memory", mapMemory());
        result.put("rateLimit", mapRateLimit());
        result.put("lock", mapLock());
        return result;
    }

    /**
     * 查看指定池的当前配置。
     *
     * @param poolType 池类型（thread / db / redis / object / memory / rateLimit / lock）
     */
    @ReadOperation
    public Map<String, Object> getPoolConfig(@Selector String poolType) {
        return switch (poolType.toLowerCase()) {
            case "thread" -> mapThread();
            case "db" -> mapDb();
            case "redis" -> mapRedis();
            case "object" -> mapObject();
            case "memory" -> mapMemory();
            case "ratelimit" -> mapRateLimit();
            case "lock" -> mapLock();
            default -> Map.of("error", "Unknown pool type: " + poolType,
                    "validTypes", new String[]{"thread", "db", "redis", "object", "memory", "rateLimit", "lock"});
        };
    }

    /**
     * 更新指定池的配置参数（运行时修改）。
     *
     * <p>支持的参数通过请求 body 以 JSON 格式传入。
     */
    @WriteOperation
    public Map<String, Object> updatePoolConfig(@Selector String poolType,
                                                 Map<String, Object> params) {
        log.info("动态修改配置: poolType={}, params={}", poolType, params);
        try {
            applyParams(poolType, params);
            return Map.of("status", "success", "poolType", poolType, "applied", params.keySet().toString());
        } catch (Exception e) {
            log.error("动态修改配置失败: poolType={}, error={}", poolType, e.getMessage());
            return Map.of("status", "error", "poolType", poolType, "message", e.getMessage());
        }
    }

    private void applyParams(String poolType, Map<String, Object> params) {
        switch (poolType.toLowerCase()) {
            case "thread" -> {
                MetaPoolProperties.ThreadPool t = properties.getThread();
                if (params.containsKey("corePoolSize")) t.setCorePoolSize((Integer) params.get("corePoolSize"));
                if (params.containsKey("maxPoolSize")) t.setMaxPoolSize((Integer) params.get("maxPoolSize"));
                if (params.containsKey("keepAliveSeconds")) t.setKeepAliveSeconds((Integer) params.get("keepAliveSeconds"));
            }
            case "db" -> {
                MetaPoolProperties.DbPool d = properties.getDb();
                if (params.containsKey("maxPoolSize")) d.setMaxPoolSize((Integer) params.get("maxPoolSize"));
                if (params.containsKey("connectionTimeoutSeconds")) d.setConnectionTimeoutSeconds((Integer) params.get("connectionTimeoutSeconds"));
            }
            case "ratelimit" -> {
                MetaPoolProperties.RateLimit r = properties.getRateLimit();
                if (params.containsKey("permitsPerSecond")) r.setPermitsPerSecond((Integer) params.get("permitsPerSecond"));
            }
            case "lock" -> {
                MetaPoolProperties.Lock l = properties.getLock();
                if (params.containsKey("maxRetryCount")) l.setMaxRetryCount((Integer) params.get("maxRetryCount"));
                if (params.containsKey("retryIntervalMillis")) l.setRetryIntervalMillis((Integer) params.get("retryIntervalMillis"));
            }
            default -> throw new IllegalArgumentException("Unsupported pool type: " + poolType);
        }
    }

    // ==================== Mapping Helpers ====================

    private Map<String, Object> mapMetrics() {
        return Map.of("exportIntervalSeconds", properties.getMetrics().getExportIntervalSeconds());
    }

    private Map<String, Object> mapHealth() {
        return Map.of("checkIntervalSeconds", properties.getHealth().getCheckIntervalSeconds());
    }

    private Map<String, Object> mapThread() {
        MetaPoolProperties.ThreadPool t = properties.getThread();
        return Map.of(
                "enabled", t.isEnabled(),
                "poolName", t.getPoolName(),
                "corePoolSize", t.getCorePoolSize(),
                "maxPoolSize", t.getMaxPoolSize(),
                "keepAliveSeconds", t.getKeepAliveSeconds(),
                "queueCapacity", t.getQueueCapacity(),
                "rejectedPolicy", t.getRejectedPolicy()
        );
    }

    private Map<String, Object> mapDb() {
        MetaPoolProperties.DbPool d = properties.getDb();
        return Map.of(
                "enabled", d.isEnabled(),
                "poolName", d.getPoolName(),
                "minIdle", d.getMinIdle(),
                "maxPoolSize", d.getMaxPoolSize(),
                "maxLifetimeMinutes", d.getMaxLifetimeMinutes(),
                "idleTimeoutSeconds", d.getIdleTimeoutSeconds(),
                "connectionTimeoutSeconds", d.getConnectionTimeoutSeconds()
        );
    }

    private Map<String, Object> mapRedis() {
        MetaPoolProperties.RedisPool r = properties.getRedis();
        return Map.of(
                "enabled", r.isEnabled(),
                "poolName", r.getPoolName(),
                "minIdle", r.getMinIdle(),
                "maxPoolSize", r.getMaxPoolSize(),
                "maxLifetimeMinutes", r.getMaxLifetimeMinutes(),
                "idleTimeoutSeconds", r.getIdleTimeoutSeconds(),
                "connectionTimeoutSeconds", r.getConnectionTimeoutSeconds()
        );
    }

    private Map<String, Object> mapObject() {
        MetaPoolProperties.ObjectPool o = properties.getObject();
        return Map.of(
                "enabled", o.isEnabled(),
                "poolName", o.getPoolName(),
                "minIdle", o.getMinIdle(),
                "maxPoolSize", o.getMaxPoolSize(),
                "idleTimeoutSeconds", o.getIdleTimeoutSeconds(),
                "lifo", o.isLifo()
        );
    }

    private Map<String, Object> mapMemory() {
        MetaPoolProperties.MemoryPool m = properties.getMemory();
        return Map.of(
                "enabled", m.isEnabled(),
                "poolName", m.getPoolName(),
                "maxDirectMemoryMb", m.getMaxDirectMemoryMb(),
                "pageSizeKb", m.getPageSizeKb(),
                "idleTimeoutSeconds", m.getIdleTimeoutSeconds()
        );
    }

    private Map<String, Object> mapRateLimit() {
        MetaPoolProperties.RateLimit r = properties.getRateLimit();
        return Map.of(
                "enabled", r.isEnabled(),
                "algorithm", r.getAlgorithm(),
                "permitsPerSecond", r.getPermitsPerSecond(),
                "warmUpSeconds", r.getWarmUpSeconds()
        );
    }

    private Map<String, Object> mapLock() {
        MetaPoolProperties.Lock l = properties.getLock();
        return Map.of(
                "enabled", l.isEnabled(),
                "defaultTtlSeconds", l.getDefaultTtlSeconds(),
                "renewalIntervalSeconds", l.getRenewalIntervalSeconds(),
                "maxRetryCount", l.getMaxRetryCount(),
                "retryIntervalMillis", l.getRetryIntervalMillis()
        );
    }
}
