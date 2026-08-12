package com.metapool.examples;

import com.metapool.common.capability.ManagedExecutor;
import com.metapool.common.capability.Pool;
import com.metapool.common.capability.RateLimiter;
import com.metapool.common.manager.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

/**
 * 演示业务如何<b>通过控制面的能力接口</b>使用被治理的资源：
 * 先经 {@link RateLimiter} 限流，放行后经 {@link Pool}{@code <Connection>} 取连接落库，
 * 最后把审计写入丢给 {@link ManagedExecutor} 异步执行。
 *
 * <p>三种能力接口、三个毫不相干的底层库（Bucket4j / HikariCP / JDK {@code ThreadPoolExecutor}），
 * 业务侧只依赖 {@code metapool-common} 的抽象，一个都不感知。
 *
 * <p><b>注意三者用法各不相同</b>：限流是 {@code tryAcquire}、连接池是 {@code borrow/release}、
 * 线程池是 {@code submit} —— MetaPool 统一的是治理（生命周期 / 指标 / 调参 / 停机），<b>不是用法</b>。
 * 1.0 试图把它们统一成一套 {@code acquire/release}，那正是里氏替换被破坏的起点（台账 P-07）。
 */
@RestController
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final RateLimiter rateLimiter;
    private final Pool<Connection> datasource;
    private final ManagedExecutor worker;

    @SuppressWarnings("unchecked")
    public OrderController(ResourceManager metaPool) {
        this.rateLimiter = (RateLimiter) metaPool.get("order-api");
        this.datasource = (Pool<Connection>) metaPool.get("main");
        this.worker = (ManagedExecutor) metaPool.get("order-worker");
    }

    @PostMapping("/orders/{id}")
    public ResponseEntity<Map<String, Object>> create(@PathVariable String id) throws Exception {
        if (!rateLimiter.tryAcquire(1)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("id", id, "status", "RATE_LIMITED"));
        }
        Connection conn = datasource.borrow();
        try {
            try (PreparedStatement ps = conn.prepareStatement(
                    "MERGE INTO orders(id) KEY(id) VALUES(?)")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            long count;
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM orders");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                count = rs.getLong(1);
            }
            String audit = submitAudit(id);
            return ResponseEntity.ok(Map.of("id", id, "status", "OK",
                    "totalOrders", count, "audit", audit));
        } finally {
            datasource.release(conn);
        }
    }

    /**
     * 把审计写入丢到被治理的线程池，不阻塞响应。
     *
     * <p>刻意<b>不吞</b> {@link RejectedExecutionException}：线程池饱和时该异常由 MetaPool 原样透传
     * （不包装成 {@code MetaPoolException}），这里演示调用方能按 JDK 的既有语义处理它 —— 审计降级，
     * 主流程照常成功。若 MetaPool 当初把它包装成自有异常，这段代码就得去认一套新词汇。
     */
    private String submitAudit(String orderId) {
        try {
            worker.execute(() -> writeAudit(orderId));
            return "SUBMITTED";
        } catch (RejectedExecutionException e) {
            log.warn("[demo] audit rejected, executor saturated; degrading (order={})", orderId);
            return "REJECTED";
        }
    }

    private void writeAudit(String orderId) {
        Connection conn;
        try {
            conn = datasource.borrow();
        } catch (InterruptedException e) {
            // 恢复中断标志后放弃本次审计。这条路径正对着优雅停机：stop(graceful) 超时后会
            // shutdownNow() 中断在跑的任务，工作线程若把中断吞掉，停机就会从"优雅"变成"挂住"。
            Thread.currentThread().interrupt();
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO order_audit(order_id, worker) VALUES(?, ?)")) {
            ps.setString(1, orderId);
            ps.setString(2, Thread.currentThread().getName());   // 线程名形如 metapool-order-worker-1
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("[demo] audit write failed for order={}", orderId, e);
        } finally {
            datasource.release(conn);
        }
    }
}
