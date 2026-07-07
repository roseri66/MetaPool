package com.smartpool.pool.rate.limit;

import com.smartpool.common.lifecycle.PoolStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TokenBucketRateLimiter} 单元测试。
 */
@DisplayName("TokenBucketRateLimiter")
class TokenBucketRateLimiterTest {

    @Nested
    @DisplayName("基本令牌桶")
    class BasicTokenBucketTests {

        @Test
        @DisplayName("有令牌时应放行")
        void shouldPassWhenTokensAvailable() {
            RateLimiterConfig config = new RateLimiterConfig();
            config.setPermitsPerSecond(100);
            config.setWarmUpSeconds(0);

            TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(config);
            limiter.init();

            // 初始有 100 个令牌（1 秒突发容量）
            assertTrue(limiter.tryAcquire());
            assertEquals(1, limiter.getPassCount());
        }

        @Test
        @DisplayName("令牌耗尽后应拒绝")
        void shouldRejectWhenNoTokens() {
            RateLimiterConfig config = new RateLimiterConfig();
            config.setPermitsPerSecond(10);
            config.setWarmUpSeconds(0);

            TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(config);
            limiter.init();

            // 耗尽所有令牌（初始 = permitsPerSecond = 10）
            int passed = 0;
            for (int i = 0; i < 100; i++) {
                if (limiter.tryAcquire()) {
                    passed++;
                }
            }

            assertEquals(10, passed, "应恰好消耗所有初始令牌");
            assertTrue(limiter.getRejectCount() > 0, "耗尽后应拒绝");
        }

        @Test
        @DisplayName("等待后令牌应自动补充")
        void shouldRefillTokensOverTime() throws Exception {
            RateLimiterConfig config = new RateLimiterConfig();
            config.setPermitsPerSecond(100); // 每秒 100 个令牌
            config.setWarmUpSeconds(0);

            TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(config);
            limiter.init();

            // 耗尽令牌
            for (int i = 0; i < 100; i++) {
                limiter.tryAcquire();
            }

            assertFalse(limiter.tryAcquire(), "令牌应已耗尽");

            // 等待 ~100ms，应补充约 10 个令牌
            Thread.sleep(100);

            int passed = 0;
            for (int i = 0; i < 20; i++) {
                if (limiter.tryAcquire()) {
                    passed++;
                }
            }

            assertTrue(passed >= 8 && passed <= 12,
                    "100ms 应补充约 10 个令牌（±20%），实际=" + passed);
        }
    }

    @Nested
    @DisplayName("限流精度")
    class RateAccuracyTests {

        @Test
        @DisplayName("实际 QPS 与配置偏差应 ≤ 5%（持续速率，不含初始突发）")
        void shouldBeWithinFivePercentAccuracy() throws Exception {
            RateLimiterConfig config = new RateLimiterConfig();
            config.setPermitsPerSecond(100);
            config.setWarmUpSeconds(0);

            TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(config);
            limiter.init();

            // 先耗尽初始突发令牌
            for (int i = 0; i < 100; i++) {
                limiter.tryAcquire();
            }

            // 运行 2 秒，统计稳定状态下的通过数
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            int passed = 0;

            while (System.nanoTime() < deadline) {
                if (limiter.tryAcquire()) {
                    passed++;
                }
                Thread.sleep(0, 1000);
            }

            // 期望：2 秒 × 100/s = 200
            int expected = 200;
            double deviation = Math.abs(passed - expected) / (double) expected;

            assertTrue(deviation <= 0.05,
                    "稳态 QPS 偏差应 ≤ 5%，expected≈" + expected + ", actual=" + passed
                            + ", deviation=" + String.format("%.2f%%", deviation * 100));
        }
    }

    @Nested
    @DisplayName("预热机制")
    class WarmUpTests {

        @Test
        @DisplayName("预热期间令牌速率应从 0 开始线性递增")
        void shouldWarmUpLinearly() throws Exception {
            RateLimiterConfig config = new RateLimiterConfig();
            config.setPermitsPerSecond(100);
            config.setWarmUpSeconds(2); // 2 秒预热

            TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(config);
            limiter.init();

            // 刚初始化时可用令牌为 0（预热模式）
            assertEquals(0.0, limiter.getAvailableTokens(), 0.01);

            // 等待 1 秒（预热一半），应产生约 50 个令牌（速率 = 50/s）
            Thread.sleep(1000);

            int midPassed = 0;
            for (int i = 0; i < 60; i++) {
                if (limiter.tryAcquire()) {
                    midPassed++;
                }
            }
            // 1秒 × 平均速率(0+50)/2 = 25 令牌，加上 1 秒 × 速率 = 约 50 令牌
            // 简化验证：预热期间令牌数应 < permitsPerSecond（100）
            assertTrue(midPassed < 80,
                    "预热中间阶段应少于满速率，actual=" + midPassed);

            // 等待预热完成（再等 1.5 秒确保预热结束）
            Thread.sleep(1500);

            int fullPassed = 0;
            for (int i = 0; i < 110; i++) {
                if (limiter.tryAcquire()) {
                    fullPassed++;
                }
            }
            assertTrue(fullPassed >= 90,
                    "预热结束后应有满速率令牌补充，actual=" + fullPassed);
        }
    }

