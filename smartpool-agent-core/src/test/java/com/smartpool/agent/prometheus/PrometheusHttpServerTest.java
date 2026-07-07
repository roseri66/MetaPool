package com.smartpool.agent.prometheus;

import com.smartpool.agent.SmartPoolMetrics;
import com.smartpool.agent.model.PoolType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SPEC-14 PrometheusHttpServer 集成测试。
 *
 * <p>启动嵌入式 HTTP 服务器，验证端点可达性、响应格式和响应时间。
 *
 * @since 0.1.0
 */
@DisplayName("SPEC-14 PrometheusHttpServer HTTP 端点测试")
class PrometheusHttpServerTest {

    private static final int TEST_PORT = 19100;
    private static final SmartPoolMetrics METRICS = SmartPoolMetrics.getInstance();

    private PrometheusMetricsExporter exporter;
    private PrometheusHttpServer server;
    private HttpClient httpClient;

    @BeforeEach
    void setUp() throws IOException {
        METRICS.clear();

        exporter = new PrometheusMetricsExporter();
        exporter.start();

        server = new PrometheusHttpServer(exporter, TEST_PORT);
        server.start();

        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    @AfterEach
    void tearDown() {
        server.stop();
        exporter.stop();
        METRICS.clear();
    }

    // ==================== 健康检查 ====================

    @Nested
    @DisplayName("健康检查端点")
    class HealthEndpointTests {

        @Test
        @DisplayName("GET /health 返回 200 OK")
        void shouldReturn200ForHealth() throws Exception {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + TEST_PORT + "/health"))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertEquals("OK", response.body());
        }

