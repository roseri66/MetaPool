package com.smartpool.starter;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * SmartPool 主自动装配入口。
 *
 * <p>通过 Spring Boot 3.4 的 {@code AutoConfiguration.imports} 机制自动加载。
 * 启用 {@code smartpool.*} 配置属性绑定。
 *
 * @since 0.1.0
 */
@AutoConfiguration
@EnableConfigurationProperties(SmartPoolProperties.class)
public class SmartPoolAutoConfiguration {
}
