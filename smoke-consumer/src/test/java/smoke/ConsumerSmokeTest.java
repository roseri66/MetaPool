package smoke;

import com.metapool.common.capability.ManagedExecutor;
import com.metapool.common.capability.Pool;
import com.metapool.common.capability.RateLimiter;
import com.metapool.common.manager.ResourceManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用方冒烟测试：<b>验证发布到 Maven Central 的正式件，外部使用方拿去真的能用。</b>
 *
 * <p>它和仓库里其余测试的区别：其余测试验证的是「我刚编译出来的代码是对的」，
 * 这一条验证的是「我发布出去的 jar 是能用的」。这两件事会不真相等 ——
 * 2.0.0 就发生过：仓库里一切正常，但打进 starter 的 {@code logback-spring.xml}
 * 让所有使用方的日志静默消失（台账 P-08）。那种问题只有站在使用方这一侧才看得见。
 *
 * <p>断言的是 README 对外承诺的那几件事，不多不少：
 * <ol>
 *   <li>只写 YAML、零装配代码，三类异构资源就被纳管</li>
 *   <li>业务能通过<b>能力接口</b>拿到资源来用（不依赖具体适配器类）</li>
 *   <li>{@code /actuator/metapool} 能查、能<b>不重启热调</b>，且白名单外的 key 会被拒</li>
 *   <li>{@code /actuator/prometheus} 上三个库的指标带<b>同一套 tag</b>（头牌能力）</li>
 * </ol>
 *
 * <p>⚠️ <b>{@code @AutoConfigureObservability} 不能删。</b> Spring Boot 在 {@code @SpringBootTest} 里
 * <b>默认关闭 metrics export 自动配置</b>（上下文里只会有 {@code SimpleMeterRegistry}，
 * 一个 Prometheus bean 都没有），于是 {@code /actuator/prometheus} 必然 404。
 * 这个 404 极易被误读成「MetaPool 的指标坏了」—— 实际 {@code java -jar} 跑真应用时完全正常。
 * 见台账 P-24。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability   // 见下方注释：不加这个，/actuator/prometheus 在测试里必然 404
class ConsumerSmokeTest {

    @Autowired
    ResourceManager metaPool;

    @Autowired
    TestRestTemplate rest;

    @LocalServerPort
    int port;

    /** ① 零装配代码，YAML 里声明的三类资源全部就位。 */
    @Test
    void yamlOnly_governsThreeHeterogeneousResources() {
        assertThat(metaPool.resources()).hasSize(3);
        assertThat(metaPool.get("main").type()).isEqualTo("datasource");
        assertThat(metaPool.get("api").type()).isEqualTo("rate-limiter");
        assertThat(metaPool.get("worker").type()).isEqualTo("executor");
        assertThat(metaPool.health().status().name()).isEqualTo("UP");
    }

    /** ② 业务经能力接口使用资源 —— 三种用法各不相同，这正是「统一治理不统一用法」。 */
    @Test
    @SuppressWarnings("unchecked")
    void businessCode_usesResourcesThroughCapabilityInterfaces() throws Exception {
        assertThat(((RateLimiter) metaPool.get("api")).tryAcquire(1)).isTrue();

        Pool<Connection> ds = (Pool<Connection>) metaPool.get("main");
        Connection c = ds.borrow();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT 1")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);
        } finally {
            ds.release(c);
        }

        ManagedExecutor worker = (ManagedExecutor) metaPool.get("worker");
        assertThat(worker.submit(() -> 6 * 7).get(10, TimeUnit.SECONDS)).isEqualTo(42);
    }

    /** ③ Actuator 端点：能查、能热调，白名单外的 key 必须被拒。 */
    @Test
    @SuppressWarnings("unchecked")
    void actuatorEndpoint_listsTunesAndRejectsNonWhitelistedKeys() {
        Map<String, Object> listing = rest.getForObject(url("/actuator/metapool"), Map.class);
        assertThat(listing).containsKeys("main", "api", "worker");

        Map<String, Object> ok = rest.postForObject(url("/actuator/metapool/main"),
                Map.of("key", "maximum-pool-size", "value", "40"), Map.class);
        assertThat(ok).containsEntry("success", true);

        Map<String, Object> rejected = rest.postForObject(url("/actuator/metapool/main"),
                Map.of("key", "jdbc-url", "value", "jdbc:h2:mem:hacked"), Map.class);
        assertThat(rejected).containsEntry("success", false);
        assertThat(rejected.get("rejected").toString()).contains("jdbc-url");
    }

    /**
     * ④ 头牌能力：三个毫不相干的库，指标在同一个端点上带<b>同一套 tag</b>。
     *
     * <p>这条如果红了，README 首屏那句「一个 Grafana 看板看到所有资源」就是假的。
     */
    @Test
    void prometheusEndpoint_exposesAllThreeLibrariesUnderOneTagScheme() {
        String body = rest.getForObject(url("/actuator/prometheus"), String.class);

        assertThat(body)
                .contains("metapool_datasource_connections_active{metapool_resource=\"main\"")
                .contains("metapool_ratelimiter_available_tokens{metapool_resource=\"api\"")
                .contains("metapool_executor_queue_size{metapool_resource=\"worker\"");

        // 统一 tag：每条 metapool_* 指标都必须同时带 resource 与 type 两个 tag
        body.lines()
                .filter(l -> l.startsWith("metapool_"))
                .forEach(l -> assertThat(l)
                        .as("每条 metapool 指标都应带统一 tag: %s", l)
                        .contains("metapool_resource=")
                        .contains("metapool_type="));
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
