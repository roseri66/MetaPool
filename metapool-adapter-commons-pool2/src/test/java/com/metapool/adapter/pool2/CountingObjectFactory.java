package com.metapool.adapter.pool2;

import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试用的内存对象工厂：造一个自增编号的字符串，并记录创建/销毁次数。
 *
 * <p><b>必须有无参公开构造</b>——本类同时被用来验证 YAML 的 {@code factory-class} 反射路径。
 * 计数器做成 static，是为了让反射实例化出来的那个工厂也能被测试观察到。
 */
public class CountingObjectFactory extends BasePooledObjectFactory<Object> {

    static final AtomicInteger CREATED = new AtomicInteger();
    static final AtomicInteger DESTROYED = new AtomicInteger();

    static void reset() {
        CREATED.set(0);
        DESTROYED.set(0);
    }

    @Override
    public Object create() {
        return "obj-" + CREATED.incrementAndGet();
    }

    @Override
    public PooledObject<Object> wrap(Object obj) {
        return new DefaultPooledObject<>(obj);
    }

    @Override
    public void destroyObject(PooledObject<Object> p) {
        DESTROYED.incrementAndGet();
    }
}
