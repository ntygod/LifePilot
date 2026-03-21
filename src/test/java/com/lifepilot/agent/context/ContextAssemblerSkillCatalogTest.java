package com.lifepilot.agent.context;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.model.Budget;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.prompt.PromptRegistry;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.model.SkillSource;
import com.lifepilot.skill.registry.SkillRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContextAssemblerSkillCatalogTest {

    @Test
    void buildReactSystemPrompt_withSkill_appendsCatalog() {
        var config = buildConfig();
        var promptRegistry = mock(PromptRegistry.class);
        var skillRegistry = mock(SkillRegistry.class);

        when(promptRegistry.render(eq("agent/role-definition"))).thenReturn("role");
        when(promptRegistry.render(eq("agent/context-guide"))).thenReturn("guide");
        when(promptRegistry.render(eq("agent/react-system"), anyMap())).thenReturn("system prompt");
        when(promptRegistry.render(eq("agent/skill-catalog"), anyMap()))
                .thenReturn("<skill_catalog>\n- todo: task management\n</skill_catalog>");

        var skill = SkillDefinition.builder()
                .id("todo").name("task management").description("manage tasks")
                .version("1.0").instructions("instructions").suggestedTools(List.of())
                .source(new SkillSource.UserDefined("/test", null)).metadata(Map.of()).build();
        when(skillRegistry.listAll()).thenReturn(List.of(skill));

        var assembler = new ContextAssembler(config, promptRegistry,
                null, null, null, null, null, null, null, null, null, skillRegistry);

        String result = assembler.buildReactSystemPrompt();

        assertThat(result).contains("system prompt");
        assertThat(result).contains("skill_catalog");
        verify(promptRegistry).render(eq("agent/skill-catalog"), anyMap());
    }

    @Test
    void buildReactSystemPrompt_withoutSkill_doesNotAppendCatalog() {
        var config = buildConfig();
        var promptRegistry = mock(PromptRegistry.class);
        var skillRegistry = mock(SkillRegistry.class);

        when(promptRegistry.render(eq("agent/role-definition"))).thenReturn("role");
        when(promptRegistry.render(eq("agent/context-guide"))).thenReturn("guide");
        when(promptRegistry.render(eq("agent/react-system"), anyMap())).thenReturn("system prompt");
        when(skillRegistry.listAll()).thenReturn(List.of());

        var assembler = new ContextAssembler(config, promptRegistry,
                null, null, null, null, null, null, null, null, null, skillRegistry);

        String result = assembler.buildReactSystemPrompt();

        assertThat(result).isEqualTo("system prompt");
        verify(promptRegistry, never()).render(eq("agent/skill-catalog"), anyMap());
    }

    @Test
    void buildReactSystemPrompt_withNullSkillRegistry_doesNotAppendCatalog() {
        var config = buildConfig();
        var promptRegistry = mock(PromptRegistry.class);

        when(promptRegistry.render(eq("agent/role-definition"))).thenReturn("role");
        when(promptRegistry.render(eq("agent/context-guide"))).thenReturn("guide");
        when(promptRegistry.render(eq("agent/react-system"), anyMap())).thenReturn("system prompt");

        var assembler = new ContextAssembler(config, promptRegistry,
                null, null, null, null, null, null, null, null, null, null);

        String result = assembler.buildReactSystemPrompt();

        assertThat(result).isEqualTo("system prompt");
        verify(promptRegistry, never()).render(eq("agent/skill-catalog"), anyMap());
    }

    @Test
    void buildReactSystemPrompt_whenSkillCatalogRenderFails_skipsCatalog() {
        var config = buildConfig();
        var promptRegistry = mock(PromptRegistry.class);
        var skillRegistry = mock(SkillRegistry.class);

        when(promptRegistry.render(eq("agent/role-definition"))).thenReturn("role");
        when(promptRegistry.render(eq("agent/context-guide"))).thenReturn("guide");
        when(promptRegistry.render(eq("agent/react-system"), anyMap())).thenReturn("system prompt");

        var skill = SkillDefinition.builder()
                .id("test").name("test").description("test")
                .version("1.0").instructions("instructions").suggestedTools(List.of())
                .source(new SkillSource.UserDefined("/test", null)).metadata(Map.of()).build();
        when(skillRegistry.listAll()).thenReturn(List.of(skill));
        when(promptRegistry.render(eq("agent/skill-catalog"), anyMap()))
                .thenThrow(new RuntimeException("missing template"));

        var assembler = new ContextAssembler(config, promptRegistry,
                null, null, null, null, null, null, null, null, null, skillRegistry);

        String result = assembler.buildReactSystemPrompt();

        assertThat(result).isEqualTo("system prompt");
    }

    @Test
    void buildReactSystemPrompt_inCronMode_usesTaskTemplate() {
        var config = buildConfig();
        var promptRegistry = mock(PromptRegistry.class);

        when(promptRegistry.render(eq("agent/role-definition"))).thenReturn("role");
        when(promptRegistry.render(eq("agent/context-guide"))).thenReturn("guide");
        when(promptRegistry.render(eq("agent/react-system-task"), anyMap())).thenReturn("task mode prompt");

        var assembler = new ContextAssembler(config, promptRegistry,
                null, null, null, null, null, null, null, null, null, null);

        String result = assembler.buildReactSystemPrompt(buildState("cron:daily", "check logs at 8 every day"));

        assertThat(result).contains("task mode prompt");
        verify(promptRegistry).render(eq("agent/react-system-task"), anyMap());
        verify(promptRegistry, never()).render(eq("agent/react-system"), anyMap());
    }

    @Test
    void buildUserPrompt_withTimeConstraint_injectsHint() {
        var config = buildConfig();
        var promptRegistry = mock(PromptRegistry.class);

        when(promptRegistry.render(eq("agent/react-user-prompt-basic"), anyMap())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> vars = invocation.getArgument(1, Map.class);
            return vars.get("timeConstraintSection") + "\nrequest: " + vars.get("userGoal");
        });

        var assembler = new ContextAssembler(config, promptRegistry,
                null, null, null, null, null, null, null, null, null, null);

        String result = assembler.buildUserPrompt(buildState("web", "analyze logs after 2026-03-21 00:00"));

        assertThat(result).contains("<time_constraints>");
        assertThat(result).contains("startTime/endTime");
        assertThat(result).contains("2026-03-21 00:00");
    }

    private AgentConfigProperties buildConfig() {
        var config = new AgentConfigProperties();
        var context = new AgentConfigProperties.ContextConfig();
        context.setMaxContextTokens(8000);
        config.setContext(context);
        return config;
    }

    private ReactAgentState buildState(String channel, String goal) {
        return ReactAgentState.builder()
                .traceId("trace-1")
                .sessionId("session-1")
                .goal(goal)
                .channel(channel)
                .steps(List.of())
                .stepCount(0)
                .shortTermMemory(List.of())
                .mentionedEntities(List.of())
                .budget(Budget.builder()
                        .maxTokens(4000)
                        .tokensUsed(0)
                        .tokensReserved(0)
                        .maxSteps(10)
                        .stepsUsed(0)
                        .maxDuration(Duration.ofMinutes(1))
                        .elapsed(Duration.ZERO)
                        .build())
                .parentTraceId(null)
                .depth(0)
                .preferredProvider(null)
                .done(false)
                .finalOutput(null)
                .terminationReason(null)
                .reasoningSummary(null)
                .allowedToolIds(null)
                .pendingMedia(null)
                .suspended(false)
                .suspendReason(null)
                .build();
    }
}
