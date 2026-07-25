package com.metapool.starter.endpoint;

import com.metapool.common.manager.ResourceManager;
import com.metapool.common.resource.ManagedResource;
import com.metapool.common.resource.Tunable;
import com.metapool.common.stats.TuneResult;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Actuator 端点 {@code /actuator/metapool}。
 *
 * <ul>
 *   <li>{@code GET /actuator/metapool} — 列出所有被治理资源（类型 / 健康 / 是否可调）</li>
 *   <li>{@code POST /actuator/metapool/{name}}，body {@code {"key":"maximum-pool-size","value":"40"}}
 *       — 运行时动态调参（🎯 头牌能力）</li>
 * </ul>
 *
 * @since 2.0.0
 */
@Endpoint(id = "metapool")
public class MetaPoolEndpoint {

    private final ResourceManager manager;

    public MetaPoolEndpoint(ResourceManager manager) {
        this.manager = manager;
    }

    @ReadOperation
    public Map<String, Object> list() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (ManagedResource r : manager.resources()) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("type", r.type());
            info.put("health", r.health().status().name());
            info.put("tunable", (r instanceof Tunable t) ? t.tunableKeys() : java.util.Set.of());
            out.put(r.name(), info);
        }
        return out;
    }

    @WriteOperation
    public Map<String, Object> tune(@Selector String name, String key, String value) {
        TuneResult result = manager.tune(name, Map.of(key, value));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", result.success());
        out.put("applied", result.applied());
        out.put("rejected", result.rejected());
        return out;
    }
}
