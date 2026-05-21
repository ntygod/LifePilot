package com.lifepilot.memory.consolidation;

import com.lifepilot.agent.learning.consolidation.ConsolidationStats;
import com.lifepilot.agent.learning.consolidation.EpisodicToProceduralConsolidator;
import com.lifepilot.embedding.router.EmbeddingRouter;
import com.lifepilot.embedding.router.EmbeddingUseCase;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.LlmScene;
import com.lifepilot.llm.LlmUnavailableException;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.store.procedural.ProceduralMemory;
import com.lifepilot.memory.store.procedural.ProcedureTemplate;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * EpisodicToProceduralConsolidator 单元测试 — 情景→程序巩固器。
 *
 * <p>覆盖正常巩固流程、空轨迹跳过、聚类为空、LLM 不可用降级、
 * 模板去重逻辑和统计结果返回等关键路径。</p>
 *
 * @author zsg
 * @since 2026-04-03
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EpisodicToProceduralConsolidator 单元测试")
class EpisodicToProceduralConsolidator_单元测试 {

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private ProceduralMemory proceduralMemory;
    @Mock
    private GenerationRouter generationRouter;
    @Mock
    private EmbeddingRouter embeddingRouter;
    @Mock
    private PromptRegistry promptRegistry;

    private MemoryProperties properties;
    private EpisodicToProceduralConsolidator consolidator;

    @BeforeEach
    void 初始化() {
        properties = new MemoryProperties();
        properties.getConsolidation().setLookbackDays(7);
        properties.getConsolidation().setMinExecutionSteps(2);
        properties.getConsolidation().setMinClusterSize(2);
        properties.getConsolidation().setMaxTemplatesPerRun(10);
        properties.getConsolidation().setClusterSimilarityThreshold(0.85f);

        consolidator = new EpisodicToProceduralConsolidator(
                jdbcTemplate, proceduralMemory, generationRouter,
                embeddingRouter, properties, promptRegistry);
    }

    // ========== 辅助方法 ==========

    /**
     * 模拟上次巩固时间查询 — 返回空列表表示无历史记录。
     */
    private void 模拟无巩固历史() {
        when(jdbcTemplate.queryForList(
                contains("memory_consolidation_log"),
                eq(String.class),
                eq("EPISODIC_TO_PROCEDURAL")
        )).thenReturn(Collections.emptyList());
    }

    /**
     * 模拟 queryEligibleTraces 返回指定数量的轨迹。
     * JdbcTemplate.query(String, RowMapper, Object...) 用于 agent_traces 查询。
     */
    @SuppressWarnings("unchecked")
    private void 模拟符合条件的轨迹(int count) {
        // 使用 thenAnswer 以便灵活构造每个轨迹
        when(jdbcTemplate.query(contains("agent_traces"), any(RowMapper.class), any(), anyInt()))
                .thenAnswer(invocation -> {
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    var result = new java.util.ArrayList<>();
                    for (int i = 0; i < count; i++) {
                        // 使用 mock ResultSet 构造 TraceInfo
                        var rs = mock(java.sql.ResultSet.class);
                        when(rs.getString("id")).thenReturn("trace-" + i);
                        when(rs.getString("user_message")).thenReturn("测试目标-" + i);
                        when(rs.getString("created_at")).thenReturn("2026-04-01T00:00:00Z");
                        result.add(mapper.mapRow(rs, i));
                    }
                    return result;
                });
    }

    /**
     * 模拟 extractToolSequence 返回工具调用序列。
     * JdbcTemplate.query(String, RowMapper, Object...) 用于 agent_trace_steps 查询。
     */
    @SuppressWarnings("unchecked")
    private void 模拟工具调用序列(String... toolIds) {
        when(jdbcTemplate.query(contains("agent_trace_steps"), any(RowMapper.class), anyString()))
                .thenAnswer(invocation -> {
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    var result = new java.util.ArrayList<>();
                    for (int i = 0; i < toolIds.length; i++) {
                        var rs = mock(java.sql.ResultSet.class);
                        when(rs.getString("tool_id")).thenReturn(toolIds[i]);
                        when(rs.getString("tool_input_json")).thenReturn("{\"key\":\"value\"}");
                        result.add(mapper.mapRow(rs, i));
                    }
                    return result;
                });
    }

    /**
     * 模拟工具调用序列为空（所有轨迹无工具调用步骤）。
     */
    @SuppressWarnings("unchecked")
    private void 模拟空工具调用序列() {
        when(jdbcTemplate.query(contains("agent_trace_steps"), any(RowMapper.class), anyString()))
                .thenReturn(Collections.emptyList());
    }

