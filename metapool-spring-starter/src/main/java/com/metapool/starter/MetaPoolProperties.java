package com.metapool.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MetaPool 配置绑定。
 *
 * <p>每类资源以 {@code 名称 → 原始属性} 的 Map 声明；属性名<b>直通底层库</b>（如 HikariCP 的
 * {@code maximum-pool-size}），MetaPool 不发明第二套命名。特殊键 {@code tunable} 声明可运行时热调的
 * 参数白名单。
 *
 * <pre>{@code
 * metapool:
 *   datasources:
 *     main:
 *       jdbc-url: jdbc:postgresql://localhost:5432/app
 *       maximum-pool-size: 20
 *       tunable: [maximum-pool-size, connection-timeout]
 *   rate-limiters:
 *     order-api:
 *       limit-for-period: 100
 *       refill-period: 1s
 *       tunable: [limit-for-period]
 *   executors:
 *     order-worker:
 *       core-pool-size: 4
 *       maximum-pool-size: 8
 *       queue-capacity: 100
 *       tunable: [core-pool-size, maximum-pool-size]
 * }</pre>
 *
 * <h3>已知局限：每种资源类型一个字段</h3>
 * <p>本类为每个内置类型硬编码了一个 Map 字段。这意味着<b>第三方经 SPI 扩展的资源类型无法用
 * YAML 声明</b> —— 而 {@code ManagedResource.type()} 用 String 而非 enum，本意恰恰是不挡第三方扩展。
 * 配置绑定层把这个口子又堵上了一半。修法（通用 {@code metapool.resources.<type>.<name>}）
 * 属于公开配置面变更，留待专门设计，不在适配器 PR 里顺手改。
 *
 * @since 2.0.0
 */
@ConfigurationProperties(prefix = "metapool")
public class MetaPoolProperties {

    /** 总开关，默认开启。 */
    private boolean enabled = true;

    /** 数据源（type=datasource），名称 → 直通 HikariCP 的属性。 */
    private Map<String, Map<String, Object>> datasources = new LinkedHashMap<>();

    /** 限流器（type=rate-limiter），名称 → 直通 Bucket4j 的属性。 */
    private Map<String, Map<String, Object>> rateLimiters = new LinkedHashMap<>();

    /** 线程池（type=executor），名称 → 直通 JDK ThreadPoolExecutor 的属性。 */
    private Map<String, Map<String, Object>> executors = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Map<String, Map<String, Object>> getDatasources() {
        return datasources;
    }

    public void setDatasources(Map<String, Map<String, Object>> datasources) {
        this.datasources = datasources;
    }

    public Map<String, Map<String, Object>> getRateLimiters() {
        return rateLimiters;
    }

    public void setRateLimiters(Map<String, Map<String, Object>> rateLimiters) {
        this.rateLimiters = rateLimiters;
    }

    public Map<String, Map<String, Object>> getExecutors() {
        return executors;
    }

    public void setExecutors(Map<String, Map<String, Object>> executors) {
        this.executors = executors;
    }
}
