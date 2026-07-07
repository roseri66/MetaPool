package com.metapool.agent.prometheus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 嵌入式 HTTP 服务器，暴露 Prometheus 指标抓取端点。
 *
 * <p>使用 JDK 内置 {@link com.sun.net.httpserver.HttpServer}，零外部依赖。
 * 仅暴露两个只读 GET 端点，不适合用作通用 HTTP 服务器。
 *
 * <h3>端点</h3>
 * <ul>
 *   <li>{@code GET /actuator/prometheus} — Prometheus 指标（Spring Boot Actuator 兼容路径）</li>
 *   <li>{@code GET /metrics} — 同上（Prometheus 社区约定路径）</li>
 *   <li>{@code GET /health} — 存活检查，返回 200 OK</li>
 * </ul>
 *
 * <h3>线程模型</h3>
 * <p>HTTP 请求处理使用单线程池，所有线程均为守护线程，不阻止 JVM 退出。
 *
 * <h3>端口配置</h3>
 * <p>优先级：构造参数 → 系统属性 {@code metapool.prometheus.port} → 默认 9100。
 *
 * @since 0.1.0
 */
public final class PrometheusHttpServer {

    /** 默认监听端口。 */
    public static final int DEFAULT_PORT = 9100;

    /** 系统属性名，用于覆盖默认端口。 */
    public static final String PORT_PROPERTY = "metapool.prometheus.port";

    private final PrometheusMetricsExporter exporter;
    private final int port;
    private final AtomicBoolean started;
    private volatile HttpServer server;

    /**
     * 使用默认端口创建 HTTP 服务器。
     */
    public PrometheusHttpServer(PrometheusMetricsExporter exporter) {
        this(exporter, resolvePort());
    }

    /**
     * 使用指定端口创建 HTTP 服务器。
     *
     * @param exporter 指标导出器
     * @param port     监听端口
     */
    public PrometheusHttpServer(PrometheusMetricsExporter exporter, int port) {
        if (exporter == null) {
            throw new IllegalArgumentException("exporter must not be null");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be in range [1, 65535]: " + port);
        }
        this.exporter = exporter;
        this.port = port;
        this.started = new AtomicBoolean(false);
    }

    // ==================== 生命周期 ====================

    /**
     * 启动 HTTP 服务器。
     *
     * <p>可重复调用（幂等），已在运行则忽略。
     *
     * @throws IOException 如果端口被占用
     */
    public void start() throws IOException {
        if (!started.compareAndSet(false, true)) {
            return;
        }

        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/actuator/prometheus", this::handleMetrics);
        server.createContext("/metrics", this::handleMetrics);
        server.createContext("/health", this::handleHealth);

        ExecutorService executor = Executors.newSingleThreadExecutor(
                daemonThreadFactory("metapool-prometheus-http"));
        server.setExecutor(executor);
        server.start();

        System.out.println("[MetaPool] Prometheus endpoint started on port " + port
                + " — scrape at http://localhost:" + port + "/actuator/prometheus");
    }

    /**
     * 停止 HTTP 服务器。
     *
     * <p>可重复调用（幂等），已停止则忽略。延迟最多 2 秒等待现有请求完成。
     */
    public void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        HttpServer s = server;
        if (s != null) {
            s.stop(2);
        }
    }

    /**
     * 返回监听端口。
     */
    public int getPort() {
        return port;
    }

    /**
     * 返回服务器是否正在运行。
     */
    public boolean isRunning() {
        return started.get();
    }

    // ==================== 请求处理 ====================

    private void handleMetrics(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Method Not Allowed");
            return;
        }

        String scraped = exporter.scrape();
        byte[] bytes = scraped.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type",
                "text/plain; version=0.0.4; charset=utf-8");
        sendResponse(exchange, 200, bytes);
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Method Not Allowed");
            return;
        }
        byte[] body = "OK".getBytes(StandardCharsets.UTF_8);
        sendResponse(exchange, 200, body);
    }

    private static void sendResponse(HttpExchange exchange, int statusCode, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        sendResponse(exchange, statusCode, bytes);
    }

    private static void sendResponse(HttpExchange exchange, int statusCode, byte[] body)
            throws IOException {
        exchange.sendResponseHeaders(statusCode, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    // ==================== 工具方法 ====================

    private static int resolvePort() {
        String prop = System.getProperty(PORT_PROPERTY);
        if (prop != null && !prop.isEmpty()) {
            try {
                int p = Integer.parseInt(prop);
                if (p >= 1 && p <= 65535) {
                    return p;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return DEFAULT_PORT;
    }

    private static ThreadFactory daemonThreadFactory(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }
}