    @Nested
    @DisplayName("动态调参")
    class DynamicConfigTests {

        @Test
        @DisplayName("减小 permitsPerSecond 应在下一补充周期生效")
        void shouldApplyDecreaseOnNextRefill() throws Exception {
            RateLimiterConfig config = new RateLimiterConfig();
            config.setPermitsPerSecond(100);
            config.setWarmUpSeconds(0);

            TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(config);
            limiter.init();

            // 耗尽初始令牌
            for (int i = 0; i < 100; i++) {
                limiter.tryAcquire();
            }

            // 降低速率
            limiter.setPermitsPerSecond(10);

            // 等待 200ms，按新速率应补充约 2 个令牌
            Thread.sleep(200);

            int passed = 0;
            for (int i = 0; i < 5; i++) {
                if (limiter.tryAcquire()) {
                    passed++;
                }
            }

            assertTrue(passed >= 1 && passed <= 3,
                    "降低速率后补充量应减少，实际=" + passed);
        }
    }

    @Nested
    @DisplayName("生命周期")
    class LifecycleTests {

        @Test
        @DisplayName("init 应初始化令牌桶")
        void shouldInitializeOnInit() {
            RateLimiterConfig config = new RateLimiterConfig();
            config.setPermitsPerSecond(50);
            config.setWarmUpSeconds(0);

            TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(config);
            limiter.init();

            assertTrue(limiter.isRunning());
            assertTrue(limiter.getAvailableTokens() >= 0);

            PoolStats stats = limiter.stats();
            assertNotNull(stats);
        }

        @Test
        @DisplayName("destroy 后应拒绝所有请求")
        void shouldRejectAfterDestroy() {
            RateLimiterConfig config = new RateLimiterConfig();
            config.setPermitsPerSecond(100);
            config.setWarmUpSeconds(0);

            TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(config);
            limiter.init();

            limiter.destroy();

            assertFalse(limiter.isRunning());
            assertFalse(limiter.tryAcquire(), "destroy 后应拒绝");
            assertEquals(1, limiter.getRejectCount());
        }

        @Test
        @DisplayName("stats 应返回正确的计数")
        void shouldReturnCorrectStats() {
            RateLimiterConfig config = new RateLimiterConfig();
            config.setPermitsPerSecond(10);
            config.setWarmUpSeconds(0);

            TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(config);
            limiter.init();

            for (int i = 0; i < 20; i++) {
                limiter.tryAcquire();
            }

            PoolStats stats = limiter.stats();
            assertEquals(10, stats.getTotalAcquired());
            assertEquals(10, stats.getTotalReleased()); // rejectCount 存放在 totalReleased
        }
    }

    @Nested
    @DisplayName("并发安全")
    class ConcurrencyTests {

        @Test
        @DisplayName("多线程并发 acquire 应线程安全")
        void shouldBeThreadSafe() throws Exception {
            RateLimiterConfig config = new RateLimiterConfig();
            config.setPermitsPerSecond(1000);
            config.setWarmUpSeconds(0);

            TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(config);
            limiter.init();

            int threads = 10;
            int attemptsPerThread = 1000;
            CountDownLatch latch = new CountDownLatch(threads);
            AtomicInteger totalPassed = new AtomicInteger(0);

            for (int i = 0; i < threads; i++) {
                new Thread(() -> {
                    int passed = 0;
                    for (int j = 0; j < attemptsPerThread; j++) {
                        if (limiter.tryAcquire()) {
                            passed++;
                        }
                    }
                    totalPassed.addAndGet(passed);
                    latch.countDown();
                }).start();
            }

            latch.await(10, TimeUnit.SECONDS);

            // 不应超过 初始容量(1000) + 补充量 ≈ 1000 tokens
            assertTrue(totalPassed.get() <= 1100,
                    "不应超出令牌生成量，实际=" + totalPassed.get());
        }
    }
}
