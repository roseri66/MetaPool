package com.metapool.examples;

import com.metapool.common.capability.ManagedExecutor;
import com.metapool.common.capability.Pool;
import com.metapool.common.capability.RateLimiter;
import com.metapool.common.exception.PoolExhaustedException;
import com.metapool.common.manager.ResourceManager;
import com.metapool.common.resource.ManagedResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * 🔴 <b>仅供演示</b>：把某个被治理资源<b>故意打饱和</b>，用来现场观察治理面的反应。
 *
 * <p>MetaPool 的价值不在「正常时能跑」，而在<b>出问题时你能看见、并且能不停机地救回来</b>。
 * 这个端点把那个过程压缩成 30 秒能演完的动作：
 *
 * <pre>
 *   POST /demo/saturate/order-worker?seconds=30
 *        → 线程池被打满，health 从 UP 变 DEGRADED，Grafana 的队列深度曲线冲顶
 *   GET  /actuator/metapool
 *        → 看到聚合健康降级，并点名是哪个资源
 *   POST /actuator/metapool/order-worker  {"key":"maximum-pool-size","value":"16"}
 *        → 不重启，热调救回来
 *   POST /demo/release/order-worker
 *        → 提前释放
 * </pre>
 *
 * <h3>它按「能力接口」分派，不按类型字符串</h3>
 * <p>怎么把一个资源打饱和，取决于它<b>有什么能力</b>而不是它<b>是什么类型</b>：
 * 池类借光、执行器塞满、限流器抽干。这段 {@code instanceof} 分派本身就是能力隔离的演示 ——
 * 新增一种资源类型时，这里不需要认识它，只要它实现了某个已知能力就能被打饱和。
 *
 * <h3>⚠️ 不要照搬到生产</h3>
 * <p>本类会<b>故意</b>耗尽资源，且未做任何认证。它属于 examples，不在任何发布构件里
 * （{@code metapool-examples} 设了 {@code maven.deploy.skip}）。
 */
@RestController
public class SaturationController {

    private static final Logger log = LoggerFactory.getLogger(SaturationController.class);

    /** 单次饱和的最长持续时间。演示用，防止忘记释放把 demo 应用长期占死。 */
    private static final int MAX_SECONDS = 120;

    /** 池类资源最多借这么多，避免配置写大时无限循环。 */
    private static final int MAX_ACQUIRE = 500;

    private final ResourceManager metaPool;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    SaturationController(ResourceManager metaPool) {
        this.metaPool = metaPool;
    }

    /** 一次饱和会话：{@code latch} 用于提前释放，{@code undo} 是释放动作。 */
    private record Session(CountDownLatch latch, List<Runnable> undo, int occupied, String capability) {
    }

