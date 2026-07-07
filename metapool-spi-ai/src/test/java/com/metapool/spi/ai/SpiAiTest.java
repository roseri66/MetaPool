package com.metapool.spi.ai;

import com.metapool.common.spi.ExtensionLoader;
import com.metapool.common.spi.SPI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC-17 AI 诊断 SPI 接口契约测试。
 *
 * <p>验证：
 * <ol>
 *   <li>3 个接口均标注 {@link SPI @SPI} 注解</li>
 *   <li>{@link ExtensionLoader} 加载时返回 {@code null}（V1 无实现类）</li>
 *   <li>DTO Builder 模式正常构造</li>
 *   <li>DTO 不可变性（final class，不提供 setter）</li>
 * </ol>
 */
@DisplayName("SPEC-17 AI Diagnosis SPI")
class SpiAiTest {

    // ──────────────────── SPI 注解验证 ────────────────────

    @Nested
    @DisplayName("SPI 注解")
    class SpiAnnotation {

        @Test
        @DisplayName("AiDiagnosisService 标注 @SPI")
        void aiDiagnosisServiceShouldHaveSpiAnnotation() {
            SPI spi = AiDiagnosisService.class.getAnnotation(SPI.class);
            assertNotNull(spi, "AiDiagnosisService must be annotated with @SPI");
        }

        @Test
        @DisplayName("AiTuningAdvisor 标注 @SPI")
        void aiTuningAdvisorShouldHaveSpiAnnotation() {
            SPI spi = AiTuningAdvisor.class.getAnnotation(SPI.class);
            assertNotNull(spi, "AiTuningAdvisor must be annotated with @SPI");
        }

        @Test
        @DisplayName("AiChatService 标注 @SPI")
        void aiChatServiceShouldHaveSpiAnnotation() {
            SPI spi = AiChatService.class.getAnnotation(SPI.class);
            assertNotNull(spi, "AiChatService must be annotated with @SPI");
        }
    }

    // ──────────────────── ExtensionLoader 返回 null ────────────────────

    @Nested
    @DisplayName("ExtensionLoader")
    class ExtensionLoaderTests {

        @Test
        @DisplayName("加载 AiDiagnosisService → 返回 null（无实现）")
        void loadAiDiagnosisServiceShouldReturnNull() {
            AiDiagnosisService service = ExtensionLoader.getExtension(AiDiagnosisService.class);
            assertNull(service, "V1 should have no implementation, ExtensionLoader must return null");
        }

        @Test
        @DisplayName("加载 AiTuningAdvisor → 返回 null（无实现）")
        void loadAiTuningAdvisorShouldReturnNull() {
            AiTuningAdvisor advisor = ExtensionLoader.getExtension(AiTuningAdvisor.class);
            assertNull(advisor, "V1 should have no implementation, ExtensionLoader must return null");
        }

        @Test
        @DisplayName("加载 AiChatService → 返回 null（无实现）")
        void loadAiChatServiceShouldReturnNull() {
            AiChatService chatService = ExtensionLoader.getExtension(AiChatService.class);
            assertNull(chatService, "V1 should have no implementation, ExtensionLoader must return null");
        }
    }

    // ──────────────────── PoolMetricsSnapshot DTO ────────────────────

    @Nested
    @DisplayName("PoolMetricsSnapshot")
    class PoolMetricsSnapshotTests {

        @Test
        @DisplayName("Builder 构造完整快照")
        void shouldBuildCompleteSnapshot() {
            PoolMetricsSnapshot snapshot = PoolMetricsSnapshot.builder()
                    .poolName("metapool-db")
                    .resourceType("DB")
                    .timestamp(1718841600000L)
                    .activeCount(5)
                    .idleCount(3)
                    .pendingCount(2)
                    .totalAcquired(1000L)
                    .totalReleased(995L)
                    .leakDetected(0)
                    .putExtraMetric("connectionTimeoutTotal", 3L)
                    .build();

            assertEquals("metapool-db", snapshot.getPoolName());
            assertEquals("DB", snapshot.getResourceType());
            assertEquals(1718841600000L, snapshot.getTimestamp());
            assertEquals(5, snapshot.getActiveCount());
            assertEquals(3, snapshot.getIdleCount());
            assertEquals(2, snapshot.getPendingCount());
            assertEquals(1000L, snapshot.getTotalAcquired());
            assertEquals(995L, snapshot.getTotalReleased());
            assertEquals(0, snapshot.getLeakDetected());
            assertEquals(8, snapshot.getPoolSize());
            assertEquals(3L, snapshot.getExtraMetrics().get("connectionTimeoutTotal"));
        }

