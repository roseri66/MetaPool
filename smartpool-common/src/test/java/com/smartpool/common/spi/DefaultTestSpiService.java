package com.smartpool.common.spi;

/**
 * 测试用默认实现。
 */
public class DefaultTestSpiService implements TestSpiService {

    @Override
    public String getName() {
        return "default";
    }
}
