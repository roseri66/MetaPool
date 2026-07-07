package com.metapool.common.pool;

/**
 * 测试用资源，追踪创建/销毁生命周期。
 */
final class TestResource {

    private final int id;
    private volatile boolean destroyed;

    TestResource(int id) {
        this.id = id;
    }

    int getId() {
        return id;
    }

    boolean isDestroyed() {
        return destroyed;
    }

    void markDestroyed() {
        this.destroyed = true;
    }

    @Override
    public String toString() {
        return "TestResource{" + id + "}";
    }
}
