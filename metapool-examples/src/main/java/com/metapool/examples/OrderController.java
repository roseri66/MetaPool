package com.metapool.examples;

import com.metapool.common.capability.Pool;
import com.metapool.common.capability.RateLimiter;
import com.metapool.common.manager.ResourceManager;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;

/**
 * 演示业务如何<b>通过控制面的能力接口</b>使用被治理的资源：
 * 先经 {@link RateLimiter} 限流，放行后经 {@link Pool}{@code <Connection>} 取连接落库。
 *
 * <p>注意：业务只依赖 {@code metapool-common} 的能力抽象，不感知 HikariCP / Bucket4j。
 */
@RestController
public class OrderController {

    private final RateLimiter rateLimiter;
    private final Pool<Connection> datasource;

    @SuppressWarnings("unchecked")
    public OrderController(ResourceManager metaPool) {
        this.rateLimiter = (RateLimiter) metaPool.get("order-api");
        this.datasource = (Pool<Connection>) metaPool.get("main");
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
            return ResponseEntity.ok(Map.of("id", id, "status", "OK", "totalOrders", count));
        } finally {
            datasource.release(conn);
        }
    }
}
