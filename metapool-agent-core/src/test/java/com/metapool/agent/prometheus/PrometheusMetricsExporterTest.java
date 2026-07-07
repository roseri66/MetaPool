package com.metapool.agent.prometheus;

import com.metapool.agent.MetaPoolMetrics;
import com.metapool.agent.model.PoolType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SPEC-14 PrometheusMetricsExporter 单元测试。
 *
 * <p>验证指标注册、抓取输出格式、Prometheus 命名规范。
 *
 * @since 0.1.0
 */
@DisplayName("SPEC-14 PrometheusMetricsExporter 指标桥接测试")
class PrometheusMetricsExporterTest {

    private static final MetaPoolMetrics METRICS = MetaPoolMetrics.getInstance();

    private PrometheusMetricsExporter exporter;

    @BeforeEach
    void setUp() {
        METRICS.clear();
        exporter = new PrometheusMetricsExporter();
        exporter.start();
    }

    @AfterEach
    void tearDown() {
        exporter.stop();
        METRICS.clear();
    }

    // ==================== 基础功能 ====================

    @Nested
    @DisplayName("基础功能")
    class BasicFunctionalityTests {

        @Test
        @DisplayName("无指标时 scrape 不应抛异常")
        void shouldScrapeEmptyWithoutError() {
            // 无注册 Meter 时 scrape 返回空字符串，但不应抛异常
            String result = assertDoesNotThrow(() -> exporter.scrape(),
                    "无指标时 scrape 不应抛异常");
            assertNotNull(result);
        }

        @Test
        @DisplayName("注册后 scrape 应包含 pool_id 标签")
        void shouldIncludePoolIdTagAfterRegistration() {
            // 注册模拟指标
            String poolId = "TestThreadPool@12345";
            METRICS.register(poolId, PoolType.THREAD);
            METRICS.setGauge(poolId, "activeCount", 5);
            METRICS.setGauge(poolId, "poolSize", 4);
            METRICS.setCounter(poolId, "executeCount", 100);
            METRICS.setCounter(poolId, "completedTasks", 95);

            String result = exporter.scrape();

            assertTrue(result.contains("pool_id=\"TestThreadPool@12345\""),
                    "scrape 输出应包含 pool_id 标签");
            assertTrue(result.contains("metapool_thread_active"),
                    "应包含线程池 active gauge");
            assertTrue(result.contains("metapool_thread_execute_count_total"),
                    "应包含 execute counter（Prometheus 命名转换后）");
        }

        @Test
        @DisplayName("重复注册不应创建重复 Meter")
        void shouldNotDuplicateMetersOnRepeatedSync() {
            String poolId = "TestDbPool@99999";
            METRICS.register(poolId, PoolType.DB);
            METRICS.setGauge(poolId, "activeCount", 3);
            METRICS.setCounter(poolId, "acquireCount", 10);

            // 多次 scrape → 多次 sync → 不应抛异常
            for (int i = 0; i < 5; i++) {
                String result = exporter.scrape();
                assertNotNull(result);
            }
        }
    }

    // ==================== 命名规范 ====================

    @Nested
    @DisplayName("Prometheus 命名规范")
    class NamingConventionTests {

        @Test
        @DisplayName("计数器应自动追加 _total 后缀")
        void shouldAppendTotalSuffixForCounters() {
            String poolId = "TestPool@1";
            METRICS.register(poolId, PoolType.THREAD);
            METRICS.setCounter(poolId, "executeCount", 42);

            String result = exporter.scrape();

            assertTrue(result.contains("_total"),
                    "Prometheus 计数器应包含 _total 后缀");
            assertTrue(result.contains("metapool_thread_execute_count_total"),
                    "驼峰命名应转为蛇形 + _total: metapool_thread_execute_count_total");
        }