    /**
     * 生成指定维度的单位向量，方向由 seed 决定。
     */
    private float[] 生成向量(int dim, float seed) {
        float[] v = new float[dim];
        for (int i = 0; i < dim; i++) {
            v[i] = (float) Math.sin(seed + i);
        }
        return v;
    }

    /**
     * 生成两个高相似度向量（接近相同方向）。
     */
    private float[] 生成相似向量(float[] base) {
        float[] v = new float[base.length];
        for (int i = 0; i < base.length; i++) {
            v[i] = base[i] + 0.001f; // 微小偏移，保持高相似度
        }
        return v;
    }

    /**
     * 构造 LLM 返回的 JSON 模板提炼结果。
     */
    private String 构造模板提炼JSON(String name, String description, String triggerIntent) {
        return """
                {
                  "name": "%s",
                  "description": "%s",
                  "triggerIntent": "%s",
                  "steps": [
                    {
                      "toolId": "search-web",
                      "action": "搜索",
                      "parameterTemplate": {"query": "${keyword}"},
                      "description": "搜索相关信息"
                    },
                    {
                      "toolId": "summarize",
                      "action": "摘要",
                      "parameterTemplate": {"content": "${searchResult}"},
                      "description": "摘要搜索结果"
                    }
                  ]
                }
                """.formatted(name, description, triggerIntent);
    }

    /**
     * 模拟巩固日志写入（logConsolidation 使用 JdbcTemplate.update）。
     */
    private void 模拟巩固日志写入() {
        when(jdbcTemplate.update(contains("memory_consolidation_log"),
                any(Object[].class))).thenReturn(1);
    }

    // ========== 测试分组 ==========

    @Nested
    @DisplayName("无符合条件轨迹时的行为")
    class 无轨迹场景 {

        @Test
        void 无符合条件轨迹时应跳过并返回零计数统计() {
            // given
            模拟无巩固历史();
            模拟符合条件的轨迹(0);

            // when
            ConsolidationStats stats = consolidator.consolidate();

            // then
            assertThat(stats.consolidationType()).isEqualTo("EPISODIC_TO_PROCEDURAL");
            assertThat(stats.conversationsAnalyzed()).isZero();
            assertThat(stats.templatesCreated()).isZero();
            assertThat(stats.templatesUpdated()).isZero();
            assertThat(stats.elapsedMs()).isGreaterThanOrEqualTo(0);

            // 不应调用嵌入或生成路由
            verifyNoInteractions(embeddingRouter);
            verifyNoInteractions(generationRouter);
            verifyNoInteractions(proceduralMemory);
        }

        @Test
        @SuppressWarnings("unchecked")
        void 有轨迹但工具调用序列全为空时应返回零模板计数() {
            // given
            模拟无巩固历史();
            模拟符合条件的轨迹(3);
            模拟空工具调用序列();

            // when
            ConsolidationStats stats = consolidator.consolidate();

            // then — traceSequences 为空，但 traces 有 3 条，conversationsAnalyzed 等于 traces.size()
            assertThat(stats.conversationsAnalyzed()).isEqualTo(3);
            assertThat(stats.templatesCreated()).isZero();
            verifyNoInteractions(embeddingRouter);
            verifyNoInteractions(generationRouter);
        }
    }

    @Nested
    @DisplayName("正常巩固流程")
    class 正常流程 {

        @Test
        void 单聚类正常提炼应创建一个模板() {
            // given
            模拟无巩固历史();
            模拟符合条件的轨迹(3);
            模拟工具调用序列("search-web", "summarize");

            // 向量化 — 3 个轨迹返回高度相似的向量（形成一个聚类）
            float[] baseVector = 生成向量(8, 1.0f);
            when(embeddingRouter.embed(anyString(), eq(EmbeddingUseCase.MEMORY), isNull(), isNull()))
                    .thenReturn(baseVector)
                    .thenReturn(生成相似向量(baseVector))
                    .thenReturn(生成相似向量(baseVector))
                    // 去重检查时 triggerIntent 的向量化
                    .thenReturn(生成向量(8, 99.0f));

            // PromptRegistry 渲染
            when(promptRegistry.render(eq("memory/procedural-extraction"), any()))
                    .thenReturn("测试提示词");

            // LLM 生成
            String json = 构造模板提炼JSON("搜索摘要流程", "搜索并摘要信息", "帮我搜索并总结");
            LlmResponse llmResponse = new LlmResponse(json, null, null, List.of(), Map.of(), 100, 200, null, 0, "provider-1", "model-1", 500, false);
            when(generationRouter.call(
                    eq(LlmScene.KNOWLEDGE_EXTRACTION),
                    anyString(), isNull(), isNull(), isNull(),
                    eq(GenerationCapability.CHAT), isNull()))
                    .thenReturn(llmResponse);

            // 去重 — 无已有模板
            when(jdbcTemplate.query(contains("procedure_templates"), any(RowMapper.class)))
                    .thenReturn(Collections.emptyList());

            // when
            ConsolidationStats stats = consolidator.consolidate();

            // then
            assertThat(stats.templatesCreated()).isEqualTo(1);
            assertThat(stats.templatesUpdated()).isZero();
            assertThat(stats.conversationsAnalyzed()).isEqualTo(3);

            // 验证模板保存
            ArgumentCaptor<ProcedureTemplate> captor = ArgumentCaptor.forClass(ProcedureTemplate.class);
            verify(proceduralMemory).save(captor.capture());
            ProcedureTemplate saved = captor.getValue();
            assertThat(saved.name()).isEqualTo("搜索摘要流程");
            assertThat(saved.triggerIntent()).isEqualTo("帮我搜索并总结");
            assertThat(saved.steps()).hasSize(2);
            assertThat(saved.sourceTraceIds()).hasSize(3);
            assertThat(saved.successRate()).isEqualTo(0.0f);
            assertThat(saved.useCount()).isZero();
        }

