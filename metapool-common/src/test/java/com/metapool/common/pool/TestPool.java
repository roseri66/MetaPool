package com.metapool.common.pool;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试用资源池，暴露钩子调用记录供断言验证。
 */
class TestPool extends AbstractResourcePool<TestResource> {

    private final AtomicInteger idCounter = new AtomicInteger(0);
    final Set<TestResource> created = ConcurrentHashMap.newKeySet();
    final Set<TestResource> destroyed = ConcurrentHashMap.newKeySet();
    final Set<TestResource> leakNotified = ConcurrentHashMap.newKeySet();

    /** 可注入的资源验证逻辑，null 表示始终有效 */
    volatile java.util.function.Predicate<TestResource> validator;

    /** 创建资源时模拟延迟（ms），用于测试并发竞争 */
    volatile long createDelayMs;

    TestPool(PoolConfig config) {
        super(config);
    }

    @Override
    protected TestResource createResource() throws Exception {
        if (createDelayMs > 0) {
            Thread.sleep(createDelayMs);
        }
        TestResource resource = new TestResource(idCounter.incrementAndGet());
        created.add(resource);
        return resource;
    }

    @Override
    protected void destroyResource(TestResource resource) {
        resource.markDestroyed();
        destroyed.add(resource);
    }

    @Override
    protected boolean validateResource(TestResource resource) {
        if (resource.isDestroyed()) {
            return false;
        }
        if (validator != null) {
            return validator.test(resource);
        }
        return true;
    }

    @Override
    protected void onResourceAcquired(TestResource resource) {
    }

    @Override
    protected void onResourceReleased(TestResource resource) {
    }

    @Override
    protected void onResourceLeaked(TestResource resource) {
        leakNotified.add(resource);
    }

    int getTotalCreated() {
        return created.size();
    }

    int getTotalDestroyed() {
        return destroyed.size();
    }

    Set<TestResource> getCreated() {
        return Collections.unmodifiableSet(created);
    }
}