        @Test
        @DisplayName("poolName 为空时抛异常")
        void shouldThrowWhenPoolNameEmpty() {
            assertThrows(IllegalArgumentException.class, () ->
                    PoolMetricsSnapshot.builder()
                            .poolName("")
                            .resourceType("DB")
                            .build());
        }

        @Test
        @DisplayName("resourceType 为空时抛异常")
        void shouldThrowWhenResourceTypeEmpty() {
            assertThrows(IllegalArgumentException.class, () ->
                    PoolMetricsSnapshot.builder()
                            .poolName("test")
                            .resourceType("")
                            .build());
        }

        @Test
        @DisplayName("extraMetrics 默认空 Map")
        void shouldHaveEmptyExtraMetricsByDefault() {
            PoolMetricsSnapshot snapshot = PoolMetricsSnapshot.builder()
                    .poolName("test")
                    .resourceType("THREAD")
                    .build();

            assertTrue(snapshot.getExtraMetrics().isEmpty());
        }
    }

    // ──────────────────── DiagnosisResult DTO ────────────────────

    @Nested
    @DisplayName("DiagnosisResult")
    class DiagnosisResultTests {

        @Test
        @DisplayName("Builder 构造诊断结果")
        void shouldBuildDiagnosisResult() {
            DiagnosisResult result = DiagnosisResult.builder()
                    .severity(DiagnosisResult.Severity.WARNING)
                    .title("连接池利用率偏高")
                    .description("数据库连接池活跃连接数持续接近最大值，存在连接耗尽风险。")
                    .addSuggestion("将 maxPoolSize 从 20 提升至 30")
                    .addSuggestion("检查是否有慢查询导致连接持有时间过长")
                    .build();

            assertEquals(DiagnosisResult.Severity.WARNING, result.getSeverity());
            assertEquals("连接池利用率偏高", result.getTitle());
            assertTrue(result.getDescription().contains("连接耗尽风险"));
            assertEquals(2, result.getSuggestions().size());
        }

        @Test
        @DisplayName("默认严重级别为 NORMAL")
        void shouldDefaultSeverityToNormal() {
            DiagnosisResult result = DiagnosisResult.builder()
                    .title("运行正常")
                    .build();

            assertEquals(DiagnosisResult.Severity.NORMAL, result.getSeverity());
        }

        @Test
        @DisplayName("title 为空时抛异常")
        void shouldThrowWhenTitleEmpty() {
            assertThrows(IllegalArgumentException.class, () ->
                    DiagnosisResult.builder().title("").build());
        }
    }

    // ──────────────────── TuningAdvice DTO ────────────────────

    @Nested
    @DisplayName("TuningAdvice")
    class TuningAdviceTests {

        @Test
        @DisplayName("Builder 构造调优建议")
        void shouldBuildTuningAdvice() {
            TuningAdvice advice = TuningAdvice.builder()
                    .parameterName("maxPoolSize")
                    .currentValue("20")
                    .suggestedValue("30")
                    .reason("当前活跃连接数持续接近上限，增大池容量可降低获取等待时间")
                    .confidence(0.85)
                    .build();

            assertEquals("maxPoolSize", advice.getParameterName());
            assertEquals("20", advice.getCurrentValue());
            assertEquals("30", advice.getSuggestedValue());
            assertTrue(advice.getReason().contains("活跃连接数"));
            assertEquals(0.85, advice.getConfidence(), 0.001);
        }

        @Test
        @DisplayName("默认置信度为 0.5")
        void shouldDefaultConfidenceToHalf() {
            TuningAdvice advice = TuningAdvice.builder()
                    .parameterName("corePoolSize")
                    .build();

            assertEquals(0.5, advice.getConfidence(), 0.001);
        }

        @Test
        @DisplayName("置信度超出范围时抛异常")
        void shouldThrowWhenConfidenceOutOfRange() {
            assertThrows(IllegalArgumentException.class, () ->
                    TuningAdvice.builder()
                            .parameterName("test")
                            .confidence(1.5)
                            .build());
        }

