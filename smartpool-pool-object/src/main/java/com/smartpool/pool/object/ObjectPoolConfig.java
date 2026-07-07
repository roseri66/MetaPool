package com.smartpool.pool.object;

import com.smartpool.common.pool.PoolConfig;

/**
 * 通用对象池配置，继承 {@link PoolConfig} 基类。
 *
 * @since 0.1.0
 */
public class ObjectPoolConfig extends PoolConfig {

    /** 是否使用 LIFO（后进先出）队列策略，默认 true */
    private boolean lifo = true;

    public boolean isLifo() {
        return lifo;
    }

    public void setLifo(boolean lifo) {
        this.lifo = lifo;
    }
}
