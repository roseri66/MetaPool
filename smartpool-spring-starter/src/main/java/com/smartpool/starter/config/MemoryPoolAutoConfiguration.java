package com.smartpool.starter.config;

import com.smartpool.pool.memory.MemoryPool;
import com.smartpool.pool.memory.MemoryPoolConfig;
import com.smartpool.starter.SmartPoolProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * 内存资源池自动装配。
 *
 * <p>当 classpath 中存在 {@code MemoryPool} 且配置
 * {@code smartpool.memory.enabled=true}（默认）时，自动创建内存池。
 *
 * @since 0.1.0
 */
@AutoConfiguration
@ConditionalOnClass(MemoryPool.class)
@ConditionalOnProperty(prefix = "smartpool.memory", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MemoryPoolAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MemoryPoolAutoConfiguration.class);

    @Bean
    public MemoryPoolConfig memoryPoolConfig(SmartPoolProperties properties) {
        SmartPoolProperties.MemoryPool props = properties.getMemory();
        MemoryPoolConfig config = new MemoryPoolConfig();
        config.setPoolName(props.getPoolName());
        config.setMaxPoolSize(props.getMaxPoolSize());
        config.setMaxDirectMemoryMB(props.getMaxDirectMemoryMb());
        config.setPageSizeKB(props.getPageSizeKb());
        config.setIdleTimeoutSeconds(props.getIdleTimeoutSeconds());
        return config;
    }

    @Bean
    public MemoryPool memoryPool(MemoryPoolConfig config) {
        MemoryPool pool = new MemoryPool(config);
        pool.init();
        log.info("内存资源池已创建: poolName={}, maxDirectMemoryMB={}, pageSizeKB={}",
                config.getPoolName(), config.getMaxDirectMemoryMB(), config.getPageSizeKB());
        return pool;
    }
}
