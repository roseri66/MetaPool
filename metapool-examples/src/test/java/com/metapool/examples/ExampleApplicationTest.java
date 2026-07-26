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

    /**
     * 桶容量 5/s、greedy 补充（每 200ms 回 1 个令牌）。因此"放行数恰好等于 5"只在整个循环跑完于
     * 一个补充周期内时成立 —— 机器慢或 CI 有负载时会多放行 1~2 个。断言写成区间而非精确值，
     * 既守住"限流真实生效"这个被测行为，又不把测试绑死在时序上（见坑 P-17）。
     */
    @Test
    void rateLimit_kicksIn_afterCapacity() throws Exception {
        int capacity = 5;
        int requests = 10;
        int ok = 0;
        int limited = 0;
        for (int i = 0; i < requests; i++) {
            int status = mockMvc.perform(post("/orders/order-" + i))
                    .andReturn().getResponse().getStatus();
            if (status == 200) {
                ok++;
            } else if (status == 429) {
                limited++;
            }
        }
        assertTrue(ok >= capacity, "至少应放行一个满桶的量，实得 " + ok);
        assertTrue(ok < requests, "不应全部放行，否则限流没生效，实得 " + ok);
        assertEquals(requests, ok + limited, "每个请求都应落在 200 或 429，实得 ok=" + ok + " limited=" + limited);
        assertTrue(limited > 0, "超出限额的请求应被限流（429）");
    }
}