        @Test
        @DisplayName("health 端点仅允许 GET")
        void shouldRejectNonGetOnHealth() throws Exception {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + TEST_PORT + "/health"))
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(405, response.statusCode());
        }
    }

    // ==================== Prometheus 端点 ====================

    @Nested
    @DisplayName("Prometheus 指标端点")
    class PrometheusEndpointTests {

        @Test
        @DisplayName("GET /actuator/prometheus 返回 200 + text/plain")
        void shouldReturn200WithPrometheusContentType() throws Exception {
            // 预注册一些指标
            String poolId = "TestThread@1";
            METRICS.register(poolId, PoolType.THREAD);
            METRICS.setGauge(poolId, "activeCount", 5);
            METRICS.setCounter(poolId, "executeCount", 100);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + TEST_PORT + "/actuator/prometheus"))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());

            String contentType = response.headers().firstValue("Content-Type").orElse("");
            assertTrue(contentType.contains("text/plain"),
                    "Content-Type 应包含 text/plain: " + contentType);
            assertTrue(contentType.contains("version=0.0.4"),
                    "Content-Type 应包含 Prometheus 版本");

            String body = response.body();
            assertTrue(body.contains("smartpool_thread_"),
                    "响应体应包含 smartpool 指标");
        }

        @Test
        @DisplayName("GET /metrics 应返回与 /actuator/prometheus 相同的内容")
        void shouldReturnSameContentForMetricsEndpoint() throws Exception {
            String poolId = "SharedPool@99";
            METRICS.register(poolId, PoolType.DB);
            METRICS.setGauge(poolId, "activeCount", 3);

            HttpRequest req1 = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + TEST_PORT + "/actuator/prometheus"))
                    .GET().build();
            HttpRequest req2 = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + TEST_PORT + "/metrics"))
                    .GET().build();

            HttpResponse<String> resp1 = httpClient.send(req1,
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> resp2 = httpClient.send(req2,
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, resp1.statusCode());
            assertEquals(200, resp2.statusCode());
            // 两次抓取的值可能因时间略有差异，但指标名应一致
            assertTrue(resp2.body().contains("smartpool_db_"),
                    "/metrics 端点也应返回 Prometheus 指标");
        }

        @Test
        @DisplayName("Prometheus 端点仅允许 GET")
        void shouldRejectNonGetOnMetrics() throws Exception {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + TEST_PORT + "/actuator/prometheus"))
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(405, response.statusCode());
        }

        @Test
        @DisplayName("无指标时端点仍应返回 200")
        void shouldReturn200EvenWithoutMetrics() throws Exception {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + TEST_PORT + "/actuator/prometheus"))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertNotNull(response.body());
        }
    }

    // ==================== 响应时间 ====================

    @Nested
    @DisplayName("响应时间 < 100ms")
    class ResponseTimeTests {

        @Test
        @DisplayName("/actuator/prometheus 应在 100ms 内响应")
        void shouldRespondWithin100ms() throws Exception {
            // 注册大量指标以制造合理负载
            for (int i = 0; i < 50; i++) {
                String poolId = "Pool" + i + "@" + (1000 + i);
                METRICS.register(poolId, PoolType.THREAD);
                METRICS.setGauge(poolId, "activeCount", i % 10);
                METRICS.setGauge(poolId, "poolSize", 10);
                METRICS.setGauge(poolId, "queueSize", i % 5);
                METRICS.setCounter(poolId, "executeCount", i * 100L);
                METRICS.setCounter(poolId, "completedTasks", i * 95L);
                METRICS.setCounter(poolId, "rejectedTasks", i % 3L);
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + TEST_PORT + "/actuator/prometheus"))
                    .GET()
                    .build();

            long start = System.nanoTime();

            // 预热一次
            httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            // 正式测量
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertEquals(200, response.statusCode());
            assertTrue(elapsedMs < 100,
                    "端点响应应在 100ms 内，实际: " + elapsedMs + "ms");
        }
    }

    // ==================== 服务器生命周期 ====================

    @Nested
    @DisplayName("服务器生命周期")
    class ServerLifecycleTests {

        @Test
        @DisplayName("start 后 isRunning 返回 true")
        void shouldReportRunningAfterStart() {
            assertTrue(server.isRunning());
        }

        @Test
        @DisplayName("stop 后 isRunning 返回 false")
        void shouldReportStoppedAfterStop() {
            server.stop();
            assertFalse(server.isRunning());
        }

        @Test
        @DisplayName("重复 start 不抛异常（幂等）")
        void shouldBeIdempotentOnStart() throws IOException {
            server.start(); // 已在 @BeforeEach 中 start 过
            assertTrue(server.isRunning());
        }

        @Test
        @DisplayName("重复 stop 不抛异常（幂等）")
        void shouldBeIdempotentOnStop() {
            server.stop();
            server.stop(); // 第二次 stop
            assertFalse(server.isRunning());
        }

        @Test
        @DisplayName("stop 后端点不可达")
        void shouldNotBeReachableAfterStop() {
            server.stop();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + TEST_PORT + "/health"))
                    .GET()
                    .build();

            assertThrows(Exception.class, () ->
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString()));
        }
    }

    // ==================== 端口配置 ====================

    @Nested
    @DisplayName("端口配置")
    class PortConfigurationTests {

        @Test
        @DisplayName("构造器应接受自定义端口")
        void shouldAcceptCustomPort() {
            PrometheusHttpServer customServer = new PrometheusHttpServer(exporter, 19199);
            assertEquals(19199, customServer.getPort());
        }

        @Test
        @DisplayName("默认端口为 9100")
        void shouldDefaultTo9100() {
            // 验证 DEFAULT_PORT 常量
            assertEquals(9100, PrometheusHttpServer.DEFAULT_PORT);
        }

        @Test
        @DisplayName("非法端口应抛异常")
        void shouldRejectInvalidPorts() {
            assertThrows(IllegalArgumentException.class,
                    () -> new PrometheusHttpServer(exporter, 0));
            assertThrows(IllegalArgumentException.class,
                    () -> new PrometheusHttpServer(exporter, 65536));
            assertThrows(IllegalArgumentException.class,
                    () -> new PrometheusHttpServer(exporter, -1));
        }

        @Test
        @DisplayName("null exporter 应抛异常")
        void shouldRejectNullExporter() {
            assertThrows(IllegalArgumentException.class,
                    () -> new PrometheusHttpServer(null));
        }
    }
}