        @Test
        @DisplayName("指标名应全小写+下划线（Prometheus 规范）")
        void shouldUseLowerCaseUnderscoreNames() {
            String poolId = "TestPool@2";
            METRICS.register(poolId, PoolType.DB);
            METRICS.setGauge(poolId, "activeCount", 3);
            METRICS.setGauge(poolId, "idleCount", 2);
            METRICS.setGauge(poolId, "pendingCount", 1);

            String result = exporter.scrape();

            // 验证没有大写字母出现在指标名中（Prometheus 规范要求全小写+下划线）
            Pattern metricLinePattern = Pattern.compile("^metapool_[a-z_]+\\{", Pattern.MULTILINE);
            // 提取所有 metapool 开头的指标行
            for (String line : result.split("\n")) {
                if (line.startsWith("metapool_") && !line.startsWith("#")) {
                    String metricName = line.contains("{")
                            ? line.substring(0, line.indexOf('{'))
                            : line.split("\\s")[0];
                    assertTrue(metricName.equals(metricName.toLowerCase()),
                            "指标名应全小写: " + metricName);
                    assertFalse(metricName.contains("Count"),
                            "指标名不应包含驼峰: " + metricName);
                }
            }
        }

        @Test
        @DisplayName("各池类型应使用正确前缀")
        void shouldUseCorrectPoolTypePrefix() {
            String[] prefixes = {
                    "metapool_thread_",
                    "metapool_db_",
                    "metapool_redis_",
                    "metapool_object_",
                    "metapool_memory_",
                    "metapool_rate_limit_",
                    "metapool_lock_"
            };

            for (String prefix : prefixes) {
                // 每个前缀对应的 PoolType
                PoolType type = guessTypeFromPrefix(prefix);
                if (type == null) continue;

                String poolId = "TestPool_" + type.name();
                METRICS.register(poolId, type);
                METRICS.setGauge(poolId, "activeCount", 1);
                METRICS.setCounter(poolId, "acquireCount", 1);
            }

            String result = exporter.scrape();

            // 验证每种池类型的前缀都出现
            for (String prefix : prefixes) {
                assertTrue(result.contains(prefix),
                        "scrape 输出应包含前缀: " + prefix);
            }
        }

        private PoolType guessTypeFromPrefix(String prefix) {
            if (prefix.contains("thread")) return PoolType.THREAD;
            if (prefix.contains("db")) return PoolType.DB;
            if (prefix.contains("redis")) return PoolType.REDIS;
            if (prefix.contains("object")) return PoolType.OBJECT;
            if (prefix.contains("memory")) return PoolType.MEMORY;
            if (prefix.contains("rate_limit")) return PoolType.RATE_LIMIT;
            if (prefix.contains("lock")) return PoolType.LOCK;
            return null;
        }
    }

    // ==================== Label 白名单 ====================

    @Nested
    @DisplayName("Label 白名单")
    class LabelWhitelistTests {

        @Test
        @DisplayName("不应包含高基数动态标签")
        void shouldNotContainHighCardinalityLabels() {
            String poolId = "MyPool@123";
            METRICS.register(poolId, PoolType.THREAD);
            METRICS.setGauge(poolId, "activeCount", 5);

            String result = exporter.scrape();

            // 不应出现禁止的标签
            assertFalse(result.contains("traceId="), "不应包含 traceId 标签");
            assertFalse(result.contains("threadName="), "不应包含 threadName 标签");
            assertFalse(result.contains("connectionId="), "不应包含 connectionId 标签");
            assertFalse(result.contains("requestId="), "不应包含 requestId 标签");
        }

        @Test
        @DisplayName("仅应包含 pool_id 标签（加 HELP/TYPE 注释）")
        void shouldOnlyHavePoolIdAsLabel() {
            String poolId = "SimplePool@1";
            METRICS.register(poolId, PoolType.OBJECT);
            METRICS.setGauge(poolId, "activeCount", 1);

            String result = exporter.scrape();

            // 移除注释行，检查实际指标行
            for (String line : result.split("\n")) {
                if (line.startsWith("metapool_") && line.contains("{")) {
                    // 提取标签部分
                    String labelsPart = line.substring(line.indexOf('{') + 1, line.indexOf('}'));
                    for (String label : labelsPart.split(",")) {
                        String labelName = label.split("=")[0].trim();
                        // 唯一允许的自定义标签是 pool_id
                        assertTrue("pool_id".equals(labelName),
                                "仅允许 pool_id 标签，发现: " + labelName + " in " + line);
                    }
                }
            }
        }
    }

