package com.metapool.starter.config;

import com.metapool.pool.object.GenericObjectPool;
import com.metapool.pool.object.ObjectFactory;
import com.metapool.pool.object.ObjectPoolConfig;
import com.metapool.starter.MetaPoolProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * 通用对象池自动装配。
 *
 * <p>当 classpath 中存在 {@code GenericObjectPool} 且配置
 * {@code metapool.object.enabled=true}（默认）时，自动创建对象池。
 *
 * <p><b>注意</b>：业务方需要自行提供 {@link ObjectFactory} Bean 来定义
 * 对象的创建/销毁/验证逻辑。如果不存在 {@link ObjectFactory} Bean，
 * 对象池将不会被自动创建。
 *
 * @since 0.1.0
 */
@AutoConfiguration
@ConditionalOnClass(GenericObjectPool.class)
@ConditionalOnProperty(prefix = "metapool.object", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ObjectPoolAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ObjectPoolAutoConfiguration.class);

    @Bean
    public ObjectPoolConfig objectPoolConfig(MetaPoolProperties properties) {
        MetaPoolProperties.ObjectPool props = properties.getObject();
        ObjectPoolConfig config = new ObjectPoolConfig();
        config.setPoolName(props.getPoolName());
        config.setMinIdle(props.getMinIdle());
        config.setMaxPoolSize(props.getMaxPoolSize());
        config.setIdleTimeoutSeconds(props.getIdleTimeoutSeconds());
        config.setLifo(props.isLifo());
        return config;
    }

    @Bean
    @SuppressWarnings({"rawtypes", "unchecked"})
    public GenericObjectPool<?> genericObjectPool(ObjectPoolConfig config,
                                                   ObjectFactory<?> factory) {
        GenericObjectPool<?> pool = new GenericObjectPool(config, factory);
        pool.init();
        log.info("通用对象池已创建: poolName={}, minIdle={}, maxPoolSize={}, lifo={}",
                config.getPoolName(), config.getMinIdle(), config.getMaxPoolSize(), config.isLifo());
        return pool;
    }
}
