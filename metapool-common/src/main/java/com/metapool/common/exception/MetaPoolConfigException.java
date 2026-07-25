package com.metapool.common.exception;

/**
 * 配置非法异常 — 启动期校验失败时抛出（fail-fast，绝不带病运行）。
 *
 * @since 2.0.0
 */
public class MetaPoolConfigException extends MetaPoolException {

    public MetaPoolConfigException(String message) {
        super(ErrorCode.CONFIG_INVALID, message);
    }

    public MetaPoolConfigException(String message, Throwable cause) {
        super(ErrorCode.CONFIG_INVALID, message, cause);
    }
}