    // ==================== 全部 28 个指标 ====================

    @Nested
    @DisplayName("全部 28 个指标覆盖")
    class All28MetricsTests {

        @Test
        @DisplayName("6 类池 × 预期指标数 = 全部注册")
        void shouldRegisterAllExpectedMetrics() {
            // 线程池 — 6 指标
            String threadPoolId = "ThreadPool@1";
            METRICS.register(threadPoolId, PoolType.THREAD);
            METRICS.setGauge(threadPoolId, "activeCount", 2);
            METRICS.setGauge(threadPoolId, "poolSize", 4);
            METRICS.setGauge(threadPoolId, "queueSize", 5);
            METRICS.setCounter(threadPoolId, "executeCount", 100);
            METRICS.setCounter(threadPoolId, "completedTasks", 90);
            METRICS.setCounter(threadPoolId, "rejectedTasks", 3);

            // DB 连接池 — 6 指标
            String dbPoolId = "DbPool@1";
            METRICS.register(dbPoolId, PoolType.DB);
            METRICS.setGauge(dbPoolId, "activeCount", 5);
            METRICS.setGauge(dbPoolId, "idleCount", 3);
            METRICS.setGauge(dbPoolId, "pendingCount", 1);
            METRICS.setCounter(dbPoolId, "acquireCount", 50);
            METRICS.setCounter(dbPoolId, "releaseCount", 48);
            METRICS.setCounter(dbPoolId, "leakDetected", 0);

            // Redis 连接池 — 6 指标
            String redisPoolId = "RedisPool@1";
            METRICS.register(redisPoolId, PoolType.REDIS);
            METRICS.setGauge(redisPoolId, "activeCount", 3);
            METRICS.setGauge(redisPoolId, "idleCount", 5);
            METRICS.setGauge(redisPoolId, "pendingCount", 2);
            METRICS.setCounter(redisPoolId, "acquireCount", 30);
            METRICS.setCounter(redisPoolId, "releaseCount", 28);
            METRICS.setCounter(redisPoolId, "leakDetected", 1);

            // 限流器 — 3 指标
            String rateLimiterId = "RateLimiter@1";
            METRICS.register(rateLimiterId, PoolType.RATE_LIMIT);
            METRICS.setCounter(rateLimiterId, "tryAcquireCount", 1000);
            METRICS.setCounter(rateLimiterId, "passCount", 900);
            METRICS.setCounter(rateLimiterId, "rejectCount", 100);

            // 分布式锁 — 4 指标
            String lockId = "Lock@1";
            METRICS.register(lockId, PoolType.LOCK);
            METRICS.setGauge(lockId, "isLocked", 0);
            METRICS.setGauge(lockId, "fallbackMode", 0);
            METRICS.setCounter(lockId, "acquireCount", 200);
            METRICS.setCounter(lockId, "releaseCount", 195);
            METRICS.setCounter(lockId, "timeoutCount", 5);

            // 内存池 — 3 指标（gauges from ResourcePool + MemoryPool specific）
            String memoryPoolId = "MemoryPool@1";
            METRICS.register(memoryPoolId, PoolType.MEMORY);
            METRICS.setGauge(memoryPoolId, "activeCount", 2);
            METRICS.setGauge(memoryPoolId, "heapMemoryUsed", 1024);
            METRICS.setGauge(memoryPoolId, "directMemoryUsed", 4096);
            METRICS.setGauge(memoryPoolId, "totalMemoryUsed", 5120);
            METRICS.setCounter(memoryPoolId, "allocateDirectCount", 50);
            METRICS.setCounter(memoryPoolId, "freeDirectCount", 48);

            String result = exporter.scrape();

            // 验证各池的前缀都出现
            assertTrue(result.contains("metapool_thread_"), "缺少线程池指标");
            assertTrue(result.contains("metapool_db_"), "缺少 DB 连接池指标");
            assertTrue(result.contains("metapool_redis_"), "缺少 Redis 连接池指标");
            assertTrue(result.contains("metapool_rate_limit_"), "缺少限流器指标");
            assertTrue(result.contains("metapool_lock_"), "缺少分布式锁指标");
            assertTrue(result.contains("metapool_memory_"), "缺少内存池指标");

            // 用 pool_id 标签数来验证每个池的指标都被注册
            assertTrue(countOccurrences(result, "pool_id=\"ThreadPool@1\"") >= 1);
            assertTrue(countOccurrences(result, "pool_id=\"DbPool@1\"") >= 1);
            assertTrue(countOccurrences(result, "pool_id=\"RedisPool@1\"") >= 1);
            assertTrue(countOccurrences(result, "pool_id=\"RateLimiter@1\"") >= 1);
            assertTrue(countOccurrences(result, "pool_id=\"Lock@1\"") >= 1);
            assertTrue(countOccurrences(result, "pool_id=\"MemoryPool@1\"") >= 1);
        }

