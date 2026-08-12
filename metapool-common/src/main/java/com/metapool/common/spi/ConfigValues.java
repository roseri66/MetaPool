package com.metapool.common.spi;

import com.metapool.common.exception.MetaPoolConfigException;

import java.time.Duration;

/**
 * {@link ResourceAdapterFactory} 实现共用的配置值解析。
 *
 * <h3>为什么它存在</h3>
 * <p>配置来自 YAML，值可能是数字也可能是字符串（{@code 500} / {@code "500ms"} / {@code "1s"}）。
 * 每个适配器工厂都要把它们解析成强类型，并且必须遵守 RULES §3.2：
 * <b>非法配置要报 {@link MetaPoolConfigException}，不能漏出裸的
 * {@code NumberFormatException} / {@code DateTimeParseException}</b> ——
 * 那是底层解析器的实现细节，不该成为使用方看到的错误类型。
 *
 * <p>这段逻辑此前在 bucket4j / jdk-executor / commons-pool2 / lettuce 四个工厂里各有一份
 * <b>完全相同的实现</b>。当初立的约定是「第三个 adapter 再需要就抽取」，抽到这里兑现它。
 *
 * <h3>为什么放在 {@code spi} 包</h3>
 * <p>它服务的对象是 {@link ResourceAdapterFactory} 的实现方（含第三方 adapter），
 * 与 {@link ResourceDefinition} 是同一层的东西：都属于「怎么把声明式配置变成资源」这件事。
 *
 * @since 2.3.0
 */
public final class ConfigValues {

    private ConfigValues() {
    }

    /**
     * 把配置值解析成 {@link Duration}。
     *
     * <p>接受的写法：
     * <table border="1">
     *   <caption>支持的 duration 写法</caption>
     *   <tr><th>写法</th><th>示例</th><th>含义</th></tr>
     *   <tr><td>数字（{@link Number}）</td><td>{@code 500}</td><td>毫秒</td></tr>
     *   <tr><td>纯数字字符串</td><td>{@code "500"}</td><td>毫秒</td></tr>
     *   <tr><td>{@code ms} 后缀</td><td>{@code "500ms"}</td><td>毫秒</td></tr>
     *   <tr><td>{@code s} 后缀</td><td>{@code "30s"}</td><td>秒</td></tr>
     *   <tr><td>{@code m} 后缀</td><td>{@code "2m"}</td><td>分钟</td></tr>
     *   <tr><td>ISO-8601</td><td>{@code "PT1M30S"}</td><td>交给 {@link Duration#parse}</td></tr>
     * </table>
     *
     * <p><b>刻意不校验正负</b>：负值在某些底层库里有确定含义
     * （如 Commons Pool2 约定负的 {@code max-wait} 表示无限等待），
     * 是否允许负数属于各适配器自己的语义，由调用方在拿到 {@code Duration} 之后判断。
     * <b>解析器只管「能不能读懂」，不管「合不合理」。</b>
     *
     * @param key 配置项名，仅用于拼错误消息（如 {@code "keep-alive"}），非空
     * @param raw 原始配置值
     * @return 解析出的时长
     * @throws MetaPoolConfigException 无法解析；消息里带上 {@code key} 与原值，便于定位
     */
    public static Duration duration(String key, Object raw) {
        if (raw instanceof Number n) {
            return Duration.ofMillis(n.longValue());
        }
        String s = String.valueOf(raw).trim();
        try {
            if (s.startsWith("PT") || s.startsWith("pt")) {
                return Duration.parse(s);
            }
            if (s.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(s.substring(0, s.length() - 2).trim()));
            }
            if (s.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(s.substring(0, s.length() - 1).trim()));
            }
            if (s.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(s.substring(0, s.length() - 1).trim()));
            }
            return Duration.ofMillis(Long.parseLong(s));
        } catch (RuntimeException e) {
            throw new MetaPoolConfigException("invalid " + key + " '" + s + "'", e);
        }
    }
}
