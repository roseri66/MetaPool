package com.smartpool.common.spi;

/**
 * 测试用自定义实现（通过 ServiceLoader 注册）。
 */
public class CustomTestSpiService implements TestSpiService {

    @Override
    public String getName() {
        return "custom";
    }
}