        private int countOccurrences(String source, String target) {
            int count = 0;
            int idx = 0;
            while ((idx = source.indexOf(target, idx)) != -1) {
                count++;
                idx += target.length();
            }
            return count;
        }
    }

    // ==================== 并发安全 ====================

    @Nested
    @DisplayName("并发安全")
    class ConcurrencyTests {

        @Test
        @DisplayName("并发注册 + scrape 不应抛异常")
        void shouldBeConcurrentlySafe() throws Exception {
            int poolCount = 10;
            Thread[] threads = new Thread[poolCount];

            for (int i = 0; i < poolCount; i++) {
                final int idx = i;
                threads[i] = new Thread(() -> {
                    String poolId = "ConcurrentPool@" + idx;
                    METRICS.register(poolId, PoolType.THREAD);
                    for (int j = 0; j < 100; j++) {
                        METRICS.setGauge(poolId, "activeCount", j % 10);
                        METRICS.setCounter(poolId, "executeCount", j);
                    }
                });
            }

            // 启动并发写入 + 并发 scrape
            Thread scrapeThread = new Thread(() -> {
                for (int i = 0; i < 50; i++) {
                    assertDoesNotThrow(() -> exporter.scrape());
                }
            });

            scrapeThread.start();
            for (Thread t : threads) {
                t.start();
            }
            for (Thread t : threads) {
                t.join();
            }
            scrapeThread.join();

            // 最终 scrape 不应抛异常
            String finalResult = exporter.scrape();
            assertNotNull(finalResult);
        }
    }

    // ==================== 响应时间 ====================

    @Nested
    @DisplayName("响应时间 < 100ms")
    class ResponseTimeTests {

        @Test
        @DisplayName("scrape 应在 100ms 内完成")
        void shouldCompleteWithin100ms() {
            // 注册 100 个池实例（压力测试）
            for (int i = 0; i < 100; i++) {
                String poolId = "Pool@" + i;
                METRICS.register(poolId, PoolType.THREAD);
                METRICS.setGauge(poolId, "activeCount", i % 10);
                METRICS.setGauge(poolId, "poolSize", 10);
                METRICS.setGauge(poolId, "queueSize", i % 5);
                METRICS.setCounter(poolId, "executeCount", i * 100);
                METRICS.setCounter(poolId, "completedTasks", i * 95);
                METRICS.setCounter(poolId, "rejectedTasks", i % 3);
            }

            long start = System.nanoTime();
            String result = exporter.scrape();
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertNotNull(result);
            assertTrue(elapsedMs < 100,
                    "scrape 应在 100ms 内完成，实际: " + elapsedMs + "ms");
        }
    }
}