        @Test
        void 多个聚类应分别提炼模板() {
            // given
            模拟无巩固历史();
            模拟符合条件的轨迹(4);
            模拟工具调用序列("tool-a", "tool-b");

            // 向量化 — 前 2 个相似（聚类 A），后 2 个相似（聚类 B），两组差异大
            float[] groupA = 生成向量(8, 1.0f);
            float[] groupB = 生成向量(8, 100.0f); // 方向差异很大
            when(embeddingRouter.embed(anyString(), eq(EmbeddingUseCase.MEMORY), isNull(), isNull()))
                    // 4 个轨迹的序列向量化
                    .thenReturn(groupA)
                    .thenReturn(生成相似向量(groupA))
                    .thenReturn(groupB)
                    .thenReturn(生成相似向量(groupB))
                    // 去重检查：两次 triggerIntent 向量化
                    .thenReturn(生成向量(8, 200.0f))
                    .thenReturn(生成向量(8, 300.0f));

            when(promptRegistry.render(eq("memory/procedural-extraction"), any()))
                    .thenReturn("测试提示词");

            String jsonA = 构造模板提炼JSON("流程A", "描述A", "意图A");
            String jsonB = 构造模板提炼JSON("流程B", "描述B", "意图B");
            when(generationRouter.call(
                    eq(LlmScene.KNOWLEDGE_EXTRACTION),
                    anyString(), isNull(), isNull(), isNull(),
                    eq(GenerationCapability.CHAT), isNull()))
                    .thenReturn(new LlmResponse(jsonA, null, null, List.of(), Map.of(), 100, 200, null, 0, "p", "m", 500, false))
                    .thenReturn(new LlmResponse(jsonB, null, null, List.of(), Map.of(), 100, 200, null, 0, "p", "m", 500, false));

            // 去重 — 无已有模板
            when(jdbcTemplate.query(contains("procedure_templates"), any(RowMapper.class)))
                    .thenReturn(Collections.emptyList());

            // when
            ConsolidationStats stats = consolidator.consolidate();

            // then
            assertThat(stats.templatesCreated()).isEqualTo(2);
            verify(proceduralMemory, times(2)).save(any(ProcedureTemplate.class));
        }
    }

    @Nested
    @DisplayName("聚类结果为空或不满足最小聚类大小")
    class 聚类不足场景 {

        @Test
        void 所有轨迹向量差异大导致无符合条件聚类时应返回零模板() {
            // given
            模拟无巩固历史();
            模拟符合条件的轨迹(3);
            模拟工具调用序列("tool-a");

            // 3 个完全不同方向的向量，两两相似度 < 阈值 → 每个单独成聚类 → 大小 1 < minClusterSize(2)
            when(embeddingRouter.embed(anyString(), eq(EmbeddingUseCase.MEMORY), isNull(), isNull()))
                    .thenReturn(生成向量(8, 0.0f))
                    .thenReturn(生成向量(8, 100.0f))
                    .thenReturn(生成向量(8, 200.0f));

            // when
            ConsolidationStats stats = consolidator.consolidate();

            // then
            assertThat(stats.templatesCreated()).isZero();
            assertThat(stats.templatesUpdated()).isZero();
            assertThat(stats.conversationsAnalyzed()).isEqualTo(3);
            verifyNoInteractions(generationRouter);
            verifyNoInteractions(proceduralMemory);
        }
    }

