package com.metapool.starter;

import com.metapool.common.exception.MetaPoolException;
import com.metapool.common.manager.ResourceManager;
import com.metapool.common.resource.ManagedResource;
import com.metapool.common.resource.Tunable;
import com.metapool.common.stats.HealthStatus;
import com.metapool.common.stats.TuneResult;
import com.metapool.core.DefaultResourceManager;
import com.metapool.starter.endpoint.MetaPoolEndpoint;
import com.metapool.starter.health.MetaPoolHealthIndicator;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Actuator 门面的行为守护：{@code /actuator/metapool} 的 list/tune 与 health 指示器。
 *
 * <p>这两个类此前零测试，而 tune 端点正是「不停机调参」头牌能力的对外入口，且 P-02 就是端点相关的坑
 * （缺 {@code -parameters} 导致任何使用方启动即失败）。补齐守护。
 */
class ActuatorSurfaceTest {

    /** 可调的假资源，避免测试依赖真实 HikariCP/Bucket4j。 */
    static class FakeResource implements ManagedResource {
        private final String name;
        private final String type;
        HealthStatus.Status status = HealthStatus.Status.UP;

        FakeResource(String name, String type) {
            this.name = name;
            this.type = type;
        }

        @Override public String name() { return name; }
        @Override public String type() { return type; }
        @Override public void start() { }
        @Override public void stop(Duration graceful) { }
        @Override public HealthStatus health() { return new HealthStatus(status, status == HealthStatus.Status.UP ? "" : name + " sick"); }
        @Override public void bindTo(MeterRegistry registry) { }
    }

    static final class TunableFake extends FakeResource implements Tunable {
        Map<String, Object> lastPatch;

        TunableFake(String name) { super(name, "datasource"); }

        @Override public Set<String> tunableKeys() { return Set.of("maximum-pool-size"); }

        @Override public TuneResult apply(Map<String, Object> patch) {
            this.lastPatch = patch;
            return patch.keySet().equals(Set.of("maximum-pool-size"))
                    ? TuneResult.ok(Set.of("maximum-pool-size"))
                    : TuneResult.partial(Set.of(), Map.of("bad", "not in whitelist"));
        }
    }

    private ResourceManager managerWith(ManagedResource... resources) {
        DefaultResourceManager mgr = new DefaultResourceManager(Duration.ZERO);
        for (ManagedResource r : resources) {
            mgr.register(r);
        }
        return mgr;
    }

    @Test
    void endpoint_list_reportsTypeHealthAndTunableKeys() {
        TunableFake ds = new TunableFake("main");
        FakeResource rl = new FakeResource("order-api", "rate-limiter");
        MetaPoolEndpoint endpoint = new MetaPoolEndpoint(managerWith(ds, rl));

        Map<String, Object> out = endpoint.list();

        assertEquals(List.of("main", "order-api"), List.copyOf(out.keySet()), "应保持注册顺序");

        Map<String, Object> main = asMap(out.get("main"));
        assertEquals("datasource", main.get("type"));
        assertEquals("UP", main.get("health"));
        assertEquals(Set.of("maximum-pool-size"), main.get("tunable"));

        // 不实现 Tunable 的资源要报空白名单，而不是漏字段或报错
        Map<String, Object> limiter = asMap(out.get("order-api"));
        assertEquals("rate-limiter", limiter.get("type"));
        assertEquals(Set.of(), limiter.get("tunable"));
    }

    @Test
    void endpoint_tune_passesKeyValueThrough_andReportsResult() {
        TunableFake ds = new TunableFake("main");
        MetaPoolEndpoint endpoint = new MetaPoolEndpoint(managerWith(ds));

        Map<String, Object> ok = endpoint.tune("main", "maximum-pool-size", "40");

        assertEquals(Map.of("maximum-pool-size", "40"), ds.lastPatch);
        assertEquals(true, ok.get("success"));
        assertEquals(Set.of("maximum-pool-size"), ok.get("applied"));
        assertEquals(Map.of(), ok.get("rejected"));
    }

    @Test
    void endpoint_tune_reportsRejections_withoutThrowing() {
        MetaPoolEndpoint endpoint = new MetaPoolEndpoint(managerWith(new TunableFake("main")));

        Map<String, Object> out = endpoint.tune("main", "jdbc-url", "x");

        assertEquals(false, out.get("success"));
        assertTrue(asMap(out.get("rejected")).containsKey("bad"));
    }

    @Test
    void endpoint_tune_onMissingOrNonTunableResource_fails() {
        MetaPoolEndpoint endpoint = new MetaPoolEndpoint(
                managerWith(new FakeResource("plain", "rate-limiter")));

        assertThrows(MetaPoolException.class, () -> endpoint.tune("nope", "k", "v"));
        assertThrows(MetaPoolException.class, () -> endpoint.tune("plain", "k", "v"));
    }

    @Test
    void healthIndicator_mapsAllThreeStates_andCarriesDetails() {
        FakeResource a = new FakeResource("a", "datasource");
        FakeResource b = new FakeResource("b", "rate-limiter");
        MetaPoolHealthIndicator indicator = new MetaPoolHealthIndicator(managerWith(a, b));

        Health up = indicator.health();
        assertEquals(Status.UP, up.getStatus());
        assertEquals(2, up.getDetails().get("resources"));

        b.status = HealthStatus.Status.DEGRADED;
        Health degraded = indicator.health();
        assertEquals("DEGRADED", degraded.getStatus().getCode());
        assertTrue(String.valueOf(degraded.getDetails().get("problems")).contains("b"));

        // DOWN 必须压过 DEGRADED（坑 P-06 的守护）
        a.status = HealthStatus.Status.DOWN;
        Health down = indicator.health();
        assertEquals(Status.DOWN, down.getStatus());
        assertTrue(String.valueOf(down.getDetails().get("problems")).contains("a"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }
}
