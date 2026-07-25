package com.metapool.examples;

import com.metapool.common.manager.ResourceManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 示例应用冒烟测试：验证 YAML → 控制面 → 两个适配器全链路装配，且限流真实生效。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExampleApplicationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ResourceManager metaPool;

    @Test
    void contextLoads_bothResourcesGoverned() {
        assertEquals(2, metaPool.resources().size());
        assertTrue(metaPool.find("main").isPresent());
        assertTrue(metaPool.find("order-api").isPresent());
    }

    @Test
    void rateLimit_kicksIn_afterCapacity() throws Exception {
        int ok = 0;
        int limited = 0;
        // 桶容量 5/s，10 次快速请求应出现限流
        for (int i = 0; i < 10; i++) {
            int status = mockMvc.perform(post("/orders/order-" + i))
                    .andReturn().getResponse().getStatus();
            if (status == 200) {
                ok++;
            } else if (status == 429) {
                limited++;
            }
        }
        assertEquals(5, ok, "应有 5 个请求在限额内放行");
        assertTrue(limited > 0, "超出限额的请求应被限流（429）");
    }
}
