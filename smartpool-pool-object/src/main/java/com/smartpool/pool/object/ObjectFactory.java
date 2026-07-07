package com.smartpool.pool.object;

/**
 * 对象工厂接口，定义对象的创建、销毁和验证逻辑。
 *
 * <p>业务方实现此接口以自定义对象池中管理的对象类型和生命周期。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * ObjectFactory<MyConnection> factory = new ObjectFactory<>() {
 *     public MyConnection create() { return new MyConnection("host", 3306); }
 *     public void destroy(MyConnection c) { c.close(); }
 *     public boolean validate(MyConnection c) { return c.isAlive(); }
 * };
 * GenericObjectPool<MyConnection> pool = new GenericObjectPool<>(config, factory);
 * }</pre>
 *
 * @param <T> 池中管理的对象类型
 * @since 0.1.0
 */
public interface ObjectFactory<T> {

    /**
     * 创建新对象。
     *
     * <p>由池在需要扩容或初始化时调用。实现应返回一个完全初始化的、可用的对象。
     *
     * @return 新创建的对象
     * @throws Exception 创建失败
     */
    T create() throws Exception;

    /**
     * 销毁对象，释放底层资源。
     *
     * <p>在对象被淘汰、空闲超时或池销毁时调用。实现应确保资源完全释放。
     *
     * @param obj 要销毁的对象
     */
    void destroy(T obj);

    /**
     * 验证对象是否仍然有效可用。
     *
     * <p>在对象借出前和归还后调用。默认返回 true。
     * 返回 false 时对象将被销毁并从池中移除。
     *
     * @param obj 待验证的对象
     * @return true 对象有效可用
     */
    default boolean validate(T obj) {
        return true;
    }
}
