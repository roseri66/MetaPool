package com.metapool.starter;

import com.metapool.common.manager.ResourceManager;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通用分段 {@code metapool.resources.<type>.<name>} 的验收。
 *
 * <p>它存在的理由：{@code ManagedResource.type()} 用 String 而非 enum，本意是不挡第三方 SPI 扩展；
 * 而具名分段（{@code datasources} / {@code rate-limiters} / …）为每个内置类型硬编码一个字段，
 * 把这个口子堵掉了一半——第三方类型根本没法用 YAML 声明。
 */
class GenericResourcesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MetaPoolAutoConfiguration.class))
            .withUserConfiguration(MeterRegistryConfig.class);

    /**
     * 内置类型用通用分段声明，效果与具名分段完全一致。
     *
     * <p>同时验证一件容易想当然的事：<b>带连字符的类型名（{@code rate-limiter}）在 Spring
     * 的 Map 绑定里必须原样保留</b>——若被规范化成别的形式，就对不上
     * {@code ResourceAdapterFactory.supportedType()}，启动即失败。
     */
    @Test
    void genericSection_worksForBuiltInTypes_includingHyphenatedTypeNames() {
        runner.withPropertyValues(
                        "metapool.resources.datasource.reporting.jdbc-url="
                                + "jdbc:h2:mem:genericDs;DB_CLOSE_DELAY=-1",
                        "metapool.resources.datasource.reporting.username=sa",
                        "metapool.resources.datasource.reporting.maximum-pool-size=2",
                        "metapool.resources.rate-limiter.search-api.limit-for-period=50",
                        "metapool.resources.rate-limiter.search-api.refill-period=1s")
                .run(ctx -> {
                    ResourceManager mgr = ctx.getBean(ResourceManager.class);
                    assertThat(mgr.resources()).hasSize(2);

                    assertThat(mgr.get("reporting").type()).isEqualTo("datasource");
                    assertThat(mgr.get("search-api").type()).isEqualTo("rate-limiter");

                    MeterRegistry registry = ctx.getBean(MeterRegistry.class);
                    assertThat(registry.find("metapool.datasource.connections.active")
                            .tag("metapool.resource", "reporting").gauge()).isNotNull();
                    assertThat(registry.find("metapool.ratelimiter.available.tokens")
                            .tag("metapool.resource", "search-api").gauge()).isNotNull();
                });
    }

    /** {@code tunable} 在通用分段里同样被识别为治理字段，而不是当成底层库参数透传下去。 */
    @Test
    void genericSection_stillExtractsTunableWhitelist() {
        runner.withPropertyValues(
                        "metapool.resources.rate-limiter.api.limit-for-period=10",
                        "metapool.resources.rate-limiter.api.tunable=limit-for-period")
                .run(ctx -> {
                    ResourceManager mgr = ctx.getBean(ResourceManager.class);
                    assertThat(mgr.tune("api", java.util.Map.of("limit-for-period", "20")).success())
                            .isTrue();
                });
    }

    /** 两种写法混用是允许的——2.0.x 的配置不必改，新资源可以用新写法。 */
    @Test
    void bothSections_canCoexist() {
        runner.withPropertyValues(
                        "metapool.rate-limiters.legacy-api.limit-for-period=10",
                        "metapool.resources.rate-limiter.new-api.limit-for-period=20")
                .run(ctx -> {
                    ResourceManager mgr = ctx.getBean(ResourceManager.class);
                    assertThat(mgr.resources()).hasSize(2);
                    assertThat(mgr.find("legacy-api")).isPresent();
                    assertThat(mgr.find("new-api")).isPresent();
                });
    }

    /**
     * 同一个名字在两段里都出现 → 启动即失败，且<b>报错要指出是哪两段撞了</b>。
     *
     * <p>控制面自己的重名拒绝只能说「名字撞了」；混用两种写法时，最容易犯的错正是把同一个
     * 资源写了两遍，说清出处能省一轮排查。
     */
    @Test
    void duplicateNameAcrossSections_failsFast_namingBothSources() {
        runner.withPropertyValues(
                        "metapool.rate-limiters.order-api.limit-for-period=10",
                        "metapool.resources.rate-limiter.order-api.limit-for-period=20")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure())
                            .hasStackTraceContaining("duplicate resource name 'order-api'")
                            .hasStackTraceContaining("metapool.rate-limiters")
                            .hasStackTraceContaining("metapool.resources.rate-limiter");
                });
    }

    /** 未知类型由 SPI loader 兜住：启动即失败，并列出实际可用的类型（可操作的报错）。 */
    @Test
    void unknownType_failsFast_listingAvailableTypes() {
        runner.withPropertyValues("metapool.resources.no-such-type.x.foo=1")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure())
                            .hasStackTraceContaining("no ResourceAdapterFactory for type 'no-such-type'")
                            .hasStackTraceContaining("available:");
                });
    }

    @Test
    void emptyConfiguration_startsWithNoResources() {
        runner.run(ctx -> assertThat(ctx.getBean(ResourceManager.class).resources()).isEmpty());
    }

    @Configuration(proxyBeanMethods = false)
    static class MeterRegistryConfig {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
