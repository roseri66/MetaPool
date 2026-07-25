package com.metapool.common.resource;

import com.metapool.common.stats.TuneResult;

import java.util.Map;
import java.util.Set;

/**
 * 运行时动态调参能力（🎯 头牌能力之一）。<b>可选能力接口</b>：无可调参数的资源不实现它。
 *
 * <p>控制面「不停机治理」的杀手锏——运行期把 {@code maximum-pool-size} 从 20 调到 40 而不重启。
 * 底层库通常已支持（HikariCP 的 {@code HikariConfigMXBean}、Bucket4j 重建 bucket），本接口把它们统一成
 * 一个 {@code apply(patch)} 门面，供 Actuator 端点 / 控制面调用。
 *
 * <h3>安全边界</h3>
 * <p>仅允许调整 {@link #tunableKeys()} 白名单内的参数；其余一律拒绝并记入 {@link TuneResult#rejected()}。
 * 绝不允许任意反射改字段。实现方应记录审计日志。
 *
 * @since 2.0.0
 */
public interface Tunable {

    /**
     * 允许热调的参数 key 白名单（来自配置的 {@code tunable} 声明）。
     *
     * @return 不可变集合，可能为空
     */
    Set<String> tunableKeys();

    /**
     * 应用一批参数变更。
     *
     * <p>多线程安全。仅接受白名单内且校验通过的 key；部分成功时 {@link TuneResult#success()} 为 false，
     * 被拒项及原因见 {@link TuneResult#rejected()}。
     *
     * @param patch 参数名 → 新值
     * @return 调参结果，绝不为 null
     */
    TuneResult apply(Map<String, Object> patch);
}
