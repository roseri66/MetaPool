package com.metapool.core;

import com.metapool.common.manager.ResourceManager;

import java.time.Duration;

/**
 * MetaPool 控制面的入口门面 —— 面向编程式（非 Spring）用户的静态工厂。
 *
 * <pre>{@code
 * ResourceManager metaPool = MetaPool.create();
 * metaPool.register(HikariAdapter.from(cfg).named("main").build());
 * metaPool.register(Bucket4jAdapter.builder().named("order-api").limitForPeriod(100).build());
 * metaPool.start();
 * // ... 业务使用底层原生 API ...
 * metaPool.close();   // 逆序优雅停机
 * }</pre>
 *
 * @since 2.0.0
 */
public final class MetaPool {

    private MetaPool() {
    }

    /** 新建控制面，默认优雅停机等待 30s。 */
    public static ResourceManager create() {
        return new DefaultResourceManager();
    }

    /** 新建控制面，指定优雅停机等待时长。 */
    public static ResourceManager create(Duration shutdownGraceful) {
        return new DefaultResourceManager(shutdownGraceful);
    }
}