    @Nested
    @DisplayName("LLM 不可用时的降级行为")
    class LLM不可用场景 {

        @Test
        void 向量化阶段LLM不可用应返回零模板() {
            // given
            模拟无巩固历史();
            模拟符合条件的轨迹(2);
            模拟工具调用序列("tool-a", "tool-b");

            // 向量化抛出 LlmUnavailableException
            when(embeddingRouter.embed(anyString(), eq(EmbeddingUseCase.MEMORY), isNull(), isNull()))
                    .thenThrow(new LlmUnavailableException("无可用向量服务", "embedding", List.of()));

            // when
            ConsolidationStats stats = consolidator.consolidate();

            // then
            assertThat(stats.templatesCreated()).isZero();
            assertThat(stats.templatesUpdated()).isZero();
            verifyNoInteractions(generationRouter);
            verifyNoInteractions(proceduralMemory);
        }

        @Test
        void 模板提炼阶段LLM不可用应中止剩余提炼并返回已完成数() {
            // given
            模拟无巩固历史();
            模拟符合条件的轨迹(2);
            模拟工具调用序列("search", "summarize");

            // 高相似向量形成一个聚类
            float[] baseVector = 生成向量(8, 1.0f);
            when(embeddingRouter.embed(anyString(), eq(EmbeddingUseCase.MEMORY), isNull(), isNull()))
                    .thenReturn(baseVector)
                    .thenReturn(生成相似向量(baseVector));

            when(promptRegistry.render(eq("memory/procedural-extraction"), any()))
                    .thenReturn("测试提示词");

            // LLM 调用抛出异常
            when(generationRouter.call(
                    eq(LlmScene.KNOWLEDGE_EXTRACTION),
                    anyString(), isNull(), isNull(), isNull(),
                    eq(GenerationCapability.CHAT), isNull()))
                    .thenThrow(new LlmUnavailableException("无可用生成服务", LlmScene.KNOWLEDGE_EXTRACTION, List.of()));

            // when
            ConsolidationStats stats = consolidator.consolidate();

            // then
            assertThat(stats.templatesCreated()).isZero();
            verifyNoInteractions(proceduralMemory);
        }

        @Test
        void 单个聚类提炼失败不应阻塞后续聚类() {
            // given
            模拟无巩固历史();
            模拟符合条件的轨迹(4);
            模拟工具调用序列("tool-a", "tool-b");

            // 两组聚类
            float[] groupA = 生成向量(8, 1.0f);
            float[] groupB = 生成向量(8, 100.0f);
            when(embeddingRouter.embed(anyString(), eq(EmbeddingUseCase.MEMORY), isNull(), isNull()))
                    .thenReturn(groupA)
                    .thenReturn(生成相似向量(groupA))
                    .thenReturn(groupB)
                    .thenReturn(生成相似向量(groupB))
                    // 第二个聚类去重的向量化
                    .thenReturn(生成向量(8, 300.0f));

            when(promptRegistry.render(eq("memory/procedural-extraction"), any()))
                    .thenReturn("测试提示词");

            // 第一个聚类的 LLM 调用抛出普通异常（非 LlmUnavailableException），第二个成功
            String jsonB = 构造模板提炼JSON("流程B", "描述B", "意图B");
            when(generationRouter.call(
                    eq(LlmScene.KNOWLEDGE_EXTRACTION),
                    anyString(), isNull(), isNull(), isNull(),
                    eq(GenerationCapability.CHAT), isNull()))
                    .thenThrow(new RuntimeException("JSON 解析失败"))
                    .thenReturn(new LlmResponse(jsonB, null, null, List.of(), Map.of(), 100, 200, null, 0, "p", "m", 500, false));

            // 去重 — 无已有模板
            when(jdbcTemplate.query(contains("procedure_templates"), any(RowMapper.class)))
                    .thenReturn(Collections.emptyList());

            // when
            ConsolidationStats stats = consolidator.consolidate();

            // then — 第一个失败，第二个成功
            assertThat(stats.templatesCreated()).isEqualTo(1);
            verify(proceduralMemory, times(1)).save(any(ProcedureTemplate.class));
        }
    }

    @Nested
    @DisplayName("模板去重逻辑")
    class 去重场景 {