        @Test
        @DisplayName("parameterName 为空时抛异常")
        void shouldThrowWhenParameterNameEmpty() {
            assertThrows(IllegalArgumentException.class, () ->
                    TuningAdvice.builder()
                            .parameterName("")
                            .build());
        }
    }

    // ──────────────────── ChatResponse DTO ────────────────────

    @Nested
    @DisplayName("ChatResponse")
    class ChatResponseTests {

        @Test
        @DisplayName("Builder 构造问答响应")
        void shouldBuildChatResponse() {
            ChatResponse response = ChatResponse.builder()
                    .answer("线程池队列已满通常是因为任务提交速度超过了线程处理速度。建议：1) 增大 maxPoolSize；2) 增大队列容量；3) 检查是否有阻塞任务。")
                    .confidence(0.92)
                    .addReference("https://wiki.example.com/threadpool-tuning")
                    .build();

            assertTrue(response.getAnswer().contains("线程池队列已满"));
            assertEquals(0.92, response.getConfidence(), 0.001);
            assertEquals(1, response.getReferences().size());
        }

        @Test
        @DisplayName("references 默认空列表")
        void shouldHaveEmptyReferencesByDefault() {
            ChatResponse response = ChatResponse.builder()
                    .answer("测试回答")
                    .build();

            assertTrue(response.getReferences().isEmpty());
        }

        @Test
        @DisplayName("answer 为空时抛异常")
        void shouldThrowWhenAnswerEmpty() {
            assertThrows(IllegalArgumentException.class, () ->
                    ChatResponse.builder().answer("").build());
        }

        @Test
        @DisplayName("置信度超出范围时抛异常")
        void shouldThrowWhenConfidenceOutOfRange() {
            assertThrows(IllegalArgumentException.class, () ->
                    ChatResponse.builder()
                            .answer("test")
                            .confidence(-0.1)
                            .build());
        }
    }

    // ──────────────────── 不可变性验证 ────────────────────

    @Nested
    @DisplayName("不可变性")
    class Immutability {

        @Test
        @DisplayName("PoolMetricsSnapshot 为 final class")
        void poolMetricsSnapshotShouldBeFinal() {
            assertTrue(java.lang.reflect.Modifier.isFinal(PoolMetricsSnapshot.class.getModifiers()));
        }

        @Test
        @DisplayName("DiagnosisResult 为 final class")
        void diagnosisResultShouldBeFinal() {
            assertTrue(java.lang.reflect.Modifier.isFinal(DiagnosisResult.class.getModifiers()));
        }

        @Test
        @DisplayName("TuningAdvice 为 final class")
        void tuningAdviceShouldBeFinal() {
            assertTrue(java.lang.reflect.Modifier.isFinal(TuningAdvice.class.getModifiers()));
        }

        @Test
        @DisplayName("ChatResponse 为 final class")
        void chatResponseShouldBeFinal() {
            assertTrue(java.lang.reflect.Modifier.isFinal(ChatResponse.class.getModifiers()));
        }

        @Test
        @DisplayName("DiagnosisResult.suggestions 返回不可变列表")
        void suggestionsShouldBeUnmodifiable() {
            DiagnosisResult result = DiagnosisResult.builder()
                    .title("测试")
                    .addSuggestion("建议1")
                    .build();

            assertThrows(UnsupportedOperationException.class, () ->
                    result.getSuggestions().add("尝试修改"));
        }

        @Test
        @DisplayName("PoolMetricsSnapshot.extraMetrics 返回不可变 Map")
        void extraMetricsShouldBeUnmodifiable() {
            PoolMetricsSnapshot snapshot = PoolMetricsSnapshot.builder()
                    .poolName("test")
                    .resourceType("THREAD")
                    .build();

            assertThrows(UnsupportedOperationException.class, () ->
                    snapshot.getExtraMetrics().put("key", "value"));
        }

        @Test
        @DisplayName("ChatResponse.references 返回不可变列表")
        void referencesShouldBeUnmodifiable() {
            ChatResponse response = ChatResponse.builder()
                    .answer("测试")
                    .addReference("ref1")
                    .build();

            assertThrows(UnsupportedOperationException.class, () ->
                    response.getReferences().add("尝试修改"));
        }
    }
}
