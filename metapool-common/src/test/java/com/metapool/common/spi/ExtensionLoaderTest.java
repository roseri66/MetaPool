package com.metapool.common.spi;

import com.metapool.common.exception.MetaPoolException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link ExtensionLoader} 单元测试。
 */
@DisplayName("ExtensionLoader")
class ExtensionLoaderTest {

    @Nested
    @DisplayName("ServiceLoader 加载")
    class ServiceLoaderTests {

        @Test
        @DisplayName("应优先从 META-INF/services 加载已注册的实现")
        void shouldLoadFromServiceLoader() {
            TestSpiService service = ExtensionLoader.getExtension(TestSpiService.class);

            assertNotNull(service);
            assertEquals("custom", service.getName(),
                    "应返回 META-INF/services 中注册的 CustomTestSpiService");
        }
    }

    @Nested
    @DisplayName("默认实现回退")
    class DefaultImplTests {

        @Test
        @DisplayName("无 ServiceLoader 实现时应回退到 defaultImpl")
        void shouldFallbackToDefaultImpl() {
            // FallbackSpiService 有 @SPI(defaultImpl=FallbackTestSpiService)，无 ServiceLoader 注册
            FallbackSpiService service = ExtensionLoader.getExtension(FallbackSpiService.class);

            assertNotNull(service);
            assertEquals("fallback", service.getName(),
                    "应返回 FallbackTestSpiService（defaultImpl 指定的默认实现）");
        }

        @Test
        @DisplayName("无 ServiceLoader 且无 defaultImpl 时应返回 null")
        void shouldReturnNullWhenNoImplAvailable() {
            NoDefaultSpiService service = ExtensionLoader.getExtension(NoDefaultSpiService.class);

            assertNull(service,
                    "无 ServiceLoader 实现且未指定 defaultImpl 时应返回 null");
        }
    }

    @Nested
    @DisplayName("边界情况")
    class EdgeCaseTests {

        @Test
        @DisplayName("传入 null 应返回 null")
        void shouldReturnNullForNullInput() {
            assertNull(ExtensionLoader.getExtension(null));
        }

        @Test
        @DisplayName("未标注 @SPI 的接口应返回 null")
        void shouldReturnNullForNonSpiInterface() {
            Runnable service = ExtensionLoader.getExtension(Runnable.class);
            assertNull(service);
        }

        @Test
        @DisplayName("defaultImpl 不实现 SPI 接口时应抛 MetaPoolException")
        void shouldThrowWhenDefaultImplMismatch() {
            // BadSpiService 的 defaultImpl = String.class，String 不实现 BadSpiService
            MetaPoolException ex = assertThrows(MetaPoolException.class,
                    () -> ExtensionLoader.getExtension(BadSpiService.class));

            assertEquals("POOL-000", ex.getErrorCode());
        }
    }

    /* ==================== 内部测试用 SPI 接口 ==================== */

    /**
     * 测试用——defaultImpl 故意设置为不实现该接口的 String.class，用于验证异常路径。
     */
    @SPI(defaultImpl = String.class)
    interface BadSpiService {
        String getName();
    }
}