        @SuppressWarnings("unchecked")
        @Test
        void 已存在高相似度模板时应标记为更新而非创建() {
            // given
            模拟无巩固历史();
            模拟符合条件的轨迹(2);
            模拟工具调用序列("search", "summarize");

            float[] baseVector = 生成向量(8, 1.0f);
            when(embeddingRouter.embed(anyString(), eq(EmbeddingUseCase.MEMORY), isNull(), isNull()))
                    // 轨迹序列向量化
                    .thenReturn(baseVector)
                    .thenReturn(生成相似向量(baseVector))
                    // 去重：新模板的 triggerIntent 向量化
                    .thenReturn(baseVector)
                    // 去重：已有模板的 triggerIntent 向量化（高相似度）
                    .thenReturn(生成相似向量(baseVector));

            when(promptRegistry.render(eq("memory/procedural-extraction"), any()))
                    .thenReturn("测试提示词");

            String json = 构造模板提炼JSON("搜索流程", "搜索相关信息", "帮我搜索");
            when(generationRouter.call(
                    eq(LlmScene.KNOWLEDGE_EXTRACTION),
                    anyString(), isNull(), isNull(), isNull(),
                    eq(GenerationCapability.CHAT), isNull()))
                    .thenReturn(new LlmResponse(json, null, null, List.of(), Map.of(), 100, 200, null, 0, "p", "m", 500, false));

            // 已有一个模板
            when(jdbcTemplate.query(contains("procedure_templates"), any(RowMapper.class)))
                    .thenAnswer(invocation -> {
                        RowMapper<Object> mapper = invocation.getArgument(1);
                        var rs = mock(java.sql.ResultSet.class);
                        when(rs.getString("template_id")).thenReturn("existing-1");
                        when(rs.getString("trigger_intent")).thenReturn("帮我搜索");
                        return List.of(mapper.mapRow(rs, 0));
                    });

            // when
            ConsolidationStats stats = consolidator.consolidate();

            // then
            assertThat(stats.templatesCreated()).isZero();
            assertThat(stats.templatesUpdated()).isEqualTo(1);
            // 不应保存新模板
            verify(proceduralMemory, never()).save(any(ProcedureTemplate.class));
        }

        @SuppressWarnings("unchecked")
        @Test
        void 已有模板但相似度低于阈值时应创建新模板() {
            // given
            模拟无巩固历史();
            模拟符合条件的轨迹(2);
            模拟工具调用序列("analyze", "report");

            float[] baseVector = 生成向量(8, 1.0f);
            when(embeddingRouter.embed(anyString(), eq(EmbeddingUseCase.MEMORY), isNull(), isNull()))
                    // 轨迹序列向量化
                    .thenReturn(baseVector)
                    .thenReturn(生成相似向量(baseVector))
                    // 去重：新模板 triggerIntent 向量化
                    .thenReturn(生成向量(8, 1.0f))
                    // 去重：已有模板 triggerIntent 向量化（方向完全不同 → 低相似度）
                    .thenReturn(生成向量(8, 200.0f));

            when(promptRegistry.render(eq("memory/procedural-extraction"), any()))
                    .thenReturn("测试提示词");

            String json = 构造模板提炼JSON("分析报告流程", "分析并生成报告", "帮我分析并出报告");
            when(generationRouter.call(
                    eq(LlmScene.KNOWLEDGE_EXTRACTION),
                    anyString(), isNull(), isNull(), isNull(),
                    eq(GenerationCapability.CHAT), isNull()))
                    .thenReturn(new LlmResponse(json, null, null, List.of(), Map.of(), 100, 200, null, 0, "p", "m", 500, false));

            // 已有一个不相关的模板
            when(jdbcTemplate.query(contains("procedure_templates"), any(RowMapper.class)))
                    .thenAnswer(invocation -> {
                        RowMapper<Object> mapper = invocation.getArgument(1);
                        var rs = mock(java.sql.ResultSet.class);
                        when(rs.getString("template_id")).thenReturn("existing-1");
                        when(rs.getString("trigger_intent")).thenReturn("完全不同的意图");
                        return List.of(mapper.mapRow(rs, 0));
                    });

            // when
            ConsolidationStats stats = consolidator.consolidate();

            // then
            assertThat(stats.templatesCreated()).isEqualTo(1);
            assertThat(stats.templatesUpdated()).isZero();
            verify(proceduralMemory).save(any(ProcedureTemplate.class));
        }

