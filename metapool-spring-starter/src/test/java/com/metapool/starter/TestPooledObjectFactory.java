package com.metapool.starter;

import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 供 starter 测试用的对象工厂：验证 YAML 的 {@code factory-class} 反射路径能穿过自动装配跑通。
 *
 * <p>必须是<b>公开类 + 无参构造</b>，这正是 {@code CommonsPool2AdapterFactory} 要求的形状。
 */
public class TestPooledObjectFactory extends BasePooledObjectFactory<Object> {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Override
    public Object create() {
        return "pooled-" + SEQ.incrementAndGet();
    }

    @Override
    public PooledObject<Object> wrap(Object obj) {
        return new DefaultPooledObject<>(obj);
    }
}
