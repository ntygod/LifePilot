package com.lifepilot.skill.registry;

import com.lifepilot.embedding.router.EmbeddingRouter;
import com.lifepilot.embedding.router.EmbeddingUseCase;
import com.lifepilot.llm.LlmUnavailableException;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.model.SkillSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SkillSearchIndex 单元测试。
 *
 * @author zsg
 * @since 2026-03-24
 */
class SkillSearchIndexTest {

    private EmbeddingRouter embeddingRouter;
    private SkillSearchIndex searchIndex;

    @BeforeEach
    void setUp() {
        embeddingRouter = mock(EmbeddingRouter.class);
        searchIndex = new SkillSearchIndex(embeddingRouter);
    }

    @Test
    void index_正常索引Skill() {
        float[] vector = {1.0f, 0.0f, 0.0f};
        when(embeddingRouter.embed(anyString(), any(), any(), any())).thenReturn(vector);

        searchIndex.index(createSkillDefinition("todo", "待办管理", "管理待办事项"));

        verify(embeddingRouter).embed("todo 待办管理 管理待办事项 待办管理", EmbeddingUseCase.DEFAULT, null, null);
    }

    @Test
    void index_向量服务不可用时降级跳过() {
        when(embeddingRouter.embed(anyString(), any(), any(), any()))
                .thenThrow(new LlmUnavailableException("无可用 Provider", "EMBEDDING", List.of()));

        searchIndex.index(createSkillDefinition("todo", "待办管理", "管理待办事项"));

        reset(embeddingRouter);
        when(embeddingRouter.embed(anyString(), any(), any(), any()))
                .thenReturn(new float[]{1.0f, 0.0f, 0.0f});

        List<SkillSearchIndex.SearchResult> results = searchIndex.search("待办", 5);
        assertThat(results).isEmpty();
    }

    @Test
    void remove_移除已索引的Skill() {
        when(embeddingRouter.embed(anyString(), any(), any(), any()))
                .thenReturn(new float[]{1.0f, 0.0f, 0.0f});

        searchIndex.index(createSkillDefinition("todo", "待办管理", "管理待办事项"));
        searchIndex.remove("todo");

        List<SkillSearchIndex.SearchResult> results = searchIndex.search("待办", 5);
        assertThat(results).isEmpty();
    }

