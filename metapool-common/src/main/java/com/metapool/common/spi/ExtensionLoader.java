package com.metapool.common.spi;

import com.metapool.common.exception.MetaPoolException;

import java.util.Iterator;
import java.util.ServiceLoader;

/**
 * SPI 扩展点加载器，封装 JDK {@link ServiceLoader}，支持默认实现回退。
 *
 * <h3>加载优先级</h3>
 * <ol>
 *   <li>优先从 {@code META-INF/services/} 加载已注册的实现类（{@link ServiceLoader}）</li>
 *   <li>未找到实现时，回退到 {@link SPI#defaultImpl()} 指定的默认实现</li>
 *   <li>也未指定默认实现时，返回 {@code null}</li>
 * </ol>
 *
 * <h3>线程安全</h3>
 * <p>本类为无状态工具类，所有方法均为线程安全。</p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * AiDiagnosisService service = ExtensionLoader.getExtension(AiDiagnosisService.class);
 * if (service != null) {
 *     DiagnosisResult result = service.diagnose(snapshot);
 * }
 * }</pre>
 *
 * @param <T> SPI 接口类型
 * @since 0.1.0
 */
public final class ExtensionLoader<T> {

    private ExtensionLoader() {
        // 工具类，禁止实例化
    }

    /**
     * 加载指定 SPI 接口的扩展实现。
     *
     * <p>调用方应对返回 {@code null} 做判空兜底——当接口未标注
     * {@link SPI @SPI} 注解、或既无 ServiceLoader 实现也无默认实现时返回 {@code null}。
     *
     * @param spiInterface SPI 接口的 Class 对象，必须标注 {@link SPI @SPI} 注解
     * @param <T>          SPI 接口类型
     * @return 扩展实现实例；无可用实现时返回 {@code null}
     * @throws MetaPoolException 默认实现类不实现 SPI 接口、或实例化失败时抛出
     */
    public static <T> T getExtension(Class<T> spiInterface) {
        if (spiInterface == null) {
            return null;
        }

        SPI spi = spiInterface.getAnnotation(SPI.class);
        if (spi == null) {
            return null;
        }

        // 1. 优先从 ServiceLoader 加载
        ServiceLoader<T> loader = ServiceLoader.load(spiInterface);
        Iterator<T> it = loader.iterator();
        if (it.hasNext()) {
            return it.next();
        }

        // 2. 回退到默认实现
        Class<?> defaultImpl = spi.defaultImpl();
        if (defaultImpl == SPI.None.class) {
            return null;
        }

        if (!spiInterface.isAssignableFrom(defaultImpl)) {
            throw new MetaPoolException("POOL-000",
                    "Default implementation " + defaultImpl.getName()
                            + " does not implement " + spiInterface.getName());
        }

        try {
            return spiInterface.cast(defaultImpl.getDeclaredConstructor().newInstance());
        } catch (Exception e) {
            throw new MetaPoolException("POOL-000",
                    "Failed to instantiate default implementation: " + defaultImpl.getName(), e);
        }
    }
}
