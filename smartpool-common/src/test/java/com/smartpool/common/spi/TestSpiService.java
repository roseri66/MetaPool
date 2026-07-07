package com.smartpool.common.spi;

/**
 * 测试用 SPI 接口——带默认实现。
 */
@SPI(defaultImpl = DefaultTestSpiService.class)
public interface TestSpiService {

    String getName();
}
