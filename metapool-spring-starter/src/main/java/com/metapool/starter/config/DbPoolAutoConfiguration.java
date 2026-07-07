package com.metapool.starter.config;

import com.metapool.pool.db.ConnectionFactory;
import com.metapool.pool.db.DbConnectionPool;
import com.metapool.pool.db.DbPoolConfig;
import com.metapool.starter.MetaPoolProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.sql.Connection;
import java.sql.DriverManager;

/**
 * 数据库连接池自动装配。
 *
 * <p>当 classpath 中存在 {@code DbConnectionPool} 且配置
 * {@code metapool.db.enabled=true}（默认）时，自动创建数据库连接池。
 *
 * <p>数据库连接信息从 {@code spring.datasource.*} 标准配置读取。
 *
 * @since 0.1.0
 */
@AutoConfiguration
@ConditionalOnClass(DbConnectionPool.class)
@ConditionalOnProperty(prefix = "metapool.db", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DbPoolAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DbPoolAutoConfiguration.class);

    @Bean
    public DbPoolConfig dbPoolConfig(MetaPoolProperties properties) {
        MetaPoolProperties.DbPool props = properties.getDb();
        DbPoolConfig config = new DbPoolConfig();
        config.setPoolName(props.getPoolName());
        config.setMinIdle(props.getMinIdle());
        config.setMaxPoolSize(props.getMaxPoolSize());
        config.setMaxLifetimeMinutes(props.getMaxLifetimeMinutes());
        config.setIdleTimeoutSeconds(props.getIdleTimeoutSeconds());
        config.setConnectionTimeoutSeconds(props.getConnectionTimeoutSeconds());
        config.setValidationTimeoutSeconds(props.getValidationTimeoutSeconds());
        config.setLeakDetectionThresholdSeconds(props.getLeakDetectionThresholdSeconds());
        return config;
    }

    @Bean
    public ConnectionFactory dbConnectionFactory(
            org.springframework.core.env.Environment environment) {
        String url = environment.getProperty("spring.datasource.url");
        String username = environment.getProperty("spring.datasource.username");
        String password = environment.getProperty("spring.datasource.password");

        if (url == null) {
            log.warn("spring.datasource.url 未配置，数据库连接池将无法创建连接");
        }

        return () -> {
            if (url == null) {
                throw new IllegalStateException("spring.datasource.url 未配置");
            }
            Connection conn = DriverManager.getConnection(url,
                    username != null ? username : "",
                    password != null ? password : "");
            log.debug("创建数据库连接: {}", url);
            return conn;
        };
    }

    @Bean
    public DbConnectionPool dbConnectionPool(DbPoolConfig config,
                                              ConnectionFactory connectionFactory) {
        DbConnectionPool pool = new DbConnectionPool(config, connectionFactory);
        pool.init();
        log.info("数据库连接池已创建: poolName={}, minIdle={}, maxPoolSize={}",
                config.getPoolName(), config.getMinIdle(), config.getMaxPoolSize());
        return pool;
    }
}
