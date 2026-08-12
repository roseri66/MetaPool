package com.metapool.adapter.lettuce;

import com.metapool.common.stats.HealthStatus;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用<b>真实 Redis</b>（Testcontainers）验证 Lettuce 适配器 —— 与 hikari/redisson 的集成测试同构。
 *
 * <p>{@code disabledWithoutDocker = true}：无 Docker 自动跳过，不使构建失败。
 */
@Testcontainers(disabledWithoutDocker = true)
class LettuceAdapterRedisTest {

    private static final int REDIS_PORT = 6379;

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(REDIS_PORT);

    private LettuceAdapter redis;

    @BeforeEach
    void startAdapter() {
        redis = LettuceAdapter.builder()
                .named("cache")
                .uri("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(REDIS_PORT))
                .commandTimeout(Duration.ofSeconds(5))
                .build();
        redis.start();
    }

    @AfterEach
    void stopAdapter() {
        if (redis != null) {
            redis.stop(Duration.ofSeconds(2));
        }
    }

    @Test
    void governsRealRedis_healthAndCommands() {
        assertEquals(HealthStatus.Status.UP, redis.health().status());

        var commands = redis.connection().sync();
        commands.set("k", "v");
        assertEquals("v", commands.get("k"));
        assertEquals("PONG", commands.ping());
    }

    /**
     * 🎯 <b>单连接多路复用的直接证据</b>：重复调 {@link LettuceAdapter#connection()}
     * 拿到的是<b>同一个</b>连接对象。
     *
     * <p>这正是它不该实现 {@code Pool} 的原因 —— 没有「借出一个、再借出另一个」这回事，
     * 所有调用方共享同一条 TCP 连接，命令在其上流水线化。
     */
    @Test
    void connection_isSharedNotPooled() {
        StatefulRedisConnection<String, String> first = redis.connection();
        StatefulRedisConnection<String, String> second = redis.connection();
        assertSame(first, second, "多路复用：每次拿到的必须是同一个共享连接，而不是各借一个");
        assertTrue(first.isOpen());
    }

    /** 连接线程安全：多线程并发发命令不需要任何外部同步。 */
    @Test
    void sharedConnection_isThreadSafe() throws Exception {
        var commands = redis.connection().sync();
        int threads = 8;
        int perThread = 25;
        AtomicInteger ok = new AtomicInteger();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            for (int t = 0; t < threads; t++) {
                final int id = t;
                pool.submit(() -> {
                    for (int i = 0; i < perThread; i++) {
                        commands.set("t" + id + ":" + i, "x");
                        if ("x".equals(commands.get("t" + id + ":" + i))) {
                            ok.incrementAndGet();
                        }
                    }
                });
            }
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(threads * perThread, ok.get(), "共享连接必须支持并发使用，无需外部同步");
    }

    /** 坑 P-15：调参必须同时改运行中的连接与用于重建的配置，重启后不得回退。 */
    @Test
    void tune_commandTimeout_appliesAndSurvivesRestart() {
        assertEquals(Duration.ofSeconds(5), redis.commandTimeout());

        assertTrue(redis.apply(Map.of("command-timeout", "12s")).success());
        assertEquals(Duration.ofSeconds(12), redis.connection().getTimeout(),
                "运行中的连接应立刻生效");

        redis.stop(Duration.ZERO);
        redis.start();
        assertEquals(Duration.ofSeconds(12), redis.commandTimeout(), "重启后调参结果不得丢失（P-15）");
        assertEquals(Duration.ofSeconds(12), redis.connection().getTimeout());
    }

    @Test
    void tune_rejectsNonWhitelistedAndInvalidValues() {
        var notWhitelisted = redis.apply(Map.of("uri", "redis://elsewhere:6379"));
        assertEquals(false, notWhitelisted.success());
        assertTrue(notWhitelisted.rejected().containsKey("uri"),
                "换地址等于换资源，不该是热调");

        assertEquals(false, redis.apply(Map.of("command-timeout", "0s")).success(), "须为正");
        assertEquals(false, redis.apply(Map.of("command-timeout", "soon")).success(), "无法解析");
    }

    /** 坑 P-01：stop 后必须能重启，不能复用已关闭的客户端。 */
    @Test
    void restart_afterStop_yieldsUsableConnection() {
        StatefulRedisConnection<String, String> before = redis.connection();
        redis.stop(Duration.ZERO);
        assertEquals(HealthStatus.Status.DOWN, redis.health().status());

        redis.start();
        assertEquals(HealthStatus.Status.UP, redis.health().status());
        StatefulRedisConnection<String, String> after = redis.connection();
        assertNotSame(before, after, "重启应建立新连接，而不是复用已关闭的那个");
        assertEquals("PONG", after.sync().ping());
    }

    /** 连接事件计数：至少记到一次连接建立 —— 这是适配器唯一真能观测到的一层。 */
    @Test
    void metrics_countConnectionEvents() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        redis.bindTo(registry);

        assertEquals(1.0, registry.get("metapool.redis.connection.open")
                .tag("metapool.resource", "cache").gauge().value());
        assertTrue(registry.get("metapool.redis.connects.total")
                .tag("metapool.resource", "cache").functionCounter().count() >= 1.0,
                "启动时建立连接应被记到");
        assertNotNull(registry.find("metapool.redis.disconnects.total")
                .tag("metapool.resource", "cache").functionCounter());
    }
}
