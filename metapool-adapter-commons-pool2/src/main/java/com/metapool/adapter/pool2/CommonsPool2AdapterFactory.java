package com.metapool.adapter.pool2;

import com.metapool.common.exception.MetaPoolConfigException;
import com.metapool.common.resource.ManagedResource;
import com.metapool.common.resource.ResourceTypes;
import com.metapool.common.spi.ConfigValues;
import com.metapool.common.spi.ResourceAdapterFactory;
import com.metapool.common.spi.ResourceDefinition;
import org.apache.commons.pool2.PooledObjectFactory;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import java.lang.reflect.Constructor;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * {@code object} 类型的适配器工厂：从 {@link ResourceDefinition} 构建 {@link CommonsPool2Adapter}。
 *
 * <p>通过 JDK {@link java.util.ServiceLoader} 注册于
 * {@code META-INF/services/com.metapool.common.spi.ResourceAdapterFactory}。
 *
 * <p>识别的配置项：
 * <table border="1">
 *   <caption>object 配置项</caption>
 *   <tr><th>key</th><th>默认</th><th>说明</th></tr>
 *   <tr><td>{@code factory-class}</td><td><b>必填</b></td>
 *       <td>{@link PooledObjectFactory} 实现类的全限定名，<b>必须有无参构造</b></td></tr>
 *   <tr><td>{@code max-total}</td><td>{@code 8}</td><td>最大对象数；<b>负数 = 无上限</b></td></tr>
 *   <tr><td>{@code max-idle}</td><td>{@code 8}</td><td>最大空闲数</td></tr>
 *   <tr><td>{@code min-idle}</td><td>{@code 0}</td><td>最小空闲数</td></tr>
 *   <tr><td>{@code max-wait}</td><td>{@code -1ms}（无限等待）</td>
 *       <td>borrow 的默认等待上限；如 {@code 3s} / {@code 500ms} / {@code PT2S} / 纯毫秒数</td></tr>
 * </table>
 *
 * <h3>为什么这里要用反射</h3>
 * <p>Commons Pool2 <b>不知道怎么造 {@code T}</b>，必须由使用方提供 {@link PooledObjectFactory}，
 * 而工厂对象没法写进 YAML。若不提供 {@code factory-class} 这条路，本方法就只能一律抛异常，
 * 该适配器也就无法通过声明式配置接入 —— SPI 对称性（RULES §2.8）就破了。
 *
 * <p>反射只用于<b>按名实例化一个类</b>，不涉及改字段（RULES 明确禁止的是「任意反射改字段」）。
 * 三项校验全部在构建期完成，fail-fast：类存在、确实是 {@code PooledObjectFactory}、有无参构造。
 *
 * @since 2.1.0
 */
public final class CommonsPool2AdapterFactory implements ResourceAdapterFactory {

    static final String KEY_FACTORY_CLASS = "factory-class";

    @Override
    public String supportedType() {
        return ResourceTypes.OBJECT;
    }

    @Override
    public ManagedResource create(ResourceDefinition definition) {
        if (!supportedType().equals(definition.type())) {
            throw new MetaPoolConfigException(
                    "CommonsPool2AdapterFactory cannot handle type '" + definition.type() + "'");
        }
        Map<String, Object> props = definition.properties();
        String name = definition.name();

        Object factoryClassRaw = props.get(KEY_FACTORY_CLASS);
        if (factoryClassRaw == null || String.valueOf(factoryClassRaw).isBlank()) {
            throw new MetaPoolConfigException("object pool '" + name + "' requires '" + KEY_FACTORY_CLASS
                    + "' (a PooledObjectFactory implementation with a no-arg constructor) — "
                    + "Commons Pool2 cannot create objects on its own; "
                    + "alternatively register the pool programmatically via CommonsPool2Adapter.builder()");
        }

        Set<String> tunable = definition.tunableKeys().isEmpty()
                ? CommonsPool2Adapter.SUPPORTED_TUNABLE_KEYS
                : definition.tunableKeys();

        // 走 YAML 的实例拿不到 T（create 返回非泛型的 ManagedResource），退化为 Object
        CommonsPool2Adapter.Builder<Object> builder = CommonsPool2Adapter.<Object>builder()
                .named(name)
                .factory(instantiateFactory(name, String.valueOf(factoryClassRaw).trim()))
                .maxTotal(parseInt(name, CommonsPool2Adapter.KEY_MAX_TOTAL,
                        props.get(CommonsPool2Adapter.KEY_MAX_TOTAL),
                        GenericObjectPoolConfig.DEFAULT_MAX_TOTAL))
                .maxIdle(parseInt(name, CommonsPool2Adapter.KEY_MAX_IDLE,
                        props.get(CommonsPool2Adapter.KEY_MAX_IDLE),
                        GenericObjectPoolConfig.DEFAULT_MAX_IDLE))
                .minIdle(parseInt(name, CommonsPool2Adapter.KEY_MIN_IDLE,
                        props.get(CommonsPool2Adapter.KEY_MIN_IDLE),
                        GenericObjectPoolConfig.DEFAULT_MIN_IDLE))
                .tunable(tunable);

        Object maxWaitRaw = props.get(CommonsPool2Adapter.KEY_MAX_WAIT);
        builder.maxWait(maxWaitRaw == null
                ? GenericObjectPoolConfig.DEFAULT_MAX_WAIT
                : ConfigValues.duration("max-wait", maxWaitRaw));

        return builder.build();
    }

    /**
     * 按名实例化 {@link PooledObjectFactory}，三项校验全部 fail-fast，且错误消息说清怎么改。
     *
     * <p>用 {@code @SuppressWarnings("unchecked")}：类型参数已被擦除，运行期只能确认它是
     * {@code PooledObjectFactory}，确认不了 {@code T}。这正是 YAML 路径退化为
     * {@code CommonsPool2Adapter<Object>} 的原因。
     */
    @SuppressWarnings("unchecked")
    private static PooledObjectFactory<Object> instantiateFactory(String name, String className) {
        Class<?> clazz;
        try {
            clazz = Class.forName(className, true, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException e) {
            throw new MetaPoolConfigException("object pool '" + name + "': factory-class '" + className
                    + "' not found on classpath", e);
        }
        if (!PooledObjectFactory.class.isAssignableFrom(clazz)) {
            throw new MetaPoolConfigException("object pool '" + name + "': factory-class '" + className
                    + "' does not implement " + PooledObjectFactory.class.getName());
        }
        Constructor<?> ctor;
        try {
            ctor = clazz.getDeclaredConstructor();
        } catch (NoSuchMethodException e) {
            throw new MetaPoolConfigException("object pool '" + name + "': factory-class '" + className
                    + "' must have a no-arg constructor to be usable from YAML "
                    + "(otherwise register the pool programmatically)", e);
        }
        try {
            ctor.setAccessible(true);
            return (PooledObjectFactory<Object>) ctor.newInstance();
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new MetaPoolConfigException("object pool '" + name + "': failed to instantiate factory-class '"
                    + className + "'", e);
        }
    }

    /** 非法数值必须报 {@link MetaPoolConfigException}，不能漏出裸 NumberFormatException（RULES §3.2）。 */
    private static int parseInt(String name, String key, Object raw, int defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        if (raw instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new MetaPoolConfigException("object pool '" + name + "' has invalid '" + key + "': " + raw, e);
        }
    }

}
