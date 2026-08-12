package com.metapool.adapter.redisson;

import com.metapool.common.capability.LockHandle;
import com.metapool.common.stats.HealthStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用<b>真实 Redis</b>（Testcontainers）验证 Redisson 适配器全链路 —— 与
 * {@code HikariAdapterPostgresTest} 同构。
 *
 * <p>{@code disabledWithoutDocker = true}：无 Docker 环境自动跳过，不使构建失败。
 * 装有 Docker 时 {@code mvn -pl metapool-adapter-redisson -am test} 即会真实拉起 Redis 并执行。
 *
 * <p><b>本文件才是 2.1 第二个新接口 {@code DistributedLock} 的真正验收</b>：
 * 互斥、租约到期、凭证幂等这三件事，不接真后端验不了。
 */
@Testcontainers(disabledWithoutDocker = true)
class RedissonLockAdapterRedisTest {

    private static final int REDIS_PORT = 6379;

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(REDIS_PORT);

    private RedissonLockAdapter lock;

    @BeforeEach
    void startAdapter() {
        lock = RedissonLockAdapter.builder()
                .named("order-lock")
                .address("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(REDIS_PORT))
                .build();
        lock.start();
    }

    @AfterEach
    void stopAdapter() {
        if (lock != null) {
            lock.stop(Duration.ofSeconds(2));
        }
    }

    @Test
    void governsRealRedis_acquireAndRelease() throws Exception {
        assertEquals(HealthStatus.Status.UP, lock.health().status());

        Optional<LockHandle> acquired =
                lock.tryLock("order:1", Duration.ofSeconds(2), Duration.ofSeconds(30));
        assertTrue(acquired.isPresent(), "空闲键应能立刻拿到锁");

        try (LockHandle held = acquired.get()) {
            assertEquals("order:1", held.key());
            assertTrue(held.isHeld());
            assertEquals(1, lock.lockStats().heldByThisProcess());
        }
        assertEquals(0, lock.lockStats().heldByThisProcess(), "close 后应归还");
        assertEquals(1, lock.lockStats().totalAcquired());
    }

    /**
     * 互斥：同一个键被占住时，另一个<b>线程</b>在 waitTime 内拿不到。
     *
     * <p>必须用另一个线程而不是同一线程重入 —— Redisson 的 {@code RLock} <b>可重入</b>，
     * 同线程再 tryLock 会直接成功，测不出互斥。（可重入是 Redisson 自身行为，
     * MetaPool 的 {@code DistributedLock} 契约<b>不承诺</b>它，见接口注释。）
     */
    @Test
    void mutualExclusion_secondThreadTimesOut() throws Exception {
        try (LockHandle ignored =
                     lock.tryLock("order:2", Duration.ZERO, Duration.ofSeconds(30)).orElseThrow()) {

            AtomicReference<Optional<LockHandle>> result = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            Thread other = new Thread(() -> {
                try {
                    result.set(lock.tryLock("order:2", Duration.ofMillis(300), Duration.ofSeconds(30)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            other.start();
            assertTrue(done.await(10, TimeUnit.SECONDS), "竞争线程应在 waitTime 后返回");

            assertNotNull(result.get());
            assertTrue(result.get().isEmpty(), "键已被占，另一线程应在 waitTime 内拿不到");
            assertEquals(1, lock.lockStats().totalTimeout(), "未获得应计入 timeout");
        }
    }

    /** 锁释放后，同一个键应可以再次获取 —— 否则就是漏解锁。 */
    @Test
    void lockIsReusable_afterRelease() throws Exception {
        lock.tryLock("order:3", Duration.ZERO, Duration.ofSeconds(30)).orElseThrow().close();

        Optional<LockHandle> again =
                lock.tryLock("order:3", Duration.ZERO, Duration.ofSeconds(30));
        assertTrue(again.isPresent(), "释放后同键应可再次获取");
        again.get().close();
    }

    /**
     * 凭证幂等：重复 {@code close()} 必须静默返回。
     *
     * <p>它最常出现在 try-with-resources / finally 里，在那里抛异常只会掩盖业务的原始异常。
     */
    @Test
    void handleClose_isIdempotent() throws Exception {
        LockHandle held = lock.tryLock("order:4", Duration.ZERO, Duration.ofSeconds(30)).orElseThrow();
        held.close();
        held.close();   // 不得抛
        held.close();
        assertFalse(held.isHeld());
        assertEquals(0, lock.lockStats().heldByThisProcess(), "重复 close 不得把计数减成负数");
    }

    /**
     * 🔴 <b>本适配器最重要的一条测试</b>：租约到期后锁自动释放，别的线程能拿到同一把锁 ——
     * 即使原持有者<b>自认为</b>还持有。
     *
     * <p>这不是 bug，是 {@code DistributedLock} 契约「{@code leaseTime} 必填」的<b>直接后果</b>：
     * 显式租约关掉了 Redisson 的看门狗（不自动续期）。好处是持有者进程崩溃时锁一定会被释放，
     * 代价就是本用例演示的场景 —— 业务跑得比租约久时，<b>两个线程会同时进入临界区</b>。
     * 而 {@code DistributedLock} 又明确不提供 fencing token，下游无法拒绝过期持有者的迟到写入。
     *
     * <p>把它写成测试而不是藏起来，是为了让这个代价<b>可见且不会被无意改掉</b>。
     * 规避手段见 {@link RedissonLockAdapter} 类注释。
     */
    @Test
    void leaseExpiry_releasesLock_evenWhileHolderStillThinksItHoldsIt() throws Exception {
        Duration lease = Duration.ofSeconds(1);
        LockHandle held = lock.tryLock("order:5", Duration.ZERO, lease).orElseThrow();

        // 等租约自然到期。这里的 sleep 是【被测行为本身】（租约就是一段时间），
        // 不是 P-17 说的「把断言绑在挂钟上」：我们等的是一个明确约定的时长，且留了充足余量。
        Thread.sleep(lease.toMillis() + 1500);

        Optional<LockHandle> stolen =
                lock.tryLock("order:5", Duration.ZERO, Duration.ofSeconds(30));
        assertTrue(stolen.isPresent(),
                "租约到期后锁必须已被释放，否则持有者崩溃就是永久死锁");
        stolen.get().close();

        // 原持有者此刻 close：本地能观测到的最接近「租约已到期」的信号
        held.close();
        assertTrue(lock.lockStats().totalLeaseExpired() >= 1,
                "租约到期应被近似统计到，该指标偏高即提示 leaseTime 配短了");
    }

    @Test
    void metrics_reflectRealActivity() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        lock.bindTo(registry);

        lock.tryLock("order:6", Duration.ZERO, Duration.ofSeconds(30)).orElseThrow().close();

        assertEquals(1.0, registry.get("metapool.lock.acquired.total")
                .tag("metapool.resource", "order-lock")
                .tag("metapool.type", "lock").functionCounter().count());
        assertNotNull(registry.find("metapool.lock.held")
                .tag("metapool.resource", "order-lock").gauge());
    }

    /** 坑 P-01：stop 后必须能重启，不能复用已关闭的客户端。 */
    @Test
    void restart_afterStop_yieldsUsableLock() throws Exception {
        lock.stop(Duration.ZERO);
        assertEquals(HealthStatus.Status.DOWN, lock.health().status());

        lock.start();
        assertEquals(HealthStatus.Status.UP, lock.health().status());
        lock.tryLock("order:7", Duration.ZERO, Duration.ofSeconds(10)).orElseThrow().close();
    }
}
