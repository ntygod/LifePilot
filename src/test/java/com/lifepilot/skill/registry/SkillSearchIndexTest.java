package com.lifepilot.skill.registry;

import com.lifepilot.llm.LlmRouter;
import com.lifepilot.llm.LlmUnavailableException;
import com.lifepilot.skill.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SkillSearchIndex 单元测试。
 *
 * @author zsg
 * @since 2026-07-28
 */
class SkillSearchIndexTest {

    private LlmRouter llmRouter;
    private SkillSearchIndex searchIndex;

    @BeforeEach
    void setUp() {
        llmRouter = mock(LlmRouter.class);
        searchIndex = new SkillSearchIndex(llmRouter);
    }

    @Test
    void index_正常索引Skill() {
        float[] vector = {1.0f, 0.0f, 0.0f};
        when(llmRouter.embed(anyString())).thenReturn(vector);

        searchIndex.index(createSkillDefinition("todo", "待办管理", "管理待办事项"));

        // 验证调用了 embed
        verify(llmRouter).embed("待办管理 管理待办事项");
    }

    @Test
    void index_LLM不可用时降级跳过() {
        when(llmRouter.embed(anyString())).thenThrow(
                new LlmUnavailableException("无可用 Provider", "EMBEDDING", List.of()));

        // 不应抛出异常
        searchIndex.index(createSkillDefinition("todo", "待办管理", "管理待办事项"));

        // 重置 mock，搜索时返回正常向量
        reset(llmRouter);
        when(llmRouter.embed(anyString())).thenReturn(new float[]{1.0f, 0.0f, 0.0f});

        // 搜索应返回空结果（因为索引时跳过了，缓存中无向量）
        List<SkillSearchIndex.SearchResult> results = searchIndex.search("待办", 5);
        assertThat(results).isEmpty();
    }

    @Test
    void remove_移除已索引的Skill() {
        float[] vector = {1.0f, 0.0f, 0.0f};
        when(llmRouter.embed(anyString())).thenReturn(vector);

        searchIndex.index(createSkillDefinition("todo", "待办管理", "管理待办事项"));
        searchIndex.remove("todo");

        // 搜索应返回空结果
        List<SkillSearchIndex.SearchResult> results = searchIndex.search("待办", 5);
        assertThat(results).isEmpty();
    }

    @Test
    void search_返回相似度大于阈值的结果() {
        // 索引两个 Skill，使用相同方向的向量
        float[] todoVector = {1.0f, 0.0f, 0.0f};
        float[] scheduleVector = {0.0f, 1.0f, 0.0f};

        when(llmRouter.embed("待办管理 管理待办事项")).thenReturn(todoVector);
        searchIndex.index(createSkillDefinition("todo", "待办管理", "管理待办事项"));

        when(llmRouter.embed("日程管理 管理日程安排")).thenReturn(scheduleVector);
        searchIndex.index(createSkillDefinition("schedule", "日程管理", "管理日程安排"));

        // 查询向量与 todo 完全匹配，与 schedule 正交
        when(llmRouter.embed("待办")).thenReturn(new float[]{1.0f, 0.0f, 0.0f});
        List<SkillSearchIndex.SearchResult> results = searchIndex.search("待办", 5);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().skillId()).isEqualTo("todo");
        assertThat(results.getFirst().similarity()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void search_结果按相似度降序排列() {
        float[] highVector = {0.9f, 0.1f, 0.0f};
        float[] mediumVector = {0.7f, 0.3f, 0.0f};

        when(llmRouter.embed("高相似 高相似技能")).thenReturn(highVector);
        searchIndex.index(createSkillDefinition("high", "高相似", "高相似技能"));

        when(llmRouter.embed("中相似 中相似技能")).thenReturn(mediumVector);
        searchIndex.index(createSkillDefinition("medium", "中相似", "中相似技能"));

        // 查询向量偏向 high
        when(llmRouter.embed("查询")).thenReturn(new float[]{1.0f, 0.0f, 0.0f});
        List<SkillSearchIndex.SearchResult> results = searchIndex.search("查询", 5);

        assertThat(results).hasSizeGreaterThanOrEqualTo(2);
        assertThat(results.get(0).similarity()).isGreaterThanOrEqualTo(results.get(1).similarity());
    }

    @Test
    void search_限制topK返回数量() {
        // 索引 3 个 Skill，都使用高相似度向量
        for (int i = 1; i <= 3; i++) {
            float[] vector = new float[]{0.8f + i * 0.01f, 0.1f, 0.0f};
            String id = "skill-" + i;
            when(llmRouter.embed(id + " 技能" + i)).thenReturn(vector);
            searchIndex.index(createSkillDefinition(id, id, "技能" + i));
        }

        when(llmRouter.embed("查询")).thenReturn(new float[]{1.0f, 0.0f, 0.0f});
        List<SkillSearchIndex.SearchResult> results = searchIndex.search("查询", 2);

        assertThat(results).hasSize(2);
    }

    @Test
    void search_LLM不可用时返回空列表() {
        // 先正常索引
        when(llmRouter.embed("待办管理 管理待办事项")).thenReturn(new float[]{1.0f, 0.0f, 0.0f});
        searchIndex.index(createSkillDefinition("todo", "待办管理", "管理待办事项"));

        // 搜索时 LLM 不可用
        when(llmRouter.embed("待办")).thenThrow(
                new LlmUnavailableException("无可用 Provider", "EMBEDDING", List.of()));

        List<SkillSearchIndex.SearchResult> results = searchIndex.search("待办", 5);
        assertThat(results).isEmpty();
    }

    @Test
    void search_过滤相似度低于阈值的结果() {
        // 索引一个与查询方向几乎正交的 Skill
        float[] vector = {0.0f, 1.0f, 0.0f};
        when(llmRouter.embed("无关技能 完全无关的技能")).thenReturn(vector);
        searchIndex.index(createSkillDefinition("unrelated", "无关技能", "完全无关的技能"));

        // 查询向量与索引向量正交，余弦相似度为 0
        when(llmRouter.embed("查询")).thenReturn(new float[]{1.0f, 0.0f, 0.0f});
        List<SkillSearchIndex.SearchResult> results = searchIndex.search("查询", 5);

        assertThat(results).isEmpty();
    }

    @Test
    void cosineSimilarity_相同向量返回1() {
        float[] a = {1.0f, 2.0f, 3.0f};
        assertThat(SkillSearchIndex.cosineSimilarity(a, a)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    void cosineSimilarity_正交向量返回0() {
        float[] a = {1.0f, 0.0f, 0.0f};
        float[] b = {0.0f, 1.0f, 0.0f};
        assertThat(SkillSearchIndex.cosineSimilarity(a, b)).isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    void cosineSimilarity_零向量返回0() {
        float[] a = {0.0f, 0.0f, 0.0f};
        float[] b = {1.0f, 2.0f, 3.0f};
        assertThat(SkillSearchIndex.cosineSimilarity(a, b)).isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    void cosineSimilarity_不同长度向量返回0() {
        float[] a = {1.0f, 2.0f};
        float[] b = {1.0f, 2.0f, 3.0f};
        assertThat(SkillSearchIndex.cosineSimilarity(a, b)).isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.0001));
    }

    // --- 辅助方法 ---

    private SkillDefinition createSkillDefinition(String id, String name, String description) {
        return SkillDefinition.builder()
                .id(id)
                .name(name)
                .description(description)
                .version("1.0.0")
                .source(new SkillSource.Builtin())
                .instructions("测试 Prompt")
                .suggestedTools(List.of("tool-1"))
                .metadata(Map.of())
                .build();
    }
}
