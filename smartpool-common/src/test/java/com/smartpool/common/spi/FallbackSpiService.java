package com.smartpool.common.spi;

/**
 * 测试用 SPI 接口——有默认实现，无 ServiceLoader 注册，用于验证回退逻辑。
 */
@SPI(defaultImpl = FallbackTestSpiService.class)
public interface FallbackSpiService {

    String getName();
}
