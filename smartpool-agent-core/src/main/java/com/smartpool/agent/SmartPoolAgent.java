package com.smartpool.agent;

import com.smartpool.agent.interceptor.*;
import com.smartpool.agent.prometheus.PrometheusHttpServer;
import com.smartpool.agent.prometheus.PrometheusMetricsExporter;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * SmartPool Java Agent 入口。
 *
 * <p>通过 {@code -javaagent:smartpool-agent-core.jar} 挂载，使用 ByteBuddy
 * 对目标类进行字节码增强，零侵入采集运行指标。
 *
 * <h3>拦截目标</h3>
 * <ul>
 *   <li>{@code SmartThreadPoolExecutor#execute} — 线程池指标</li>
 *   <li>{@code AbstractResourcePool#acquire/release} — 连接池/对象池/内存池指标</li>
 *   <li>{@code TokenBucketRateLimiter#tryAcquire} — 限流器指标</li>
 *   <li>{@code SmartReentrantLock#tryLock/unlock} — 分布式锁指标</li>
 *   <li>{@code MemoryPool#allocateDirect/freeDirect} — 堆外内存指标</li>
 * </ul>
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * java -javaagent:smartpool-agent-core.jar -jar myapp.jar
 * }</pre>
 *
 * @since 0.1.0
 */
public class SmartPoolAgent {

    /**
     * Prometheus HTTP 服务器引用，供测试验证。
     */
    static volatile PrometheusHttpServer httpServer;

    /**
     * 指标导出器引用。
     */
    static volatile PrometheusMetricsExporter exporter;

    private SmartPoolAgent() {
    }

    /**
     * JVM premain 入口，在应用启动前由 JVM 调用。
     *
     * <p>安装 ByteBuddy 字节码拦截后，启动 Prometheus 指标暴露端点。
     *
     * @param agentArgs 代理参数，支持 {@code port=9100} 指定 Prometheus 端点端口
     * @param inst      {@link Instrumentation} 实例
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        buildAgent().installOn(inst);
        startPrometheusEndpoint(agentArgs);
    }

    /**
     * JVM agentmain 入口，支持运行时动态挂载。
     *
     * @param agentArgs 代理参数（同 premain）
     * @param inst      {@link Instrumentation} 实例
     */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        premain(agentArgs, inst);
    }

    /**
     * 构建 ByteBuddy Agent，按类→方法精确匹配并注册拦截器。
     *
     * <p>每个 {@code .type().transform()} 对是独立的拦截规则。
     * 不匹配的类（不在 classpath 上）会被 ByteBuddy 静默跳过。
     */
    private static AgentBuilder buildAgent() {
        return new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                .ignore(nameStartsWith("com.smartpool.agent.")
                        .or(nameStartsWith("net.bytebuddy.")))
                // --- 线程池 ---
                .type(named("com.smartpool.pool.thread.SmartThreadPoolExecutor"))
                .transform((builder, type, classLoader, module, pd) ->
                        builder.visit(Advice.to(ThreadPoolInterceptor.class)
                                .on(named("execute").and(takesArguments(1)))))
                // --- 资源池基类 (覆盖 DbConnectionPool/RedisConnectionPool/GenericObjectPool/MemoryPool) ---
                .type(named("com.smartpool.common.pool.AbstractResourcePool"))
                .transform((builder, type, classLoader, module, pd) ->
                        builder.visit(Advice.to(ResourcePoolInterceptor.class)
                                .on(named("acquire").and(takesArguments(0))
                                        .or(named("acquire").and(takesArguments(2)))
                                        .or(named("release").and(takesArguments(1))))))
                // --- 限流器 ---
                .type(named("com.smartpool.pool.rate.limit.TokenBucketRateLimiter"))
                .transform((builder, type, classLoader, module, pd) ->
                        builder.visit(Advice.to(RateLimiterInterceptor.class)
                                .on(named("tryAcquire").and(takesArguments(0)))))
                // --- 分布式锁 ---
                .type(named("com.smartpool.pool.lock.SmartReentrantLock"))
                .transform((builder, type, classLoader, module, pd) ->
                        builder.visit(Advice.to(LockInterceptor.class)
                                .on(named("tryLock").and(takesArguments(0))
                                        .or(named("tryLock").and(takesArguments(2)))
                                        .or(named("unlock").and(takesArguments(0))))))
                // --- 内存池 (仅 allocateDirect/freeDirect; acquire/release 由基类拦截) ---
                .type(named("com.smartpool.pool.memory.MemoryPool"))
                .transform((builder, type, classLoader, module, pd) ->
                        builder.visit(Advice.to(MemoryPoolInterceptor.class)
                                .on(named("allocateDirect").and(takesArguments(int.class))
                                        .or(named("freeDirect")))))
                .with(new AgentBuilder.Listener.StreamWriting(System.err)
                        .withErrorsOnly());
    }

    // ==================== Prometheus 端点 ====================

    /**
     * 启动 Prometheus 指标暴露端点。
     *
     * <p>失败不抛出异常 — Agent 的核心职责（字节码拦截）不受影响。
     *
     * @param agentArgs 代理参数，格式：{@code port=9100} 或 {@code key1=val1,key2=val2}
     */
    private static void startPrometheusEndpoint(String agentArgs) {
        try {
            exporter = new PrometheusMetricsExporter();
            exporter.start();

            int port = parseAgentPort(agentArgs);
            httpServer = new PrometheusHttpServer(exporter, port);
            httpServer.start();
        } catch (Exception e) {
            System.err.println("[SmartPool] Failed to start Prometheus endpoint: " + e.getMessage());
        }
    }

    /**
     * 从 agentArgs 中解析端口号。
     *
     * <p>支持格式：
     * <ul>
     *   <li>{@code null} 或空字符串 → 使用默认端口</li>
     *   <li>{@code "9100"} → 纯数字</li>
     *   <li>{@code "port=9100"} → key=value 格式</li>
     *   <li>{@code "port=9100,foo=bar"} → 逗号分隔，取 port 值</li>
     * </ul>
     */
    private static int parseAgentPort(String agentArgs) {
        if (agentArgs == null || agentArgs.trim().isEmpty()) {
            return PrometheusHttpServer.DEFAULT_PORT;
        }

        // 先尝试 key=value 格式
        String[] pairs = agentArgs.split(",");
        for (String pair : pairs) {
            String[] kv = pair.trim().split("=", 2);
            if (kv.length == 2 && "port".equalsIgnoreCase(kv[0].trim())) {
                try {
                    return Integer.parseInt(kv[1].trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }

        // 再尝试纯数字格式
        try {
            return Integer.parseInt(agentArgs.trim());
        } catch (NumberFormatException e) {
            return PrometheusHttpServer.DEFAULT_PORT;
        }
    }
}
