package com.metapool.benchmark;

import com.metapool.adapter.bucket4j.Bucket4jAdapter;
import com.metapool.adapter.hikari.HikariAdapter;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.sql.Connection;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 度量 MetaPool 治理层<b>相对裸用底层库</b>的额外开销。
 *
 * <p>成对对比：
 * <ul>
 *   <li>{@code rawDatasource} 裸 HikariCP getConnection/close &nbsp;vs&nbsp; {@code governedDatasource}
 *       经 {@link HikariAdapter} 的 borrow/release</li>
 *   <li>{@code rawRateLimiter} 裸 Bucket4j tryConsume &nbsp;vs&nbsp; {@code governedRateLimiter}
 *       经 {@link Bucket4jAdapter} 的 tryAcquire</li>
 * </ul>
 *
 * <p>两者之差即「治理开销」。目标：≤ 5%（对齐 BRD 硬指标）。
 *
 * <p>运行：{@code mvn -pl metapool-benchmark -am package -DskipTests && java -jar
 * metapool-benchmark/target/benchmarks.jar}
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class GovernanceOverheadBenchmark {

    private HikariDataSource rawDs;
    private HikariAdapter governedDs;
    private Bucket rawBucket;
    private Bucket4jAdapter governedRl;

    @Setup
    public void setup() {
        rawDs = new HikariDataSource(h2Config("bench_raw"));

        governedDs = HikariAdapter.from(h2Config("bench_gov")).named("bench-gov").build();
        governedDs.start();

        // 大但合法（≤ 1 token/ns）且基准期内不会耗尽的速率，确保测的是调用开销而非限流触发
        long permits = 500_000_000L;
        Bandwidth bw = Bandwidth.builder()
                .capacity(permits).refillGreedy(permits, Duration.ofSeconds(1)).build();
        rawBucket = Bucket.builder().addLimit(bw).build();

        governedRl = Bucket4jAdapter.builder()
                .named("bench-rl").limitForPeriod(permits)
                .refillPeriod(Duration.ofSeconds(1)).build();
        governedRl.start();
    }

    @TearDown
    public void tearDown() {
        governedDs.stop(Duration.ZERO);
        governedRl.stop(Duration.ZERO);
        rawDs.close();
    }

    private HikariConfig h2Config(String db) {
        HikariConfig cfg = new HikariConfig();
        cfg.setDriverClassName("org.h2.Driver");   // shaded uber-jar 里显式指定，避免 DriverManager 服务发现问题
        cfg.setJdbcUrl("jdbc:h2:mem:" + db + ";DB_CLOSE_DELAY=-1");
        cfg.setUsername("sa");
        cfg.setPassword("");
        cfg.setMaximumPoolSize(8);
        return cfg;
    }

    // ---- 数据源：裸 vs 治理 ----

    @Benchmark
    public void rawDatasource(Blackhole bh) throws Exception {
        Connection c = rawDs.getConnection();
        bh.consume(c);
        c.close();
    }

    @Benchmark
    public void governedDatasource(Blackhole bh) throws Exception {
        Connection c = governedDs.borrow();
        bh.consume(c);
        governedDs.release(c);
    }

    // ---- 限流：裸 vs 治理 ----

    @Benchmark
    public boolean rawRateLimiter() {
        return rawBucket.tryConsume(1);
    }

    @Benchmark
    public boolean governedRateLimiter() {
        return governedRl.tryAcquire(1);
    }
}
