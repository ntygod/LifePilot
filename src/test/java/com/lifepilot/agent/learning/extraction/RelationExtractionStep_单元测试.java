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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RelationExtractionStep 单元测试 —— 验证关系 JSON 数组解析、端点不足跳过、降级容错。
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
    void 对象包裹格式应按非契约输出丢弃() {
        String json = """
                {"relations":[{"sourceName":"张三","targetName":"北京","relationType":"居住于","strength":0.8}]}
                """;
        when(generationRouter.call(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(resp(json));

        var relations = step.extract("用户: 张三住在北京", List.of("张三 [PERSON]", "北京 [PLACE]"));

        assertThat(relations).isEmpty();
    }

    @Test
    void markdown包裹格式应按非契约输出丢弃() {
        String json = """
                ```json
                [{"sourceName":"张三","targetName":"北京","relationType":"居住于","strength":0.8}]
                ```
                """;
        when(generationRouter.call(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(resp(json));

        var relations = step.extract("用户: 张三住在北京", List.of("张三 [PERSON]", "北京 [PLACE]"));

        assertThat(relations).isEmpty();
    }

    @Test
    void 实体不足两个时不调用LLM() {
        var relations = step.extract("用户: 我喜欢咖啡", List.of("咖啡 [PREFERENCE]"));

        assertThat(relations).isEmpty();
        verify(generationRouter, never())
                .call(any(), any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void LLM异常时降级为空列表() {
        when(generationRouter.call(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenThrow(new RuntimeException("LLM 故障"));

        var relations = step.extract("用户: 张三在阿里工作", List.of("张三 [PERSON]", "阿里 [ORGANIZATION]"));

        assertThat(relations).isEmpty();
    }

    @Test
    void 空内容返回空列表() {
        when(generationRouter.call(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(resp(""));

        var relations = step.extract("用户: 张三在阿里工作", List.of("张三 [PERSON]", "阿里 [ORGANIZATION]"));

        assertThat(relations).isEmpty();
    }

    @Test
    void strength越界应被钳制到合法区间() {
        String json = """
                [{"sourceName":"张三","targetName":"阿里","relationType":"就职于","strength":1.8}]
                """;
        when(generationRouter.call(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(resp(json));

        var relations = step.extract("用户: 张三在阿里工作", List.of("张三 [PERSON]", "阿里 [ORGANIZATION]"));

        assertThat(relations.getFirst().strength()).isEqualTo(1.0f);
    }
}
