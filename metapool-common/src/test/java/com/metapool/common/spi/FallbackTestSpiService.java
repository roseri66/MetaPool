package com.metapool.common.spi;

/**
 * 测试用默认实现（专供 FallbackSpiService）。
 */
public class FallbackTestSpiService implements FallbackSpiService {

    @Override
    public String getName() {
        return "fallback";
    }
}
