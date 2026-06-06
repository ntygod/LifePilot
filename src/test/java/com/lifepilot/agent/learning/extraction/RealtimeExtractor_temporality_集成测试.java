package com.lifepilot.agent.learning.extraction;

import com.lifepilot.agent.learning.extraction.ExtractionValidator;
import com.lifepilot.agent.learning.extraction.RealtimeExtractor;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.memory.governance.lifecycle.Temporality;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import com.lifepilot.memory.store.scope.ChatTurnMemorySnapshot;
import com.lifepilot.memory.store.scope.ChatTurnMemorySnapshotRepository;
import com.lifepilot.memory.store.scope.MemoryWriteContext;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RealtimeExtractor temporality + expires_at 自动推导行为测试。
 *
 * <p>验证 LLM 响应在四种场景下的处理：</p>
 * <ul>
 *   <li>给 EPHEMERAL 未给 expires_at → 自动填 now + 7 天；</li>
 *   <li>给 SHORT_TERM 未给 expires_at → 自动填 now + 30 天；</li>
 *   <li>给 PERSISTENT → expires_at 留 null；</li>
 *   <li>LLM 未给 temporality → 默认 PERSISTENT；</li>
 *   <li>LLM 给非法 temporality → 降级为 PERSISTENT；</li>
 *   <li>LLM 显式提供 expires_at → 尊重该值，不走自动推导。</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-23
 */
@DisplayName("RealtimeExtractor temporality + expires_at 自动推导测试")
class RealtimeExtractor_temporality_集成测试 {

