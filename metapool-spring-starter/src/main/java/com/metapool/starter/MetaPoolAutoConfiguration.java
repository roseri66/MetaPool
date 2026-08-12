package com.metapool.starter;

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

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
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

        // 用 ResourceTypes 常量而非字面量 —— 该类的存在意义就是「避免各处硬编码类型字符串」
        props.getDatasources().forEach((name, raw) ->
                manager.register(loader.create(toDefinition(name, ResourceTypes.DATASOURCE, raw))));
        props.getRateLimiters().forEach((name, raw) ->
                manager.register(loader.create(toDefinition(name, ResourceTypes.RATE_LIMITER, raw))));
        props.getExecutors().forEach((name, raw) ->
                manager.register(loader.create(toDefinition(name, ResourceTypes.EXECUTOR, raw))));

        meterRegistries.ifAvailable(manager::bindMetrics);
        manager.start();
        return manager;
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
