package com.metapool.common.stats;

import java.util.Map;
import java.util.Set;

/**
 * 动态调参结果，不可变。由 {@link com.metapool.common.resource.Tunable#apply(Map)} 返回。
 *
 * @param success  是否全部成功（无任何拒绝项）
 * @param applied  成功生效的参数 key
 * @param rejected 被拒绝的参数：key → 原因（如"不在白名单""值非法"）
 * @since 2.0.0
 */
public record TuneResult(boolean success, Set<String> applied, Map<String, String> rejected) {

    public TuneResult {
        applied = applied == null ? Set.of() : Set.copyOf(applied);
        rejected = rejected == null ? Map.of() : Map.copyOf(rejected);
    }

    /** 全部生效。 */
    public static TuneResult ok(Set<String> applied) {
        return new TuneResult(true, applied, Map.of());
    }

    /** 存在拒绝项。 */
    public static TuneResult partial(Set<String> applied, Map<String, String> rejected) {
        return new TuneResult(rejected.isEmpty(), applied, rejected);
    }
}
