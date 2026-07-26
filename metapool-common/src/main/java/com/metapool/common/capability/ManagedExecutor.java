package com.metapool.common.capability;

import com.metapool.common.stats.ExecutorStats;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

/**
 * 线程池能力 —— <b>仅执行器类资源实现</b>（如 JDK {@code ThreadPoolExecutor} 适配器）。
 *
 * <h3>线程池不是池</h3>
 * <p>本接口<b>不实现</b> {@link Pool}：你不「借出一个线程、用完归还」，你是<b>提交任务</b>。
 * 1.0 曾让线程池实现 {@code Pool} 语义的 {@code acquire()} 并直接
 * {@code throw new UnsupportedOperationException()}——这是本项目最重要的一条反面教材
 * （见 {@code docs/RULES.md} 台账 P-07）。
 *
 * <h3>为什么继承 Executor，却刻意不继承 ExecutorService</h3>
 * <p>继承 {@link Executor}（只有一个 {@code execute(Runnable)}）是为了<b>零成本互操作</b>：
 * 可以直接传给 {@code CompletableFuture.supplyAsync(sup, executor)}、Spring 的 {@code @Async} 等。
 *
 * <p>而 {@link ExecutorService} 带着 {@code shutdown()} / {@code shutdownNow()} /
 * {@code awaitTermination()}。把它们暴露给业务代码，就等于开了<b>第二个停机入口</b>——
 * 控制面以为资源在跑，实际已被业务代码关停，治理面立刻出现一个洞。
 * <b>生命周期只有一个入口</b>：{@link com.metapool.common.resource.ManagedLifecycle#stop(java.time.Duration)}。
 *
 * <h3>饱和时抛什么</h3>
 * <p>线程池饱和且拒绝策略为 abort 时，<b>透传</b> JDK 的
 * {@link java.util.concurrent.RejectedExecutionException}，不包装成 {@code MetaPoolException}。
 * 理由：该异常类型本身就是生态契约的一部分（{@code CompletableFuture}、Spring {@code @Async}
 * 都按它做处理），<b>不应在接口层发明第二套已有既定含义的饱和语义</b>。
 * 这是 RULES §3.2 的一条明示例外；MetaPool <b>自己</b>产生的错误（未启动等）仍是
 * {@code MetaPoolException} + {@code ErrorCode}。
 *
 * <h3>线程安全</h3>
 * <p>所有方法必须支持多线程并发调用。
 *
 * @since 2.1.0
 */
public interface ManagedExecutor extends Executor {

    /**
     * 提交一个无返回值任务。
     *
     * @param task 待执行任务，非空
     * @throws java.util.concurrent.RejectedExecutionException 线程池饱和且拒绝策略为 abort（透传，见类注释）
     * @throws com.metapool.common.exception.MetaPoolException  资源未启动或已停机
     */
    @Override
    void execute(Runnable task);

    /**
     * 提交一个有返回值任务。
     *
     * <p>返回 {@link CompletableFuture} 而非 {@code Future}：前者是后者的子类型，
     * 想当 {@code Future} 用完全兼容，想链式组合也支持——严格更强，无损失。
     *
     * @param task 待执行任务，非空
     * @param <T>  返回值类型
     * @return 任务的异步结果；任务抛出的异常以 {@code CompletableFuture} 的异常完成形式传递
     * @throws java.util.concurrent.RejectedExecutionException 线程池饱和且拒绝策略为 abort（透传）
     * @throws com.metapool.common.exception.MetaPoolException  资源未启动或已停机
     */
    <T> CompletableFuture<T> submit(Callable<T> task);

    /**
     * 取底层原生 {@link ExecutorService}，用于本接口未覆盖的高级用法
     * （{@code invokeAll} / {@code invokeAny} / 自定义 {@code ThreadFactory} 等）。
     *
     * <p>与 {@code HikariAdapter.getConnection()} 同理：MetaPool 统一的是治理，不是用法，
     * 因此始终保留通往底层原生 API 的口子。
     *
     * <p>⚠️ <b>契约</b>：调用方<b>不得</b>在返回对象上调用 {@code shutdown()} / {@code shutdownNow()}。
     * 停机由控制面负责；擅自关停会使控制面状态与实际不符（这正是本接口不继承
     * {@code ExecutorService} 的原因）。
     *
     * @return 底层执行器，绝不为 null；资源未启动时抛 {@code MetaPoolException}
     */
    ExecutorService unwrap();

    /** 当前执行器统计快照。 */
    ExecutorStats executorStats();
}
