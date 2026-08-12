package com.metapool.adapter.bucket4j;

import com.metapool.common.exception.MetaPoolConfigException;
import com.metapool.common.resource.ManagedResource;
import com.metapool.common.resource.ResourceTypes;
import com.metapool.common.spi.ConfigValues;
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
        long limit = parseLimit(definition.name(), limitRaw);
        Duration refill = ConfigValues.duration("refill-period", props.getOrDefault("refill-period", "1s"));

        Set<String> tunable = definition.tunableKeys().isEmpty()
                ? Bucket4jAdapter.SUPPORTED_TUNABLE_KEYS
                : definition.tunableKeys();

        return Bucket4jAdapter.builder()
                .named(definition.name())
                .limitForPeriod(limit)
                .refillPeriod(refill)
                .tunable(tunable)
                .build();
    }

    /** 非法 {@code limit-for-period} 必须报 {@link MetaPoolConfigException}，不能漏出裸 NumberFormatException（§3.2）。 */
    private static long parseLimit(String name, Object raw) {
        if (raw instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new MetaPoolConfigException("rate-limiter '" + name
                    + "' has invalid 'limit-for-period': " + raw, e);
        }
    }

}
