package com.metapool.pool.thread;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.TimeUnit;

/**
 * SmartThreadPoolExecutor vs JDK ThreadPoolExecutor 吞吐量基准测试。
 *
 * <p>验收标准：SmartThreadPoolExecutor 吞吐量 ≥ JDK ThreadPoolExecutor 的 95%。
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
public class ThreadPoolBenchmark {

    private static final Runnable NOOP_TASK = () -> { /* no-op */ };

    private SmartThreadPoolExecutor smartPool;
    private java.util.concurrent.ThreadPoolExecutor jdkPool;
    private java.util.concurrent.CountDownLatch latch;

    @Setup(Level.Trial)
    public void setup() {
        ThreadPoolConfig config = new ThreadPoolConfig();
        config.setCorePoolSize(8);
        config.setMaxPoolSize(32);
        config.setQueueCapacity(50000);
        config.setKeepAliveSeconds(60);
        config.setRejectedPolicy(RejectedPolicyEnum.CALLER_RUNS);
        smartPool = new SmartThreadPoolExecutor(config);

        jdkPool = new java.util.concurrent.ThreadPoolExecutor(
                8, 32, 60, TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(50000),
                new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
    }

    @TearDown(Level.Trial)
    public void teardown() {
        smartPool.shutdown();
        jdkPool.shutdown();
    }

    @Benchmark
    public void smartPool_execute() {
        smartPool.execute(NOOP_TASK);
    }

    @Benchmark
    public void jdkPool_execute() {
        jdkPool.execute(NOOP_TASK);
    }

    // ── 多线程竞争场景 ──

    @Benchmark
    @Threads(4)
    public void smartPool_execute_4threads() {
        smartPool.execute(NOOP_TASK);
    }

    @Benchmark
    @Threads(4)
    public void jdkPool_execute_4threads() {
        jdkPool.execute(NOOP_TASK);
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(ThreadPoolBenchmark.class.getSimpleName())
                .warmupIterations(3)
                .measurementIterations(5)
                .forks(1)
                .build();
        new Runner(opt).run();
    }
}
