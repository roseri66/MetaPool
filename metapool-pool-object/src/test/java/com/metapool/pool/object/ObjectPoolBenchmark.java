package com.metapool.pool.object;

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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GenericObjectPool borrow/return 吞吐量基准测试。
 *
 * <p>覆盖 FIFO / LIFO 两种队列策略，以及单线程和多线程竞争场景。
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
public class ObjectPoolBenchmark {

    private static final AtomicInteger OBJECT_ID = new AtomicInteger(0);

    private GenericObjectPool<String> fifoPool;
    private GenericObjectPool<String> lifoPool;

    @Setup(Level.Trial)
    public void setup() {
        ObjectPoolConfig fifoConfig = new ObjectPoolConfig();
        fifoConfig.setMaxPoolSize(64);
        fifoConfig.setMinIdle(8);
        fifoConfig.setLifo(false);
        fifoPool = createPool(fifoConfig);
        fifoPool.init();

        ObjectPoolConfig lifoConfig = new ObjectPoolConfig();
        lifoConfig.setMaxPoolSize(64);
        lifoConfig.setMinIdle(8);
        lifoConfig.setLifo(true);
        lifoPool = createPool(lifoConfig);
        lifoPool.init();
    }

    @TearDown(Level.Trial)
    public void teardown() {
        fifoPool.destroy();
        lifoPool.destroy();
    }

    private static GenericObjectPool<String> createPool(ObjectPoolConfig config) {
        return new GenericObjectPool<>(config, new ObjectFactory<>() {
            @Override
            public String create() {
                return "obj-" + OBJECT_ID.incrementAndGet();
            }

            @Override
            public void destroy(String obj) {
                // no-op
            }

            @Override
            public boolean validate(String obj) {
                return true;
            }
        });
    }

    @Benchmark
    public String fifo_borrowReturn() {
        try {
            String obj = fifoPool.acquire();
            fifoPool.release(obj);
            return obj;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    @Benchmark
    public String lifo_borrowReturn() {
        try {
            String obj = lifoPool.acquire();
            lifoPool.release(obj);
            return obj;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    @Benchmark
    @Threads(4)
    public String fifo_borrowReturn_4threads() {
        try {
            String obj = fifoPool.acquire();
            fifoPool.release(obj);
            return obj;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    @Benchmark
    @Threads(4)
    public String lifo_borrowReturn_4threads() {
        try {
            String obj = lifoPool.acquire();
            lifoPool.release(obj);
            return obj;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(ObjectPoolBenchmark.class.getSimpleName())
                .warmupIterations(3)
                .measurementIterations(5)
                .forks(1)
                .build();
        new Runner(opt).run();
    }
}
