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
 *   locks:
 *     order-lock:
 *       address: redis://127.0.0.1:6379
 *       key-prefix: "myapp:lock:"
 *   objects:
 *     buffer-pool:
 *       factory-class: com.example.MyPooledObjectFactory   # 必填，须有无参构造
 *       max-total: 16
 *       max-wait: 3s
 *       tunable: [max-total, max-idle]
 * }</pre>
 *
 * <p>注意 {@code locks} 没有 {@code tunable}：Redisson 锁的 {@code waitTime} / {@code leaseTime}
 * 是每次调用传入的，不是配置项，故该适配器不实现 {@code Tunable}。
 *
 * <h3>两种写法，等价且可混用</h3>
 * <p>上面那些<b>具名分段</b>（{@code datasources} / {@code rate-limiters} / …）是 2.0 起的写法，
 * 每个内置类型一个字段。它们的问题是<b>第三方经 SPI 扩展的资源类型没法写</b> ——
 * 而 {@code ManagedResource.type()} 用 String 而非 enum，本意恰恰是不挡第三方扩展，
 * 配置层却把这个口子堵掉了一半。
 *
 * <p>因此 2.1 增加了<b>通用分段</b> {@code metapool.resources.<类型>.<名称>}，类型任意：
 *
 * <pre>{@code
 * metapool:
 *   resources:
 *     datasource:                    # 内置类型也能这么写
 *       reporting:
 *         jdbc-url: jdbc:postgresql://.../report
 *     my-custom-type:                # 第三方 adapter 的类型，SPI 发现即可用
 *       whatever:
 *         some-native-key: 42
 * }</pre>
 *
 * <p><b>具名分段一个都不会废弃</b>：2.0.x 的配置无需改动即可升级。两种写法可以混用，
 * 唯一的约束是<b>资源名全局唯一</b> —— 同名在两处出现会在启动时报错，并指出是哪两段撞了。
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

    /** 分布式锁（type=lock），名称 → 直通 Redisson 的属性。 */
    private Map<String, Map<String, Object>> locks = new LinkedHashMap<>();

    /** 通用对象池（type=object），名称 → 直通 Commons Pool2 的属性（须含 {@code factory-class}）。 */
    private Map<String, Map<String, Object>> objects = new LinkedHashMap<>();

    /**
     * 通用分段：<b>类型 → 名称 → 属性</b>。类型任意，包括第三方经 SPI 扩展的类型。
     *
     * <p>与上面的具名分段等价、可混用。类型字符串必须与某个
     * {@code ResourceAdapterFactory.supportedType()} 对得上，否则启动即失败并列出可用类型。
     */
    private Map<String, Map<String, Map<String, Object>>> resources = new LinkedHashMap<>();

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

    public Map<String, Map<String, Object>> getLocks() {
        return locks;
    }

    public void setLocks(Map<String, Map<String, Object>> locks) {
        this.locks = locks;
    }

    public Map<String, Map<String, Object>> getObjects() {
        return objects;
    }

    public void setObjects(Map<String, Map<String, Object>> objects) {
        this.objects = objects;
    }

    public Map<String, Map<String, Map<String, Object>>> getResources() {
        return resources;
    }

    public void setResources(Map<String, Map<String, Map<String, Object>>> resources) {
        this.resources = resources;
    }
}
