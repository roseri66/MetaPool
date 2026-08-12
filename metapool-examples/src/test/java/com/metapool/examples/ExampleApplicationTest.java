package com.metapool.examples;

import com.metapool.common.manager.ResourceManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 示例应用冒烟测试：验证 YAML → 控制面 → 三个适配器全链路装配，且限流真实生效。
 *
 * <h3>为什么每个用例都要重建上下文</h3>
 * <p>被治理的限流器是<b>有状态</b>的（一个 5/s 的令牌桶），且在整个 Spring 上下文里只有一个实例。
 * {@code rateLimit_kicksIn_afterCapacity} 会把桶抽干，排在它后面的用例于是必然收到 429 ——
 * 表现为「换个执行顺序就假失败」。这不是时序问题（不能靠 sleep 等补充，那会踩坑 P-17），
 * 是<b>共享可变状态的测试隔离问题</b>，所以按隔离来解：每个用例拿一个干净的上下文。
 * 代价是多两次上下文启动（约 7s），换来的是与执行顺序无关的确定性。见坑 P-21。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class ExampleApplicationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ResourceManager metaPool;

    @Test
    void contextLoads_allThreeResourcesGoverned() {
        assertEquals(3, metaPool.resources().size());
        assertTrue(metaPool.find("main").isPresent());
        assertTrue(metaPool.find("order-api").isPresent());
        assertTrue(metaPool.find("order-worker").isPresent());
    }

    /**
     * 审计写入走被治理的线程池：响应里带回 {@code audit=SUBMITTED}，
     * 说明业务确实拿到了 {@code ManagedExecutor} 并成功提交了任务。
     *
     * <p>只断言"已受理"，不断言审计行已落库 —— 那是异步的，等它就等于把断言绑在挂钟上（坑 P-17）。
     */
    @Test
    void order_submitsAuditToGovernedExecutor() throws Exception {
        mockMvc.perform(post("/orders/audit-demo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.audit").value("SUBMITTED"));
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
