package com.metapool.agent.model;

import com.metapool.agent.MetaPoolMetrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link PoolMetricsSnapshot} 和 {@link MetaPoolMetrics} 的单元测试。
 *
 * @since 0.1.0
 */
@DisplayName("PoolMetricsSnapshot")
class PoolMetricsSnapshotTest {

    // ==================== 构造与字段 ====================

    @Nested
    @DisplayName("构造与字段验证")
    class ConstructionTests {

        @Test
        @DisplayName("Builder 正常构造应返回所有字段正确的快照")
        void shouldBuildValidSnapshot() {
            PoolMetricsSnapshot snapshot = PoolMetricsSnapshot.builder()
                    .poolId("test-pool-1")
                    .poolType(PoolType.THREAD)
                    .counter("executeCount", 100)
                    .counter("completedTasks", 95)
                    .gauge("activeCount", 3.0)
                    .gauge("queueSize", 10.0)
                    .build();

            assertEquals("test-pool-1", snapshot.getPoolId());
            assertEquals(PoolType.THREAD, snapshot.getPoolType());
            assertEquals(100L, snapshot.getCounters().get("executeCount"));
            assertEquals(95L, snapshot.getCounters().get("completedTasks"));
            assertEquals(3.0, snapshot.getGauges().get("activeCount"));
            assertEquals(10.0, snapshot.getGauges().get("queueSize"));
        }

        @Test
        @DisplayName("poolId 为 null 时应抛异常")
        void shouldThrowWhenPoolIdNull() {
            assertThrows(IllegalArgumentException.class, () ->
                    PoolMetricsSnapshot.builder().poolId(null).poolType(PoolType.THREAD).build());
        }

        @Test
        @DisplayName("poolId 为空字符串时应抛异常")
        void shouldThrowWhenPoolIdEmpty() {
            assertThrows(IllegalArgumentException.class, () ->
                    PoolMetricsSnapshot.builder().poolId("  ").poolType(PoolType.THREAD).build());
        }

        @Test
        @DisplayName("poolType 为 null 时应抛异常")
        void shouldThrowWhenPoolTypeNull() {
            assertThrows(IllegalArgumentException.class, () ->
                    PoolMetricsSnapshot.builder().poolId("test").poolType(null).build());
        }

        @Test
        @DisplayName("空 counters 和 gauges 应正常构造")
        void shouldBuildWithEmptyMaps() {
            PoolMetricsSnapshot snapshot = PoolMetricsSnapshot.builder()
                    .poolId("minimal")
                    .poolType(PoolType.DB)
                    .build();

            assertTrue(snapshot.getCounters().isEmpty());
            assertTrue(snapshot.getGauges().isEmpty());
        }
    }

    // ==================== 不可变性 ====================

    @Nested
    @DisplayName("不可变性")
    class ImmutabilityTests {

        @Test
        @DisplayName("类应为 final")
        void shouldBeFinal() {
            assertTrue(java.lang.reflect.Modifier.isFinal(PoolMetricsSnapshot.class.getModifiers()));
        }

        @Test
        @DisplayName("getCounters 返回的 Map 应为不可修改")
        void shouldReturnUnmodifiableCounters() {
            PoolMetricsSnapshot snapshot = PoolMetricsSnapshot.builder()
                    .poolId("test")
                    .poolType(PoolType.REDIS)
                    .counter("c1", 1)
                    .build();

            assertThrows(UnsupportedOperationException.class, () ->
                    snapshot.getCounters().put("newKey", 1L));
        }

        @Test
        @DisplayName("getGauges 返回的 Map 应为不可修改")
        void shouldReturnUnmodifiableGauges() {
            PoolMetricsSnapshot snapshot = PoolMetricsSnapshot.builder()
                    .poolId("test")
                    .poolType(PoolType.REDIS)
                    .gauge("g1", 1.0)
                    .build();

            assertThrows(UnsupportedOperationException.class, () ->
                    snapshot.getGauges().put("newKey", 1.0));
        }
    }

    // ==================== MetaPoolMetrics 注册表 ====================

    @Nested
    @DisplayName("MetaPoolMetrics 注册表")
    class MetricsRegistryTests {

        @Test
        @DisplayName("register 后 counter 操作应正确累加")
        void shouldRegisterAndIncrementCounter() {
            MetaPoolMetrics metrics = MetaPoolMetrics.getInstance();
            metrics.clear();

            metrics.register("pool-1", PoolType.THREAD);
            metrics.incrementCounter("pool-1", "executeCount");
            metrics.incrementCounter("pool-1", "executeCount");
            metrics.incrementCounter("pool-1", "executeCount");

            assertEquals(3, metrics.getCounter("pool-1", "executeCount"));
            metrics.clear();
        }

        @Test
        @DisplayName("未注册时自动注册并操作")
        void shouldAutoRegisterOnOperation() {
            MetaPoolMetrics metrics = MetaPoolMetrics.getInstance();
            metrics.clear();

            metrics.setGauge("auto-pool", "activeCount", 5);
            assertEquals(5, metrics.getGauge("auto-pool", "activeCount"));
            metrics.clear();
        }

        @Test
        @DisplayName("addCounter 应按 delta 累加")
        void shouldAddCounterDelta() {
            MetaPoolMetrics metrics = MetaPoolMetrics.getInstance();
            metrics.clear();

            metrics.register("pool-2", PoolType.DB);
            metrics.addCounter("pool-2", "acquireWaitNanos", 1_000_000L);
            metrics.addCounter("pool-2", "acquireWaitNanos", 500_000L);

            assertEquals(1_500_000L, metrics.getCounter("pool-2", "acquireWaitNanos"));
            metrics.clear();
        }

        @Test
        @DisplayName("setGauge 应覆盖旧值")
        void shouldOverwriteGauge() {
            MetaPoolMetrics metrics = MetaPoolMetrics.getInstance();
            metrics.clear();

            metrics.setGauge("pool-3", "queueSize", 10);
            metrics.setGauge("pool-3", "queueSize", 20);

            assertEquals(20, metrics.getGauge("pool-3", "queueSize"));
            metrics.clear();
        }

        @Test
        @DisplayName("未注册指标的 get 应返回 0")
        void shouldReturnZeroForUnknownMetric() {
            MetaPoolMetrics metrics = MetaPoolMetrics.getInstance();
            assertEquals(0, metrics.getCounter("nonexistent", "anyMetric"));
        }

        @Test
        @DisplayName("snapshot 应返回所有已注册池的快照")
        void shouldReturnAllSnapshots() {
            MetaPoolMetrics metrics = MetaPoolMetrics.getInstance();
            metrics.clear();

            metrics.register("p1", PoolType.THREAD);
            metrics.setGauge("p1", "activeCount", 3);
            metrics.incrementCounter("p1", "executeCount");

            metrics.register("p2", PoolType.DB);
            metrics.setGauge("p2", "activeCount", 1);
            metrics.incrementCounter("p2", "acquireCount");

            List<PoolMetricsSnapshot> snapshots = metrics.snapshot();
            assertEquals(2, snapshots.size());

            metrics.clear();
        }

        @Test
        @DisplayName("poolCount 应返回正确的注册数")
        void shouldReturnCorrectPoolCount() {
            MetaPoolMetrics metrics = MetaPoolMetrics.getInstance();
            metrics.clear();

            assertEquals(0, metrics.poolCount());

            metrics.register("p1", PoolType.THREAD);
            metrics.register("p2", PoolType.DB);
            assertEquals(2, metrics.poolCount());

            metrics.clear();
        }
    }
}