        @Test
        void 去重时LLM不可用应保守地允许创建新模板() {
            // given
            模拟无巩固历史();
            模拟符合条件的轨迹(2);
            模拟工具调用序列("tool-a", "tool-b");

            float[] baseVector = 生成向量(8, 1.0f);
            when(embeddingRouter.embed(anyString(), eq(EmbeddingUseCase.MEMORY), isNull(), isNull()))
                    // 轨迹序列向量化
                    .thenReturn(baseVector)
                    .thenReturn(生成相似向量(baseVector))
                    // 去重时 embed triggerIntent 抛异常
                    .thenThrow(new LlmUnavailableException("无可用向量服务", "embedding", List.of()));

            when(promptRegistry.render(eq("memory/procedural-extraction"), any()))
                    .thenReturn("测试提示词");

            String json = 构造模板提炼JSON("新流程", "描述", "触发意图");
            when(generationRouter.call(
                    eq(LlmScene.KNOWLEDGE_EXTRACTION),
                    anyString(), isNull(), isNull(), isNull(),
                    eq(GenerationCapability.CHAT), isNull()))
                    .thenReturn(new LlmResponse(json, null, null, List.of(), Map.of(), 100, 200, null, 0, "p", "m", 500, false));

            // when
            ConsolidationStats stats = consolidator.consolidate();

            // then — 去重失败时保守跳过去重，允许创建
            assertThat(stats.templatesCreated()).isEqualTo(1);
            verify(proceduralMemory).save(any(ProcedureTemplate.class));
        }
    }

    @Nested
    @DisplayName("最大模板数限制")
    class 模板数限制场景 {

        @Test
        void 达到单次最大模板数后应停止提炼() {
            // given
            properties.getConsolidation().setMaxTemplatesPerRun(1);
            模拟无巩固历史();
            模拟符合条件的轨迹(4);
            模拟工具调用序列("tool-a", "tool-b");

            // 两组聚类（每组 2 个轨迹）
            float[] groupA = 生成向量(8, 1.0f);
            float[] groupB = 生成向量(8, 100.0f);
            when(embeddingRouter.embed(anyString(), eq(EmbeddingUseCase.MEMORY), isNull(), isNull()))
                    .thenReturn(groupA)
                    .thenReturn(生成相似向量(groupA))
                    .thenReturn(groupB)
                    .thenReturn(生成相似向量(groupB))
                    // 仅第一个聚类去重的向量化
                    .thenReturn(生成向量(8, 300.0f));

            when(promptRegistry.render(eq("memory/procedural-extraction"), any()))
                    .thenReturn("测试提示词");

            String json = 构造模板提炼JSON("流程A", "描述A", "意图A");
            when(generationRouter.call(
                    eq(LlmScene.KNOWLEDGE_EXTRACTION),
                    anyString(), isNull(), isNull(), isNull(),
                    eq(GenerationCapability.CHAT), isNull()))
                    .thenReturn(new LlmResponse(json, null, null, List.of(), Map.of(), 100, 200, null, 0, "p", "m", 500, false));

            // 去重 — 无已有模板
            when(jdbcTemplate.query(contains("procedure_templates"), any(RowMapper.class)))
                    .thenReturn(Collections.emptyList());

            // when
            ConsolidationStats stats = consolidator.consolidate();

            // then — 即使有 2 个聚类，也只创建 1 个（受 maxTemplatesPerRun 限制）
            assertThat(stats.templatesCreated()).isEqualTo(1);
            verify(proceduralMemory, times(1)).save(any(ProcedureTemplate.class));
            // generationRouter 只被调用 1 次
            verify(generationRouter, times(1)).call(
                    anyString(), anyString(), any(), any(), any(),
                    any(GenerationCapability.class), any());
        }
    }

    @Nested
    @DisplayName("LLM 返回异常内容")
    class LLM返回异常场景 {

        @Test
        void LLM返回空名称的模板应被跳过() {
            // given
            模拟无巩固历史();
            模拟符合条件的轨迹(2);
            模拟工具调用序列("tool-a");

            float[] baseVector = 生成向量(8, 1.0f);
            when(embeddingRouter.embed(anyString(), eq(EmbeddingUseCase.MEMORY), isNull(), isNull()))
                    .thenReturn(baseVector)
                    .thenReturn(生成相似向量(baseVector));

            when(promptRegistry.render(eq("memory/procedural-extraction"), any()))
                    .thenReturn("测试提示词");

            // LLM 返回空名称
            String json = """
                    {
                      "name": "",
                      "description": "描述",
                      "triggerIntent": "意图",
                      "steps": []
                    }
                    """;
            when(generationRouter.call(
                    eq(LlmScene.KNOWLEDGE_EXTRACTION),
                    anyString(), isNull(), isNull(), isNull(),
                    eq(GenerationCapability.CHAT), isNull()))
                    .thenReturn(new LlmResponse(json, null, null, List.of(), Map.of(), 100, 200, null, 0, "p", "m", 500, false));

            // when
            ConsolidationStats stats = consolidator.consolidate();

            // then
            assertThat(stats.templatesCreated()).isZero();
            assertThat(stats.templatesUpdated()).isZero();
            verify(proceduralMemory, never()).save(any(ProcedureTemplate.class));
        }