    private static final Instant FIXED_NOW = Instant.parse("2026-04-23T10:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    private GenerationRouter generationRouter;
    private SemanticMemory semanticMemory;
    private JdbcTemplate jdbcTemplate;
    private PromptRegistry promptRegistry;
    private ChatTurnMemorySnapshotRepository snapshotRepo;
    private RealtimeExtractor extractor;

    @BeforeEach
    void 初始化() {
        generationRouter = mock(GenerationRouter.class);
        semanticMemory = mock(SemanticMemory.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        promptRegistry = mock(PromptRegistry.class);
        snapshotRepo = mock(ChatTurnMemorySnapshotRepository.class);

        when(promptRegistry.render(eq("semantic/entity-extraction"), any()))
                .thenReturn("stub-prompt");
        when(semanticMemory.findAllCurrent(any())).thenReturn(List.of());
        when(snapshotRepo.findByTurnId(anyString()))
                .thenAnswer(inv -> Optional.of(快照(inv.getArgument(0))));

        var properties = new AgentLearningProperties();
        var extractionValidator = new ExtractionValidator(properties);

        extractor = new RealtimeExtractor(
                generationRouter,
                semanticMemory,
                properties,
                extractionValidator,
                jdbcTemplate,
                promptRegistry,
                snapshotRepo,
                FIXED_CLOCK
        );
    }

    // ---------------- EPHEMERAL + SHORT_TERM 自动推导 ----------------

    @Test
    void LLM响应含EPHEMERAL应自动设expires_at为7天后() {
        给出LLM响应("""
                [
                  {
                    "operation": "ADD",
                    "entityName": "最近懒得碰 Rust",
                    "entityType": "PREFERENCE",
                    "description": "临时情绪，短期不想继续",
                    "extractionConfidence": 0.9,
                    "importanceScore": 0.5,
                    "temporality": "EPHEMERAL"
                  }
                ]
                """);

        extractor.extract("sess-1", "turn-1", "我最近懒得碰 Rust", null);

        var captured = 捕获upsert实体();
        assertThat(captured.temporality()).isEqualTo(Temporality.EPHEMERAL);
        assertThat(captured.expiresAt()).isEqualTo(FIXED_NOW.plus(Duration.ofDays(7)));
    }

    @Test
    void LLM响应含SHORT_TERM应自动设expires_at为30天后() {
        给出LLM响应("""
                [
                  {
                    "operation": "ADD",
                    "entityName": "这个月学 Rust",
                    "entityType": "GOAL",
                    "description": "短期学习计划",
                    "extractionConfidence": 0.85,
                    "importanceScore": 0.7,
                    "temporality": "SHORT_TERM"
                  }
                ]
                """);

        extractor.extract("sess-2", "turn-2", "我这个月在学 Rust", null);

        var captured = 捕获upsert实体();
        assertThat(captured.temporality()).isEqualTo(Temporality.SHORT_TERM);
        assertThat(captured.expiresAt()).isEqualTo(FIXED_NOW.plus(Duration.ofDays(30)));
    }

    // ---------------- PERSISTENT 不自动填过期 ----------------

    @Test
    void LLM响应含PERSISTENT时expires_at应保持为null() {
        给出LLM响应("""
                [
                  {
                    "operation": "ADD",
                    "entityName": "不吃牛肉",
                    "entityType": "PREFERENCE",
                    "description": "长期饮食偏好",
                    "extractionConfidence": 0.9,
                    "importanceScore": 0.8,
                    "temporality": "PERSISTENT"
                  }
                ]
                """);

        extractor.extract("sess-3", "turn-3", "我不吃牛肉", null);

        var captured = 捕获upsert实体();
        assertThat(captured.temporality()).isEqualTo(Temporality.PERSISTENT);
        assertThat(captured.expiresAt()).isNull();
    }

    // ---------------- LLM 未给 temporality → 默认 PERSISTENT ----------------

    @Test
    void LLM未给temporality应默认PERSISTENT且不设expires_at() {
        给出LLM响应("""
                [
                  {
                    "operation": "ADD",
                    "entityName": "阿里",
                    "entityType": "ORGANIZATION",
                    "description": "用户所在公司",
                    "extractionConfidence": 0.9,
                    "importanceScore": 0.7
                  }
                ]
                """);

        extractor.extract("sess-4", "turn-4", "我在阿里工作", null);

        var captured = 捕获upsert实体();
        assertThat(captured.temporality()).isEqualTo(Temporality.PERSISTENT);
        assertThat(captured.expiresAt()).isNull();
    }

    // ---------------- LLM 非法 temporality → 降级 PERSISTENT ----------------

    @Test
    void LLM给非法temporality应降级为PERSISTENT() {
        给出LLM响应("""
                [
                  {
                    "operation": "ADD",
                    "entityName": "测试实体",
                    "entityType": "TOPIC",
                    "description": "description 足够长以通过验证",
                    "extractionConfidence": 0.9,
                    "importanceScore": 0.5,
                    "temporality": "WEIRD_VALUE"
                  }
                ]
                """);

        extractor.extract("sess-5", "turn-5", "测试非法值", null);

        var captured = 捕获upsert实体();
        assertThat(captured.temporality()).isEqualTo(Temporality.PERSISTENT);
        assertThat(captured.expiresAt()).isNull();
    }

    // ---------------- LLM 显式提供 expires_at → 尊重 ----------------

    @Test
    void LLM显式提供expires_at时应尊重该值而非自动推导() {
        String explicit = "2026-06-01T00:00:00Z";
        给出LLM响应("""
                [
                  {
                    "operation": "ADD",
                    "entityName": "下个月出差",
                    "entityType": "EVENT",
                    "description": "短期计划，LLM 指定结束时间",
                    "extractionConfidence": 0.9,
                    "importanceScore": 0.6,
                    "temporality": "SHORT_TERM",
                    "expires_at": "%s"
                  }
                ]
                """.formatted(explicit));

        extractor.extract("sess-6", "turn-6", "下个月要出差", null);

        var captured = 捕获upsert实体();
        assertThat(captured.temporality()).isEqualTo(Temporality.SHORT_TERM);
        assertThat(captured.expiresAt()).isEqualTo(Instant.parse(explicit));
        // 不等于自动推导的 +30 天
        assertThat(captured.expiresAt())
                .isNotEqualTo(FIXED_NOW.plus(Duration.ofDays(30)));
    }

    // ---------------- LLM 非法 expires_at 格式 → 回退自动推导 ----------------

    @Test
    void LLM给非法expires_at时应回退到按temporality自动计算() {
        给出LLM响应("""
                [
                  {
                    "operation": "ADD",
                    "entityName": "模糊日期",
                    "entityType": "TOPIC",
                    "description": "日期格式 LLM 胡写，走兜底",
                    "extractionConfidence": 0.9,
                    "importanceScore": 0.5,
                    "temporality": "EPHEMERAL",
                    "expires_at": "不是合法 ISO"
                  }
                ]
                """);

        extractor.extract("sess-7", "turn-7", "测试非法日期", null);

        var captured = 捕获upsert实体();
        assertThat(captured.temporality()).isEqualTo(Temporality.EPHEMERAL);
        assertThat(captured.expiresAt()).isEqualTo(FIXED_NOW.plus(Duration.ofDays(7)));
    }

    // ---------------- 辅助 ----------------

    private void 给出LLM响应(String content) {
        var response = new LlmResponse(content, null, null, List.of(), Map.of(), 10, 20, null, 0, "mock-provider", "mock-model", 100L, false);
        when(generationRouter.call(
                anyString(),
                anyString(),
                any(),
                any(),
                any(),
                eq(GenerationCapability.CHAT),
                any(),
                eq(true)))
                .thenReturn(response);
    }

    private TemporalEntity 捕获upsert实体() {
        var captor = ArgumentCaptor.forClass(TemporalEntity.class);
        org.mockito.Mockito.verify(semanticMemory).upsertWithConflictDetection(
                captor.capture(), anyString(), any(MemoryWriteContext.class));
        return captor.getValue();
    }

    private ChatTurnMemorySnapshot 快照(String turnId) {
        return new ChatTurnMemorySnapshot(
                turnId,
                "sess-x",
                "memory-space-personal-default",
                "memory-space-experience-default",
                null,
                null,
                List.of("memory-space-personal-default", "memory-space-experience-default"),
                List.of(),
                true,
                false,
                true,
                Map.of(),
                FIXED_NOW
        );
    }
}
