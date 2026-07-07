package com.smartpool.pool.rate.limit;

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
 * 令牌桶限流器 tryAcquire 吞吐量基准测试。
 *
 * <p>验收标准：限流精度偏差 ≤ 5%。
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
public class RateLimiterBenchmark {

    /** 高容量令牌桶，避免令牌耗尽影响吞吐量测量 */
    private TokenBucketRateLimiter fastLimiter;

    /** 低容量令牌桶，测试限流精度 */
    private TokenBucketRateLimiter slowLimiter;

    @Setup(Level.Trial)
    public void setup() {
        RateLimiterConfig fastConfig = new RateLimiterConfig();
        fastConfig.setPermitsPerSecond(1_000_000);  // 100 万 QPS，基本不限流
        fastLimiter = new TokenBucketRateLimiter(fastConfig);
        fastLimiter.init();

        RateLimiterConfig slowConfig = new RateLimiterConfig();
        slowConfig.setPermitsPerSecond(10_000);     // 1 万 QPS
        slowLimiter = new TokenBucketRateLimiter(slowConfig);
        slowLimiter.init();
    }

    @TearDown(Level.Trial)
    public void teardown() {
        fastLimiter.destroy();
        slowLimiter.destroy();
    }

    /** 单线程不限流场景 — 测量纯同步开销 */
    @Benchmark
    public boolean fast_tryAcquire() {
        return fastLimiter.tryAcquire();
    }

    /** 4 线程不限流竞争场景 — 测量 synchronized 竞争开销 */
    @Benchmark
    @Threads(4)
    public boolean fast_tryAcquire_4threads() {
        return fastLimiter.tryAcquire();
    }

    /** 1 万 QPS 限流场景 */
    @Benchmark
    public boolean slow_tryAcquire() {
        return slowLimiter.tryAcquire();
    }

    /** 4 线程竞争 1 万 QPS */
    @Benchmark
    @Threads(4)
    public boolean slow_tryAcquire_4threads() {
        return slowLimiter.tryAcquire();
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(RateLimiterBenchmark.class.getSimpleName())
                .warmupIterations(3)
                .measurementIterations(5)
                .forks(1)
                .build();
        new Runner(opt).run();
    }
}