        @Test
        void LLM返回null名称的模板应被跳过() {
            // given
            模拟无巩固历史();
            模拟符合条件的轨迹(2);
            模拟工具调用序列("tool-a");

            float[] baseVector = 生成向量(8, 1.0f);
            when(embeddingRouter.embed(anyString(), eq(EmbeddingUseCase.MEMORY), isNull(), isNull()))
                    .thenReturn(baseVector)
                    .thenReturn(生成相似向量(baseVector));

            when(promptRegistry.render(eq("memory/procedural-extraction"), any()))
                    .thenReturn("测试提示词");

            // LLM 返回无 name 字段
            String json = """
                    {
                      "description": "描述",
                      "triggerIntent": "意图",
                      "steps": []
                    }
                    """;
            when(generationRouter.call(
                    eq(LlmScene.KNOWLEDGE_EXTRACTION),
                    anyString(), isNull(), isNull(), isNull(),
                    eq(GenerationCapability.CHAT), isNull()))
                    .thenReturn(new LlmResponse(json, null, null, List.of(), Map.of(), 100, 200, null, 0, "p", "m", 500, false));

            // when
            ConsolidationStats stats = consolidator.consolidate();

            // then
            assertThat(stats.templatesCreated()).isZero();
            verify(proceduralMemory, never()).save(any(ProcedureTemplate.class));
        }
    }

    @Nested
    @DisplayName("模板字段缺省值处理")
    class 字段缺省值场景 {

        @SuppressWarnings("unchecked")
        @Test
        void 模板步骤字段为null时应使用缺省值() {
            // given
            模拟无巩固历史();
            模拟符合条件的轨迹(2);
            模拟工具调用序列("tool-a");

            float[] baseVector = 生成向量(8, 1.0f);
            when(embeddingRouter.embed(anyString(), eq(EmbeddingUseCase.MEMORY), isNull(), isNull()))
                    .thenReturn(baseVector)
                    .thenReturn(生成相似向量(baseVector));
            // triggerIntent 为 null 时 isDuplicateTemplate 直接返回 false，无需去重向量化

            when(promptRegistry.render(eq("memory/procedural-extraction"), any()))
                    .thenReturn("测试提示词");

            // 步骤中大部分字段为 null
            String json = """
                    {
                      "name": "测试流程",
                      "description": null,
                      "triggerIntent": null,
                      "steps": [
                        {
                          "toolId": null,
                          "action": null,
                          "parameterTemplate": null,
                          "description": null
                        }
                      ]
                    }
                    """;
            when(generationRouter.call(
                    eq(LlmScene.KNOWLEDGE_EXTRACTION),
                    anyString(), isNull(), isNull(), isNull(),
                    eq(GenerationCapability.CHAT), isNull()))
                    .thenReturn(new LlmResponse(json, null, null, List.of(), Map.of(), 100, 200, null, 0, "p", "m", 500, false));
            // triggerIntent 为 null → isDuplicateTemplate 直接返回 false，不查询 procedure_templates

            // when
            ConsolidationStats stats = consolidator.consolidate();

            // then
            assertThat(stats.templatesCreated()).isEqualTo(1);

            ArgumentCaptor<ProcedureTemplate> captor = ArgumentCaptor.forClass(ProcedureTemplate.class);
            verify(proceduralMemory).save(captor.capture());
            ProcedureTemplate saved = captor.getValue();

            // description 为 null 时应为空字符串
            assertThat(saved.description()).isEmpty();
            // triggerIntent 为 null 时应回退为 name
            assertThat(saved.triggerIntent()).isEqualTo("测试流程");
            // 步骤字段 null 应使用空字符串/空 Map
            assertThat(saved.steps()).hasSize(1);
            var step = saved.steps().getFirst();
            assertThat(step.toolId()).isEmpty();
            assertThat(step.action()).isEmpty();
            assertThat(step.parameterTemplate()).isEmpty();
            assertThat(step.description()).isEmpty();
        }