    @Test
    void search_返回高相关结果() {
        when(embeddingRouter.embed("todo 待办管理 管理待办事项 待办管理", EmbeddingUseCase.DEFAULT, null, null))
                .thenReturn(new float[]{1.0f, 0.0f, 0.0f});
        searchIndex.index(createSkillDefinition("todo", "待办管理", "管理待办事项"));

        when(embeddingRouter.embed("schedule 日程管理 管理日程安排 日程管理", EmbeddingUseCase.DEFAULT, null, null))
                .thenReturn(new float[]{0.0f, 1.0f, 0.0f});
        searchIndex.index(createSkillDefinition("schedule", "日程管理", "管理日程安排"));

        when(embeddingRouter.embed("待办", EmbeddingUseCase.DEFAULT, null, null))
                .thenReturn(new float[]{1.0f, 0.0f, 0.0f});

        List<SkillSearchIndex.SearchResult> results = searchIndex.search("待办", 5);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().skillId()).isEqualTo("todo");
        assertThat(results.getFirst().similarity()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void search_按相似度降序返回() {
        when(embeddingRouter.embed("high 高相似 高相似技能 高相似", EmbeddingUseCase.DEFAULT, null, null))
                .thenReturn(new float[]{0.9f, 0.1f, 0.0f});
        searchIndex.index(createSkillDefinition("high", "高相似", "高相似技能"));

        when(embeddingRouter.embed("medium 中相似 中相似技能 中相似", EmbeddingUseCase.DEFAULT, null, null))
                .thenReturn(new float[]{0.7f, 0.3f, 0.0f});
        searchIndex.index(createSkillDefinition("medium", "中相似", "中相似技能"));

        when(embeddingRouter.embed("查询", EmbeddingUseCase.DEFAULT, null, null))
                .thenReturn(new float[]{1.0f, 0.0f, 0.0f});

        List<SkillSearchIndex.SearchResult> results = searchIndex.search("查询", 5);
        assertThat(results).hasSizeGreaterThanOrEqualTo(2);
        assertThat(results.get(0).similarity()).isGreaterThanOrEqualTo(results.get(1).similarity());
    }

    @Test
    void search_限制TopK数量() {
        when(embeddingRouter.embed(anyString(), any(), any(), any()))
                .thenReturn(
                        new float[]{0.81f, 0.1f, 0.0f},
                        new float[]{0.82f, 0.1f, 0.0f},
                        new float[]{0.83f, 0.1f, 0.0f},
                        new float[]{1.0f, 0.0f, 0.0f}
                );

        for (int i = 1; i <= 3; i++) {
            String id = "skill-" + i;
            searchIndex.index(createSkillDefinition(id, id, "技能" + i));
        }

        when(embeddingRouter.embed("查询", EmbeddingUseCase.DEFAULT, null, null))
                .thenReturn(new float[]{1.0f, 0.0f, 0.0f});

        List<SkillSearchIndex.SearchResult> results = searchIndex.search("查询", 2);
        assertThat(results).hasSize(2);
    }

    @Test
    void search_查询向量生成失败时降级为关键词搜索() {
        when(embeddingRouter.embed("todo 待办管理 管理待办事项 待办管理", EmbeddingUseCase.DEFAULT, null, null))
                .thenReturn(new float[]{1.0f, 0.0f, 0.0f});
        searchIndex.index(createSkillDefinition("todo", "待办管理", "管理待办事项"));

        when(embeddingRouter.embed("待办", EmbeddingUseCase.DEFAULT, null, null))
                .thenThrow(new LlmUnavailableException("无可用 Provider", "EMBEDDING", List.of()));

        List<SkillSearchIndex.SearchResult> results = searchIndex.search("待办", 5);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().skillId()).isEqualTo("todo");
    }

    @Test
    void search_过滤低相似度结果() {
        when(embeddingRouter.embed("unrelated 无关技能 完全无关的技能 无关技能", EmbeddingUseCase.DEFAULT, null, null))
                .thenReturn(new float[]{0.0f, 1.0f, 0.0f});
        searchIndex.index(createSkillDefinition("unrelated", "无关技能", "完全无关的技能"));

        when(embeddingRouter.embed("查询", EmbeddingUseCase.DEFAULT, null, null))
                .thenReturn(new float[]{1.0f, 0.0f, 0.0f});

        List<SkillSearchIndex.SearchResult> results = searchIndex.search("查询", 5);
        assertThat(results).isEmpty();
    }

    @Test
    void cosineSimilarity_相同向量返回一() {
        float[] a = {1.0f, 2.0f, 3.0f};
        assertThat(SkillSearchIndex.cosineSimilarity(a, a))
                .isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    void cosineSimilarity_正交向量返回零() {
        float[] a = {1.0f, 0.0f, 0.0f};
        float[] b = {0.0f, 1.0f, 0.0f};
        assertThat(SkillSearchIndex.cosineSimilarity(a, b))
                .isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    void cosineSimilarity_零向量返回零() {
        float[] a = {0.0f, 0.0f, 0.0f};
        float[] b = {1.0f, 2.0f, 3.0f};
        assertThat(SkillSearchIndex.cosineSimilarity(a, b))
                .isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    void cosineSimilarity_不同长度返回零() {
        float[] a = {1.0f, 2.0f};
        float[] b = {1.0f, 2.0f, 3.0f};
        assertThat(SkillSearchIndex.cosineSimilarity(a, b))
                .isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.0001));
    }

    private SkillDefinition createSkillDefinition(String id, String name, String description) {
        return SkillDefinition.builder()
                .id(id)
                .name(name)
                .description(description)
                .version("1.0.0")
                .source(new SkillSource.UserDefined("/test", null))
                .instructions("测试 Prompt")
                .suggestedTools(List.of("tool-1"))
                .triggers(List.of(name))
                .metadata(Map.of())
                .build();
    }
}