    @PostMapping("/demo/saturate/{name}")
    public ResponseEntity<Map<String, Object>> saturate(
            @PathVariable String name,
            @RequestParam(defaultValue = "30") int seconds) {

        ManagedResource resource;
        try {
            resource = metaPool.get(name);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "no such governed resource: " + name,
                            "available", metaPool.resources().stream().map(ManagedResource::name).toList()));
        }
        if (sessions.containsKey(name)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "'" + name + "' is already saturated",
                            "hint", "POST /demo/release/" + name));
        }
        int hold = Math.max(1, Math.min(seconds, MAX_SECONDS));

        String healthBefore = resource.health().status().name();
        CountDownLatch latch = new CountDownLatch(1);
        List<Runnable> undo = new ArrayList<>();

        int occupied;
        String capability;
        try {
            // ── 按能力分派：怎么打饱和取决于它有什么能力，不是它是什么类型 ──
            if (resource instanceof ManagedExecutor executor) {
                capability = "ManagedExecutor";
                occupied = fillExecutor(executor, latch);
            } else if (resource instanceof Pool<?> pool) {
                capability = "Pool<T>";
                occupied = drainPool(pool, latch, undo);
            } else if (resource instanceof RateLimiter limiter) {
                capability = "RateLimiter";
                occupied = drainLimiter(limiter);
            } else {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "resource '" + name + "' (" + resource.type()
                                + ") exposes no capability this demo knows how to saturate",
                        "hint", "supported: ManagedExecutor / Pool<T> / RateLimiter"));
            }
        } catch (RuntimeException e) {
            undo.forEach(Runnable::run);
            latch.countDown();
            throw e;
        }

        sessions.put(name, new Session(latch, undo, occupied, capability));
        startAutoRelease(name, latch, hold);

        String healthAfter = resource.health().status().name();
        log.warn("[demo] saturated '{}' via {} ({} occupied), health {} -> {}",
                name, capability, occupied, healthBefore, healthAfter);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resource", name);
        body.put("type", resource.type());
        body.put("capability", capability);
        body.put("occupied", occupied);
        body.put("healthBefore", healthBefore);
        body.put("healthAfter", healthAfter);
        body.put("aggregateHealth", metaPool.health().status().name());
        body.put("autoReleaseInSeconds", hold);
        body.put("watch", List.of(
                "GET  /actuator/metapool               —— 聚合健康会点名降级的资源",
                "GET  /actuator/prometheus             —— metapool_* 指标",
                "POST /actuator/metapool/" + name + "   —— 不重启热调救回来",
                "POST /demo/release/" + name + "        —— 提前释放"));
        if ("UP".equals(healthAfter)) {
            body.put("note", "该资源的 health() 不因饱和而降级（如限流器：抽干令牌不是故障，"
                    + "而是它正在履职）。看指标而不是健康状态：可用令牌归零、拒绝计数上涨。");
        }
        return ResponseEntity.ok(body);
    }

    @PostMapping("/demo/release/{name}")
    public ResponseEntity<Map<String, Object>> release(@PathVariable String name) {
        Session s = sessions.remove(name);
        if (s == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "'" + name + "' is not currently saturated"));
        }
        s.latch().countDown();
        s.undo().forEach(Runnable::run);
        log.info("[demo] released '{}'", name);
        return ResponseEntity.ok(Map.of(
                "resource", name,
                "released", s.occupied(),
                "health", metaPool.get(name).health().status().name(),
                "aggregateHealth", metaPool.health().status().name()));
    }

    @GetMapping("/demo/saturation")
    public Map<String, Object> state() {
        Map<String, Object> active = new LinkedHashMap<>();
        sessions.forEach((name, s) -> active.put(name, Map.of(
                "capability", s.capability(), "occupied", s.occupied(),
                "health", metaPool.get(name).health().status().name())));
        return Map.of("saturated", active, "aggregateHealth", metaPool.health().status().name());
    }

    // ==================== 各能力的打饱和方式 ====================

    /**
     * 线程池：一直提交阻塞任务，直到被拒 —— 被拒即证明线程与队列都已满。
     *
     * <p>任务体只是 {@code latch.await()}，不烧 CPU；释放时 countDown，它们自行结束。
     */
    private int fillExecutor(ManagedExecutor executor, CountDownLatch latch) {
        int submitted = 0;
        try {
            while (submitted < MAX_ACQUIRE) {
                executor.execute(() -> awaitQuietly(latch));
                submitted++;
            }
        } catch (RejectedExecutionException expected) {
            // 正是我们要的信号：饱和了。异常由 MetaPool 原样透传（不包装），此处照 JDK 语义处理。
            log.info("[demo] executor saturated after {} submissions", submitted);
        }
        return submitted;
    }

    /**
     * 池类资源：借光，然后<b>再制造一个等待者</b>。
     *
     * <p>只借光还不够 —— `HikariAdapter.health()` 的降级判据是「无空闲 <b>且</b> 有人在等」，
     * 因为「借满」本身不是故障，那正是池在满负荷工作。要演示降级就必须真的有人排队。
     */
    private int drainPool(Pool<?> pool, CountDownLatch latch, List<Runnable> undo) {
        List<Object> held = new ArrayList<>();
        try {
            while (held.size() < MAX_ACQUIRE) {
                held.add(pool.borrow());
            }
        } catch (PoolExhaustedException expected) {
            log.info("[demo] pool drained after {} borrows", held.size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        undo.add(() -> releaseAll(pool, held));

        // 制造排队者：它会阻塞在 borrow 上，从而让 pending > 0
        Thread waiter = new Thread(() -> {
            try {
                Object o = pool.borrow(Duration.ofSeconds(MAX_SECONDS));
                releaseAll(pool, List.of(o));   // 万一真拿到了（已被释放），立刻还回去
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException ignored) {
                // 超时/耗尽都在预期内
            }
        }, "demo-pool-waiter");
        waiter.setDaemon(true);
        waiter.start();
        undo.add(waiter::interrupt);

        awaitPendingVisible(pool);
        return held.size();
    }

    /** 限流器：抽干令牌。注意它不会因此降级 —— 拒绝流量正是它在履职。 */
    private int drainLimiter(RateLimiter limiter) {
        int consumed = 0;
        while (consumed < MAX_ACQUIRE && limiter.tryAcquire(1)) {
            consumed++;
        }
        return consumed;
    }

    // ==================== helpers ====================

    @SuppressWarnings("unchecked")
    private static void releaseAll(Pool<?> pool, List<?> held) {
        Pool<Object> p = (Pool<Object>) pool;
        for (Object o : held) {
            try {
                p.release(o);
            } catch (RuntimeException e) {
                log.warn("[demo] release failed", e);
            }
        }
    }

    /**
     * 等到「有人在排队」这件事对池可见为止，再返回给调用方 ——
     * 否则响应里的 healthAfter 会与排队线程竞速，演示时时灵时不灵。
     */
    private static void awaitPendingVisible(Pool<?> pool) {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            if (pool.poolStats().pending() > 0) {
                return;
            }
            Thread.onSpinWait();
        }
    }

    /** 到点自动释放，防止演示完忘了收拾把 demo 应用占死。 */
    private void startAutoRelease(String name, CountDownLatch latch, int seconds) {
        Thread t = new Thread(() -> {
            try {
                if (!latch.await(seconds, TimeUnit.SECONDS)) {
                    log.info("[demo] auto-releasing '{}' after {}s", name, seconds);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                Session s = sessions.remove(name);
                if (s != null) {
                    s.latch().countDown();
                    s.undo().forEach(Runnable::run);
                }
            }
        }, "demo-saturation-" + name);
        t.setDaemon(true);
        t.start();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(MAX_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