        @SuppressWarnings("unchecked")
        @Test
        void 模板steps为null时应使用空列表() {
            // given
            模拟无巩固历史();
            模拟符合条件的轨迹(2);
            模拟工具调用序列("tool-a");

            float[] baseVector = 生成向量(8, 1.0f);
            when(embeddingRouter.embed(anyString(), eq(EmbeddingUseCase.MEMORY), isNull(), isNull()))
                    .thenReturn(baseVector)
                    .thenReturn(生成相似向量(baseVector))
                    .thenReturn(生成向量(8, 99.0f));

            when(promptRegistry.render(eq("memory/procedural-extraction"), any()))
                    .thenReturn("测试提示词");

            String json = """
                    {
                      "name": "无步骤流程",
                      "description": "描述",
                      "triggerIntent": "意图"
                    }
                    """;
            when(generationRouter.call(
                    eq(LlmScene.KNOWLEDGE_EXTRACTION),
                    anyString(), isNull(), isNull(), isNull(),
                    eq(GenerationCapability.CHAT), isNull()))
                    .thenReturn(new LlmResponse(json, null, null, List.of(), Map.of(), 100, 200, null, 0, "p", "m", 500, false));

            when(jdbcTemplate.query(contains("procedure_templates"), any(RowMapper.class)))
                    .thenReturn(Collections.emptyList());

            // when
            ConsolidationStats stats = consolidator.consolidate();

            // then
            assertThat(stats.templatesCreated()).isEqualTo(1);
            ArgumentCaptor<ProcedureTemplate> captor = ArgumentCaptor.forClass(ProcedureTemplate.class);
            verify(proceduralMemory).save(captor.capture());
            assertThat(captor.getValue().steps()).isEmpty();
        }
    }

    @Nested
    @DisplayName("巩固日志记录")
    class 日志记录场景 {

        @Test
        void 巩固完成后应写入日志表() {
            // given
            模拟无巩固历史();
            模拟符合条件的轨迹(0); // 空轨迹，快速通过

            // when
            consolidator.consolidate();

            // then — 验证 logConsolidation 被调用
            verify(jdbcTemplate).update(
                    contains("memory_consolidation_log"),
                    any(Object[].class));
        }

        @Test
        void 有巩固历史时应使用历史时间作为窗口起始() {
            // given — 返回上次巩固时间
            when(jdbcTemplate.queryForList(
                    contains("memory_consolidation_log"),
                    eq(String.class),
                    eq("EPISODIC_TO_PROCEDURAL")
            )).thenReturn(List.of("2026-04-01T00:00:00Z"));

            // 查询轨迹返回空
            when(jdbcTemplate.query(contains("agent_traces"), any(RowMapper.class), any(), anyInt()))
                    .thenReturn(Collections.emptyList());

            // when
            ConsolidationStats stats = consolidator.consolidate();

            // then
            assertThat(stats.conversationsAnalyzed()).isZero();
            // 验证使用了历史时间
            verify(jdbcTemplate).query(
                    contains("agent_traces"),
                    any(RowMapper.class),
                    eq("2026-04-01T00:00:00Z"),
                    eq(2));
        }

        @Test
        void 巩固历史时间解析失败时应使用默认回溯窗口() {
            // given — 返回无法解析的时间
            when(jdbcTemplate.queryForList(
                    contains("memory_consolidation_log"),
                    eq(String.class),
                    eq("EPISODIC_TO_PROCEDURAL")
            )).thenReturn(List.of("invalid-time-format"));

            // 查询轨迹返回空
            when(jdbcTemplate.query(contains("agent_traces"), any(RowMapper.class), any(), anyInt()))
                    .thenReturn(Collections.emptyList());

            // when
            ConsolidationStats stats = consolidator.consolidate();

            // then
            assertThat(stats.conversationsAnalyzed()).isZero();
            // 使用默认回溯窗口（任意 Instant 字符串，非 "invalid-time-format"）
            verify(jdbcTemplate).query(
                    contains("agent_traces"),
                    any(RowMapper.class),
                    argThat(arg -> arg instanceof String s && !s.equals("invalid-time-format")),
                    eq(2));
        }
    }

    @Nested
    @DisplayName("统计结果完整性")
    class 统计结果场景 {

        @Test
        void 统计结果应包含正确的类型标识() {
            // given
            模拟无巩固历史();
            模拟符合条件的轨迹(0);

            // when
            ConsolidationStats stats = consolidator.consolidate();

            // then
            assertThat(stats.consolidationType()).isEqualTo("EPISODIC_TO_PROCEDURAL");
        }

        @Test
        void 耗时应为非负数() {
            // given
            模拟无巩固历史();
            模拟符合条件的轨迹(0);

            // when
            ConsolidationStats stats = consolidator.consolidate();

            // then
            assertThat(stats.elapsedMs()).isGreaterThanOrEqualTo(0);
        }

        @Test
        void 情景程序巩固不使用实体相关字段() {
            // given
            模拟无巩固历史();
            模拟符合条件的轨迹(0);

            // when
            ConsolidationStats stats = consolidator.consolidate();

            // then — entitiesFound/entitiesBoosted/extractionsTriggered 始终为 0
            assertThat(stats.entitiesFound()).isZero();
            assertThat(stats.entitiesBoosted()).isZero();
            assertThat(stats.extractionsTriggered()).isZero();
        }
    }
}
