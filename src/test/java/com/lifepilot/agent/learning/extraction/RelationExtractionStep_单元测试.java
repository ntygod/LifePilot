package com.lifepilot.agent.learning.extraction;

import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RelationExtractionStep 单元测试 —— 验证关系 JSON 数组契约、端点不足跳过、调用失败暴露。
 *
 * @author zsg
 * @since 2026-06-06
 */
class RelationExtractionStep_单元测试 {

    private GenerationRouter generationRouter;
    private PromptRegistry promptRegistry;
    private RelationExtractionStep step;

    @BeforeEach
    void setUp() {
        generationRouter = mock(GenerationRouter.class);
        promptRegistry = mock(PromptRegistry.class);
        when(promptRegistry.render(any(), any())).thenReturn("prompt-ignored");
        step = new RelationExtractionStep(generationRouter, promptRegistry, new AgentLearningProperties());
    }

    private LlmResponse resp(String content) {
        return new LlmResponse(content, null, null, List.of(), Map.of(), 1, 1, null, 0, "mock", "mock", 1L, false);
    }

    @Test
    void 关系数组应被正确解析() {
        String json = """
                [{"sourceName":"张三","targetName":"阿里","relationType":"就职于",
                  "strength":0.9,"evidence":"张三在阿里工作"}]
                """;
        when(generationRouter.call(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(resp(json));

        var relations = step.extract("用户: 张三在阿里工作", List.of("张三 [PERSON]", "阿里 [ORGANIZATION]"));

        assertThat(relations).hasSize(1);
        var r = relations.getFirst();
        assertThat(r.sourceName()).isEqualTo("张三");
        assertThat(r.targetName()).isEqualTo("阿里");
        assertThat(r.relationType()).isEqualTo("就职于");
        assertThat(r.strength()).isEqualTo(0.9f);
    }

    @Test
    void 对象包裹格式应按非契约输出失败() {
        String json = """
                {"relations":[{"sourceName":"张三","targetName":"北京","relationType":"居住于","strength":0.8}]}
                """;
        when(generationRouter.call(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(resp(json));

        assertThatThrownBy(() -> step.extract("用户: 张三住在北京", List.of("张三 [PERSON]", "北京 [PLACE]")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("关系数组解析失败");
    }

    @Test
    void markdown包裹格式应按非契约输出失败() {
        String json = """
                ```json
                [{"sourceName":"张三","targetName":"北京","relationType":"居住于","strength":0.8}]
                ```
                """;
        when(generationRouter.call(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(resp(json));

        assertThatThrownBy(() -> step.extract("用户: 张三住在北京", List.of("张三 [PERSON]", "北京 [PLACE]")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("关系数组解析失败");
    }

    @Test
    void 实体不足两个时不调用LLM() {
        var relations = step.extract("用户: 我喜欢咖啡", List.of("咖啡 [PREFERENCE]"));

        assertThat(relations).isEmpty();
        verify(generationRouter, never())
                .call(any(), any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void 空对话文本应直接失败() {
        assertThatThrownBy(() -> step.extract(" ", List.of("张三 [PERSON]", "阿里 [ORGANIZATION]")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("关系抽取对话文本不能为空");
        verify(generationRouter, never())
                .call(any(), any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void 实体清单缺失或含空白实体名应直接失败() {
        assertThatThrownBy(() -> step.extract("用户: 张三在阿里工作", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("关系抽取实体清单不能为空");
        assertThatThrownBy(() -> step.extract("用户: 张三在阿里工作", List.of("张三 [PERSON]", " ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("关系抽取实体名称不能为空");
        verify(generationRouter, never())
                .call(any(), any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void 实体名称包含首尾空白应直接失败() {
        assertThatThrownBy(() -> step.extract("用户: 张三在阿里工作", List.of("张三 [PERSON]", " 阿里 [ORGANIZATION] ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能包含首尾空白");
        verify(generationRouter, never())
                .call(any(), any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void LLM异常时应抛出() {
        when(generationRouter.call(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenThrow(new RuntimeException("LLM 故障"));

        assertThatThrownBy(() -> step.extract(
                "用户: 张三在阿里工作", List.of("张三 [PERSON]", "阿里 [ORGANIZATION]")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("关系抽取 LLM 调用失败")
                .hasMessageContaining("LLM 故障");
    }

    @Test
    void 空内容应按非契约输出失败() {
        when(generationRouter.call(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(resp(""));

        assertThatThrownBy(() -> step.extract(
                "用户: 张三在阿里工作", List.of("张三 [PERSON]", "阿里 [ORGANIZATION]")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("关系抽取 LLM 返回空内容");
    }

    @Test
    void strength越界应按非契约输出失败() {
        String json = """
                [{"sourceName":"张三","targetName":"阿里","relationType":"就职于","strength":1.8}]
                """;
        when(generationRouter.call(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(resp(json));

        assertThatThrownBy(() -> step.extract(
                "用户: 张三在阿里工作", List.of("张三 [PERSON]", "阿里 [ORGANIZATION]")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("关系数组解析失败")
                .hasMessageContaining("strength 必须在 [0,1] 范围内");
    }

    @Test
    void 缺少端点字段应按非契约输出失败() {
        String json = """
                [{"sourceName":"","targetName":"阿里","relationType":"就职于","strength":0.8}]
                """;
        when(generationRouter.call(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(resp(json));

        assertThatThrownBy(() -> step.extract(
                "用户: 张三在阿里工作", List.of("张三 [PERSON]", "阿里 [ORGANIZATION]")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("关系数组解析失败")
                .hasMessageContaining("sourceName 不能为空");
    }

    @Test
    void 端点字段包含首尾空白应按非契约输出失败() {
        String json = """
                [{"sourceName":" 张三 ","targetName":"阿里","relationType":"就职于","strength":0.8}]
                """;
        when(generationRouter.call(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(resp(json));

        assertThatThrownBy(() -> step.extract(
                "用户: 张三在阿里工作", List.of("张三 [PERSON]", "阿里 [ORGANIZATION]")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("关系数组解析失败")
                .hasMessageContaining("sourceName 不能包含首尾空白");
    }

    @Test
    void 超时配置非正数应直接失败() {
        var properties = new AgentLearningProperties();
        properties.getExtraction().setRelationTimeoutSeconds(0);

        assertThatThrownBy(() -> new RelationExtractionStep(generationRouter, promptRegistry, properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("关系抽取超时秒数必须大于 0");
    }
}
