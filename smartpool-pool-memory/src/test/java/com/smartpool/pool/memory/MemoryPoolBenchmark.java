package com.smartpool.pool.memory;

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
 * MemoryPool allocate/deallocate 吞吐量基准测试。
 *
 * <p>测量内存页借还的性能。
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
public class MemoryPoolBenchmark {

    private MemoryPool pool;

    @Setup(Level.Trial)
    public void setup() {
        MemoryPoolConfig config = new MemoryPoolConfig();
        config.setMaxPoolSize(64);
        config.setMinIdle(8);
        config.setPageSizeKB(4);
        config.setMaxDirectMemoryMB(64);
        pool = new MemoryPool(config);
        pool.init();
    }

    @TearDown(Level.Trial)
    public void teardown() {
        pool.destroy();
    }

    @Benchmark
    public MemoryPage allocateDeallocate_heap() {
        try {
            MemoryPage page = pool.acquire();
            pool.release(page);
            return page;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    @Benchmark
    @Threads(4)
    public MemoryPage allocateDeallocate_heap_4threads() {
        try {
            MemoryPage page = pool.acquire();
            pool.release(page);
            return page;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(MemoryPoolBenchmark.class.getSimpleName())
                .warmupIterations(3)
                .measurementIterations(5)
                .forks(1)
                .build();
        new Runner(opt).run();
    }
}
