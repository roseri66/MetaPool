package com.metapool.common;

import com.metapool.common.spi.ResourceDefinition;
import com.metapool.common.stats.HealthStatus;
import com.metapool.common.stats.TuneResult;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1 契约层值对象的行为守护：工厂方法、null 归一、防御性拷贝。
 */
class ValueObjectsTest {

    @Test
    void healthStatus_factories_and_nullDetailNormalized() {
        assertEquals(HealthStatus.Status.UP, HealthStatus.up().status());
        assertEquals("", HealthStatus.up().detail());
        assertEquals("boom", HealthStatus.down("boom").detail());
        assertEquals("", new HealthStatus(HealthStatus.Status.DEGRADED, null).detail());
        assertThrows(NullPointerException.class, () -> new HealthStatus(null, "x"));
    }

    @Test
    void tuneResult_okIsSuccess_partialReflectsRejections() {
        assertTrue(TuneResult.ok(Set.of("maxPoolSize")).success());
        TuneResult partial = TuneResult.partial(Set.of("a"), Map.of("b", "not in whitelist"));
        assertFalse(partial.success());
        assertEquals(1, partial.rejected().size());
    }

    @Test
    void resourceDefinition_defensivelyCopiesMutableInputs() {
        Map<String, Object> props = new HashMap<>();
        props.put("maximum-pool-size", 20);
        Set<String> tunable = new HashSet<>(Set.of("maximum-pool-size"));

        ResourceDefinition def = new ResourceDefinition("main", "datasource", props, tunable);
        props.put("maximum-pool-size", 999);   // 外部改动不得影响已构建的定义
        tunable.clear();

        assertEquals(20, def.properties().get("maximum-pool-size"));
        assertEquals(1, def.tunableKeys().size());
        assertThrows(UnsupportedOperationException.class,
                () -> def.properties().put("x", 1));   // 不可变视图
    }
}
