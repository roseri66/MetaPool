package com.metapool.common.spi;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个接口为 SPI（Service Provider Interface）扩展点。
 *
 * <p>被此注解标记的接口可通过 {@link ExtensionLoader} 加载其实现类。
 * 实现类通过 JDK {@link java.util.ServiceLoader} 机制注册于
 * {@code META-INF/services/接口全限定名} 文件中。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * @SPI(defaultImpl = DefaultDiagnosisService.class)
 * public interface AiDiagnosisService {
 *     DiagnosisResult diagnose(PoolMetricsSnapshot snapshot);
 * }
 * }</pre>
 *
 * @see ExtensionLoader
 * @since 0.1.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SPI {

    /**
     * 默认实现类。当 {@link java.util.ServiceLoader} 未找到任何实现时，
     * {@link ExtensionLoader} 将实例化此默认实现作为回退。
     *
     * <p>默认值为 {@link None}，表示无默认实现，此时 ExtensionLoader 返回 {@code null}。
     *
     * @return 默认实现类
     */
    Class<?> defaultImpl() default None.class;

    /**
     * 哨兵值，表示未指定默认实现。
     */
    final class None {
        private None() {}
    }
}
