package com.metapool.starter;

import com.metapool.common.exception.PoolExhaustedException;
import com.metapool.common.exception.PoolInitializationException;
import com.metapool.common.exception.ResourceLeakException;
import com.metapool.common.exception.MetaPoolException;
import com.metapool.starter.advice.MetaPoolExceptionHandler;
import com.metapool.starter.vo.ApiResult;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * SPEC-16 ApiResult 和 MetaPoolExceptionHandler 测试。
 *
 * @since 0.1.0
 */
@DisplayName("SPEC-16 ApiResult 和异常处理测试")
class ApiResultAndExceptionHandlerTest {

    // ==================== ApiResult 基础测试 ====================

    @Nested
    @DisplayName("ApiResult 静态工厂")
    class ApiResultFactory {

        @Test
        @DisplayName("success() 应返回 code=0")
        void shouldReturnSuccessCode() {
            ApiResult<Void> result = ApiResult.success();
            assertThat(result.getCode()).isEqualTo(0);
            assertThat(result.getMessage()).isEqualTo("success");
            assertThat(result.getTimestamp()).isGreaterThan(0);
        }

        @Test
        @DisplayName("success(data) 应包含数据")
        void shouldContainData() {
            ApiResult<String> result = ApiResult.success("hello");
            assertThat(result.getCode()).isEqualTo(0);
            assertThat(result.getData()).isEqualTo("hello");
        }

        @Test
        @DisplayName("fail(code, message) 应返回指定错误码")
        void shouldReturnSpecifiedErrorCode() {
            ApiResult<Void> result = ApiResult.fail(503001, "Resource exhausted");
            assertThat(result.getCode()).isEqualTo(503001);
            assertThat(result.getMessage()).isEqualTo("Resource exhausted");
        }

        @Test
        @DisplayName("fail(message) 应返回默认错误码 -1")
        void shouldReturnDefaultErrorCode() {
            ApiResult<Void> result = ApiResult.fail("something wrong");
            assertThat(result.getCode()).isEqualTo(-1);
        }
    }

    // ==================== 异常处理器集成测试 ====================

    @Nested
    @DisplayName("MetaPoolExceptionHandler 集成测试")
    class ExceptionHandlerIntegration {

        @RestController
        static class TestController {
            @GetMapping("/test/pool-exhausted")
            public String throwPoolExhausted() {
                throw new PoolExhaustedException("Connection pool exhausted");
            }

            @GetMapping("/test/resource-leak")
            public String throwResourceLeak() {
                throw new ResourceLeakException("Resource leaked: idle-conn-5");
            }

            @GetMapping("/test/init-failure")
            public String throwInitFailure() {
                throw new PoolInitializationException("Unable to connect to DB");
            }

            @GetMapping("/test/metapool-exception")
            public String throwMetaPoolException() {
                throw new MetaPoolException("POOL-999", "General pool error");
            }

            @GetMapping("/test/unknown-exception")
            public String throwUnknownException() {
                throw new RuntimeException("Boom!");
            }
        }

        private final MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new TestController())
                .setControllerAdvice(new MetaPoolExceptionHandler())
                .build();

        @Test
        @DisplayName("PoolExhaustedException → 503 + POOL-001")
        void shouldReturn503ForPoolExhausted() throws Exception {
            MockHttpServletResponse response = mockMvc.perform(get("/test/pool-exhausted"))
                    .andReturn().getResponse();

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
            assertThat(response.getContentAsString()).contains("Connection pool exhausted");
        }

        @Test
        @DisplayName("ResourceLeakException → 500 + POOL-002")
        void shouldReturn500ForResourceLeak() throws Exception {
            MockHttpServletResponse response = mockMvc.perform(get("/test/resource-leak"))
                    .andReturn().getResponse();

            assertThat(response.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
            assertThat(response.getContentAsString()).contains("Resource leaked");
        }

        @Test
        @DisplayName("PoolInitializationException → 500 + POOL-003")
        void shouldReturn500ForInitFailure() throws Exception {
            MockHttpServletResponse response = mockMvc.perform(get("/test/init-failure"))
                    .andReturn().getResponse();

            assertThat(response.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
            assertThat(response.getContentAsString()).contains("Unable to connect");
        }

        @Test
        @DisplayName("MetaPoolException → 500")
        void shouldReturn500ForMetaPoolException() throws Exception {
            MockHttpServletResponse response = mockMvc.perform(get("/test/metapool-exception"))
                    .andReturn().getResponse();

            assertThat(response.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        }

        @Test
        @DisplayName("Unknown Exception → 500 + 脱敏消息")
        void shouldReturn500WithSanitizedMessage() throws Exception {
            MockHttpServletResponse response = mockMvc.perform(get("/test/unknown-exception"))
                    .andReturn().getResponse();

            assertThat(response.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
            // 生产脱敏：不暴露内部异常消息
            assertThat(response.getContentAsString()).contains("Internal server error");
            assertThat(response.getContentAsString()).doesNotContain("Boom!");
        }
    }
}
