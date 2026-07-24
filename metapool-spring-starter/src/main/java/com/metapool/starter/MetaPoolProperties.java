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
 * }</pre>
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
}
