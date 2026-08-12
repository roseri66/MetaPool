package com.metapool.adapter.executor;

import com.metapool.common.exception.MetaPoolConfigException;

import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.stream.Collectors;

/**
 * 线程池饱和拒绝策略 —— JDK 四种内置策略的可配置化映射。
 *
 * <p>MetaPool 不发明第五种策略，只把 JDK 的四个内置 handler 映射成 kebab-case 配置值
 * （RULES §2.3「参数直通底层库原生命名」的一次折中：JDK 侧的"原生命名"是四个内部类名
 * {@code AbortPolicy} / {@code CallerRunsPolicy} / ...，无法直接写进 YAML，
 * 故取其语义名的 kebab 形式，一一对应、不增不减）。
 *
 * @since 2.1.0
 */
public enum RejectionPolicy {

    /**
     * 抛出 {@link java.util.concurrent.RejectedExecutionException}（JDK 默认）。
     *
     * <p>该异常<b>原样透传</b>给调用方，不包装成 {@code MetaPoolException} ——
     * 它本身就是生态契约的一部分（{@code CompletableFuture}、Spring {@code @Async} 都按它做处理），
     * 见 RULES §3.2 的明示例外。
     */
    ABORT("abort", new ThreadPoolExecutor.AbortPolicy()),

    /** 由提交任务的线程自己执行该任务，形成天然背压（提交方被拖慢 → 上游自动降速）。 */
    CALLER_RUNS("caller-runs", new ThreadPoolExecutor.CallerRunsPolicy()),

    /** 静默丢弃新任务。⚠️ 任务无声消失，仅适用于可丢弃的旁路任务。 */
    DISCARD("discard", new ThreadPoolExecutor.DiscardPolicy()),

    /** 丢弃队列中最老的任务，再重试提交新任务。⚠️ 同样会丢任务。 */
    DISCARD_OLDEST("discard-oldest", new ThreadPoolExecutor.DiscardOldestPolicy());

    private final String configValue;
    private final RejectedExecutionHandler jdkHandler;

    RejectionPolicy(String configValue, RejectedExecutionHandler jdkHandler) {
        this.configValue = configValue;
        this.jdkHandler = jdkHandler;
    }

    /** 配置文件中书写的值（kebab-case）。 */
    public String configValue() {
        return configValue;
    }

    /** 对应的 JDK 内置 handler。 */
    RejectedExecutionHandler jdkHandler() {
        return jdkHandler;
    }

    /**
     * 解析配置值。无法识别时 fail-fast（RULES §3.3），不做「猜一个默认值」的兜底 ——
     * 拼错的策略名若被静默降级成 abort，线上表现是任务莫名被拒，极难排查。
     *
     * @param raw 配置里的原始值，大小写与下划线/连字符不敏感
     * @throws MetaPoolConfigException 无法识别
     */
    static RejectionPolicy from(Object raw) {
        String s = String.valueOf(raw).trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return Arrays.stream(values())
                .filter(p -> p.configValue.equals(s))
                .findFirst()
                .orElseThrow(() -> new MetaPoolConfigException("invalid rejection-policy '" + raw + "'; supported: "
                        + Arrays.stream(values()).map(RejectionPolicy::configValue).collect(Collectors.joining(", "))));
    }
}
