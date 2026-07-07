package com.metapool.starter.advice;

import com.metapool.common.exception.PoolExhaustedException;
import com.metapool.common.exception.PoolInitializationException;
import com.metapool.common.exception.ResourceLeakException;
import com.metapool.common.exception.MetaPoolException;
import com.metapool.starter.vo.ApiResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * MetaPool 全局异常拦截器。
 *
 * <p>通过 {@code @RestControllerAdvice} 拦截所有 Controller 抛出的异常，
 * 统一映射为 {@link ApiResult} JSON 响应体，确保 API 返回口径一致。
 *
 * <h3>异常 → HTTP 状态码映射</h3>
 * <table>
 *   <tr><th>异常类</th><th>HTTP 码</th><th>ErrorCode</th></tr>
 *   <tr><td>PoolExhaustedException</td><td>503</td><td>POOL-001</td></tr>
 *   <tr><td>ResourceLeakException</td><td>500</td><td>POOL-002</td></tr>
 *   <tr><td>PoolInitializationException</td><td>500</td><td>POOL-003</td></tr>
 *   <tr><td>MetaPoolException</td><td>500</td><td>POOL-000</td></tr>
 *   <tr><td>Exception（兜底）</td><td>500</td><td>POOL-999</td></tr>
 * </table>
 *
 * <p>仅在 Spring Web（spring-web 模块）存在于 classpath 时生效。
 *
 * @since 0.1.0
 */
@RestControllerAdvice
@ConditionalOnClass(name = "org.springframework.web.bind.annotation.RestControllerAdvice")
public class MetaPoolExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(MetaPoolExceptionHandler.class);

    /** 资源池耗尽 — 503 Service Unavailable */
    @ExceptionHandler(PoolExhaustedException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ApiResult<Void> handlePoolExhausted(PoolExhaustedException e) {
        log.warn("资源池耗尽: {}", e.getMessage());
        return ApiResult.fail(503001, e.getMessage());
    }

    /** 资源泄露检测 — 500 Internal Server Error */
    @ExceptionHandler(ResourceLeakException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResult<Void> handleResourceLeak(ResourceLeakException e) {
        log.error("资源泄露检测: {}", e.getMessage(), e);
        return ApiResult.fail(500002, e.getMessage());
    }

    /** 池初始化失败 — 500 Internal Server Error */
    @ExceptionHandler(PoolInitializationException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResult<Void> handleInitFailure(PoolInitializationException e) {
        log.error("资源池初始化失败: {}", e.getMessage(), e);
        return ApiResult.fail(500003, e.getMessage());
    }

    /** MetaPool 通用异常 — 500 Internal Server Error */
    @ExceptionHandler(MetaPoolException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResult<Void> handleMetaPool(MetaPoolException e) {
        log.error("MetaPool 异常: {}", e.getMessage(), e);
        return ApiResult.fail(500000, e.getMessage());
    }

    /** 未预期异常（兜底） — 500 Internal Server Error */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResult<Void> handleUnknown(Exception e) {
        log.error("未预期异常", e);
        // 生产环境脱敏：不返回具体异常信息
        return ApiResult.fail(500999, "Internal server error");
    }
}
