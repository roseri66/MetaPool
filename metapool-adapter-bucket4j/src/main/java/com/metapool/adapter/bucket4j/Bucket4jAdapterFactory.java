package com.metapool.adapter.bucket4j;

import com.metapool.common.exception.MetaPoolConfigException;
import com.metapool.common.resource.ManagedResource;
import com.metapool.common.resource.ResourceTypes;
import com.metapool.common.spi.ResourceAdapterFactory;
import com.metapool.common.spi.ResourceDefinition;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * {@code rate-limiter} 类型的适配器工厂：从 {@link ResourceDefinition} 构建 {@link Bucket4jAdapter}。
 *
 * <p>通过 JDK {@link java.util.ServiceLoader} 注册于
 * {@code META-INF/services/com.metapool.common.spi.ResourceAdapterFactory}。
 *
 * <p>识别的配置项：{@code limit-for-period}（正整数）、{@code refill-period}（如 {@code 1s} / {@code 500ms}
 * / 纯数字毫秒 / ISO-8601 {@code PT1S}）。
 *
 * @since 2.0.0
 */
public final class Bucket4jAdapterFactory implements ResourceAdapterFactory {

    @Override
    public String supportedType() {
        return ResourceTypes.RATE_LIMITER;
    }

    @Override
    public ManagedResource create(ResourceDefinition definition) {
        if (!supportedType().equals(definition.type())) {
            throw new MetaPoolConfigException(
                    "Bucket4jAdapterFactory cannot handle type '" + definition.type() + "'");
        }
        Map<String, Object> props = definition.properties();
        Object limitRaw = props.get(Bucket4jAdapter.KEY_LIMIT_FOR_PERIOD);
        if (limitRaw == null) {
            throw new MetaPoolConfigException(
                    "rate-limiter '" + definition.name() + "' requires 'limit-for-period'");
        }
        long limit = (limitRaw instanceof Number n) ? n.longValue() : Long.parseLong(String.valueOf(limitRaw));
        Duration refill = parseDuration(props.getOrDefault("refill-period", "1s"));

        Set<String> tunable = definition.tunableKeys().isEmpty()
                ? Set.of(Bucket4jAdapter.KEY_LIMIT_FOR_PERIOD)
                : definition.tunableKeys();

        return Bucket4jAdapter.builder()
                .named(definition.name())
                .limitForPeriod(limit)
                .refillPeriod(refill)
                .tunable(tunable)
                .build();
    }

    /** 支持 {@code "1s"} / {@code "500ms"} / {@code "2m"} / 纯毫秒数字 / ISO-8601（{@code PT1S}）。 */
    static Duration parseDuration(Object raw) {
        if (raw instanceof Number n) {
            return Duration.ofMillis(n.longValue());
        }
        String s = String.valueOf(raw).trim();
        try {
            if (s.startsWith("PT") || s.startsWith("pt")) {
                return Duration.parse(s);
            }
            if (s.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(s.substring(0, s.length() - 2).trim()));
            }
            if (s.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(s.substring(0, s.length() - 1).trim()));
            }
            if (s.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(s.substring(0, s.length() - 1).trim()));
            }
            return Duration.ofMillis(Long.parseLong(s));
        } catch (RuntimeException e) {
            throw new MetaPoolConfigException("invalid refill-period '" + s + "'", e);
        }
    }
}
