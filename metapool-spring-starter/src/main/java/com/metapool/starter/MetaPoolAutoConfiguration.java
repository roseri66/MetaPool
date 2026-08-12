package com.metapool.starter;

import com.metapool.common.exception.MetaPoolConfigException;
import com.metapool.common.manager.ResourceManager;
import com.metapool.common.resource.ResourceTypes;
import com.metapool.common.spi.ResourceDefinition;
import com.metapool.core.DefaultResourceManager;
import com.metapool.core.ResourceAdapterLoader;
import com.metapool.starter.endpoint.MetaPoolEndpoint;
import com.metapool.starter.health.MetaPoolHealthIndicator;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MetaPool 控制面自动装配。
 *
 * <p>把 {@link MetaPoolProperties} 声明的每个资源经 SPI（{@link ResourceAdapterLoader}）构建、注册进
 * {@link ResourceManager}，绑定 Micrometer 指标并启动；容器关闭时 {@code close()} 逆序优雅停机。
 *
 * @since 2.0.0
 */
@AutoConfiguration
@EnableConfigurationProperties(MetaPoolProperties.class)
@ConditionalOnProperty(prefix = "metapool", name = "enabled", matchIfMissing = true)
public class MetaPoolAutoConfiguration {

    private static final String TUNABLE_KEY = "tunable";

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public ResourceManager metaPoolResourceManager(MetaPoolProperties props,
                                                   ObjectProvider<MeterRegistry> meterRegistries) {
        DefaultResourceManager manager = new DefaultResourceManager();
        ResourceAdapterLoader loader = new ResourceAdapterLoader();

        for (SourcedDefinition sd : collect(props)) {
            manager.register(loader.create(sd.definition()));
        }

        meterRegistries.ifAvailable(manager::bindMetrics);
        manager.start();
        return manager;
    }

    /**
     * 把两种配置写法收敛成一条注册路径，并在此做重名检查。
     *
     * <p>为什么不直接依赖 {@code DefaultResourceManager.register()} 的重名拒绝：它只知道
     * 「名字撞了」，说不出<b>是哪两段配置撞的</b>。而通用分段与具名分段可以混用，重名恰恰是
     * 最容易犯的错（同一个资源不小心写了两遍），把出处指出来能省下一轮排查。
     */
    private List<SourcedDefinition> collect(MetaPoolProperties props) {
        List<SourcedDefinition> all = new ArrayList<>();
        // 具名分段（2.0 起的写法，保留兼容）。用 ResourceTypes 常量而非字面量 ——
        // 该类的存在意义就是「避免各处硬编码类型字符串」。
        addAll(all, props.getDatasources(), ResourceTypes.DATASOURCE, "metapool.datasources");
        addAll(all, props.getRateLimiters(), ResourceTypes.RATE_LIMITER, "metapool.rate-limiters");
        addAll(all, props.getExecutors(), ResourceTypes.EXECUTOR, "metapool.executors");
        addAll(all, props.getLocks(), ResourceTypes.LOCK, "metapool.locks");
        addAll(all, props.getObjects(), ResourceTypes.OBJECT, "metapool.objects");
        addAll(all, props.getRedis(), ResourceTypes.REDIS, "metapool.redis");
        // 通用分段：类型任意，第三方 SPI 扩展的类型也能声明式接入
        props.getResources().forEach((type, byName) -> {
            if (type == null || type.isBlank()) {
                throw new MetaPoolConfigException(
                        "metapool.resources contains a blank resource type");
            }
            addAll(all, byName, type, "metapool.resources." + type);
        });

        Map<String, String> sourceByName = new LinkedHashMap<>();
        for (SourcedDefinition sd : all) {
            String previous = sourceByName.putIfAbsent(sd.definition().name(), sd.source());
            if (previous != null) {
                throw new MetaPoolConfigException("duplicate resource name '" + sd.definition().name()
                        + "' declared in both '" + previous + "' and '" + sd.source()
                        + "'; resource names must be unique across all sections");
            }
        }
        return all;
    }

    private void addAll(List<SourcedDefinition> sink, Map<String, Map<String, Object>> byName,
                        String type, String source) {
        if (byName == null) {
            return;
        }
        byName.forEach((name, raw) ->
                sink.add(new SourcedDefinition(toDefinition(name, type, raw), source)));
    }

    /** 定义 + 它来自哪一段配置（只为了报错时说得清）。 */
    private record SourcedDefinition(ResourceDefinition definition, String source) {
    }

    /** 从原始属性抽出 tunable 白名单，其余作为直通配置构建 {@link ResourceDefinition}。 */
    private ResourceDefinition toDefinition(String name, String type, Map<String, Object> raw) {
        Map<String, Object> props = new LinkedHashMap<>(raw);
        Object tunableRaw = props.remove(TUNABLE_KEY);
        return new ResourceDefinition(name, type, props, parseTunable(tunableRaw));
    }

    private Set<String> parseTunable(Object raw) {
        if (raw instanceof Collection<?> c) {
            return c.stream().map(String::valueOf).map(String::trim).collect(Collectors.toSet());
        }
        if (raw instanceof String s && !s.isBlank()) {
            return Arrays.stream(s.split(",")).map(String::trim)
                    .filter(v -> !v.isEmpty()).collect(Collectors.toSet());
        }
        return Set.of();
    }

    /**
     * Actuator 集成：仅当类路径存在 spring-boot-actuator 时装配 health 贡献器与 {@code metapool} 端点。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({HealthIndicator.class, Endpoint.class})
    static class ActuatorConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public MetaPoolHealthIndicator metaPoolHealthIndicator(ResourceManager manager) {
            return new MetaPoolHealthIndicator(manager);
        }

        @Bean
        @ConditionalOnMissingBean
        public MetaPoolEndpoint metaPoolEndpoint(ResourceManager manager) {
            return new MetaPoolEndpoint(manager);
        }
    }
}
