package com.smartpool.starter.config;

import com.smartpool.pool.thread.RejectedPolicyEnum;
import com.smartpool.pool.thread.ThreadPoolConfig;
import com.smartpool.pool.thread.ThreadResourcePool;
import com.smartpool.starter.SmartPoolProperties;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * 线程池自动装配。
 *
 * <p>当 classpath 中存在 {@code ThreadResourcePool} 且配置
 * {@code smartpool.thread.enabled=true}（默认）时，自动创建线程池实例。
 *
 * @since 0.1.0
 */
@AutoConfiguration
@ConditionalOnClass(ThreadResourcePool.class)
@ConditionalOnProperty(prefix = "smartpool.thread", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ThreadPoolAutoConfiguration {

    @Bean
    public ThreadPoolConfig threadPoolConfig(SmartPoolProperties properties) {
        SmartPoolProperties.ThreadPool props = properties.getThread();
        ThreadPoolConfig config = new ThreadPoolConfig();
        config.setPoolName(props.getPoolName());
        config.setCorePoolSize(props.getCorePoolSize());
        config.setMaxPoolSize(props.getMaxPoolSize());
        config.setKeepAliveSeconds(props.getKeepAliveSeconds());
        config.setQueueCapacity(props.getQueueCapacity());
        config.setRejectedPolicy(RejectedPolicyEnum.valueOf(props.getRejectedPolicy()));
        config.setThreadNamePrefix(props.getThreadNamePrefix());
        return config;
    }

    @Bean
    public ThreadResourcePool threadResourcePool(ThreadPoolConfig config) {
        ThreadResourcePool pool = new ThreadResourcePool(config);
        pool.init();
        return pool;
    }
}
