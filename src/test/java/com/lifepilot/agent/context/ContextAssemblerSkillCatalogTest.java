package com.lifepilot.agent.context;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.prompt.PromptRegistry;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.model.SkillSource;
import com.lifepilot.skill.registry.SkillRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ContextAssembler L1 Skill 清单注入测试 — 验证 buildReactSystemPrompt 中 Skill 清单渲染。
 *
 * @author zsg
 * @since 2026-03-19
 */
class ContextAssemblerSkillCatalogTest {

    @Test
    void buildReactSystemPrompt_有Skill时_追加清单() {
        var config = buildConfig();
        var promptRegistry = mock(PromptRegistry.class);
        var skillRegistry = mock(SkillRegistry.class);

        // react-system 模板渲染
        when(promptRegistry.render(eq("agent/role-definition"))).thenReturn("角色定义");
        when(promptRegistry.render(eq("agent/context-guide"))).thenReturn("上下文指南");
        when(promptRegistry.render(eq("agent/react-system"), anyMap())).thenReturn("系统提示词");

        // skill-catalog 模板渲染
        when(promptRegistry.render(eq("agent/skill-catalog"), anyMap()))
                .thenReturn("<skill_catalog>\n- todo: 待办管理\n</skill_catalog>");

        // 注册一个 Skill
        var skill = SkillDefinition.builder()
                .id("todo").name("待办管理").description("管理待办事项")
                .version("1.0").instructions("指令").suggestedTools(List.of())
                .source(new SkillSource.UserDefined("/test", null)).metadata(Map.of()).build();
        when(skillRegistry.listAll()).thenReturn(List.of(skill));

        var assembler = new ContextAssembler(config, promptRegistry,
                null, null, null, null, null, null, null, null, skillRegistry);

        String result = assembler.buildReactSystemPrompt();

        assertThat(result).contains("系统提示词");
        assertThat(result).contains("skill_catalog");
        verify(promptRegistry).render(eq("agent/skill-catalog"), anyMap());
    }

    @Test
    void buildReactSystemPrompt_无Skill时_不追加清单() {
        var config = buildConfig();
        var promptRegistry = mock(PromptRegistry.class);
        var skillRegistry = mock(SkillRegistry.class);

        when(promptRegistry.render(eq("agent/role-definition"))).thenReturn("角色定义");
        when(promptRegistry.render(eq("agent/context-guide"))).thenReturn("上下文指南");
        when(promptRegistry.render(eq("agent/react-system"), anyMap())).thenReturn("系统提示词");

        when(skillRegistry.listAll()).thenReturn(List.of());

        var assembler = new ContextAssembler(config, promptRegistry,
                null, null, null, null, null, null, null, null, skillRegistry);

        String result = assembler.buildReactSystemPrompt();

        assertThat(result).isEqualTo("系统提示词");
        verify(promptRegistry, never()).render(eq("agent/skill-catalog"), anyMap());
    }

    @Test
    void buildReactSystemPrompt_SkillRegistry为null时_不追加清单() {
        var config = buildConfig();
        var promptRegistry = mock(PromptRegistry.class);

        when(promptRegistry.render(eq("agent/role-definition"))).thenReturn("角色定义");
        when(promptRegistry.render(eq("agent/context-guide"))).thenReturn("上下文指南");
        when(promptRegistry.render(eq("agent/react-system"), anyMap())).thenReturn("系统提示词");

        var assembler = new ContextAssembler(config, promptRegistry,
                null, null, null, null, null, null, null, null, null);

        String result = assembler.buildReactSystemPrompt();

        assertThat(result).isEqualTo("系统提示词");
        verify(promptRegistry, never()).render(eq("agent/skill-catalog"), anyMap());
    }

    @Test
    void buildReactSystemPrompt_清单模板渲染异常时_降级跳过() {
        var config = buildConfig();
        var promptRegistry = mock(PromptRegistry.class);
        var skillRegistry = mock(SkillRegistry.class);

        when(promptRegistry.render(eq("agent/role-definition"))).thenReturn("角色定义");
        when(promptRegistry.render(eq("agent/context-guide"))).thenReturn("上下文指南");
        when(promptRegistry.render(eq("agent/react-system"), anyMap())).thenReturn("系统提示词");

        var skill = SkillDefinition.builder()
                .id("test").name("测试").description("测试")
                .version("1.0").instructions("指令").suggestedTools(List.of())
                .source(new SkillSource.UserDefined("/test", null)).metadata(Map.of()).build();
        when(skillRegistry.listAll()).thenReturn(List.of(skill));
        when(promptRegistry.render(eq("agent/skill-catalog"), anyMap()))
                .thenThrow(new RuntimeException("模板不存在"));

        var assembler = new ContextAssembler(config, promptRegistry,
                null, null, null, null, null, null, null, null, skillRegistry);

        String result = assembler.buildReactSystemPrompt();

        // 降级：不追加清单，但系统提示词正常返回
        assertThat(result).isEqualTo("系统提示词");
    }

    private AgentConfigProperties buildConfig() {
        var config = new AgentConfigProperties();
        var context = new AgentConfigProperties.ContextConfig();
        context.setMaxContextTokens(8000);
        config.setContext(context);
        return config;
    }
}
