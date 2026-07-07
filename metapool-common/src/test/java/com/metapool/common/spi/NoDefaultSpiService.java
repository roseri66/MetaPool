package com.metapool.common.spi;

/**
 * 测试用 SPI 接口——无默认实现，用于验证返回 null 场景。
 */
@SPI
public interface NoDefaultSpiService {

    String getName();
}
