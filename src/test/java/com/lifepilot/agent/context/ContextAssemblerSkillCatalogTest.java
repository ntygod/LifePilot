package com.lifepilot.agent.context;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.model.Budget;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.datastore.model.Collection;
import com.lifepilot.datastore.repository.CollectionRepository;
import com.lifepilot.interaction.web.repository.SessionDatastoreRepository;
import com.lifepilot.interaction.web.repository.SessionKnowledgeBaseRepository;
import com.lifepilot.knowledge.model.KnowledgeBase;
import com.lifepilot.knowledge.repository.KnowledgeBaseRepository;
import com.lifepilot.prompt.PromptRegistry;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.model.SkillSource;
import com.lifepilot.skill.registry.SkillRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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

/**
 * ContextAssembler Skill Catalog 测试。
 *
 * @author zsg
 * @since 2026-03-24
 */
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
                .thenReturn("<skill_catalog>\n- todo: manage tasks\n</skill_catalog>");

        var skill = SkillDefinition.builder()
                .id("todo")
                .name("task management")
                .description("manage tasks")
                .version("1.0")
                .instructions("instructions")
                .suggestedTools(List.of())
                .source(new SkillSource.UserDefined("/test", null))
                .metadata(Map.of())
                .build();
        when(skillRegistry.listAll()).thenReturn(List.of(skill));

        var assembler = new ContextAssembler(config, promptRegistry,
                null, null, null, null, null, skillRegistry);

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
                null, null, null, null, null, skillRegistry);

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
                null, null, null, null, null, null);

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
                .id("test")
                .name("test")
                .description("test")
                .version("1.0")
                .instructions("instructions")
                .suggestedTools(List.of())
                .source(new SkillSource.UserDefined("/test", null))
                .metadata(Map.of())
                .build();
        when(skillRegistry.listAll()).thenReturn(List.of(skill));
        when(promptRegistry.render(eq("agent/skill-catalog"), anyMap()))
                .thenThrow(new RuntimeException("missing template"));

        var assembler = new ContextAssembler(config, promptRegistry,
                null, null, null, null, null, skillRegistry);

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
                null, null, null, null, null, null);

        String result = assembler.buildReactSystemPrompt(buildState("cron:daily", "check logs at 8 every day"));

        assertThat(result).contains("task mode prompt");
        verify(promptRegistry).render(eq("agent/react-system-task"), anyMap());
        verify(promptRegistry, never()).render(eq("agent/react-system"), anyMap());
    }

    @Test
    void buildUserPrompt_putsRuntimeContextAndCurrentRequestIntoUserPrompt() {
        var config = buildConfig();
        var promptRegistry = mock(PromptRegistry.class);

        when(promptRegistry.render(eq("agent/react-user-prompt"), anyMap())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> vars = invocation.getArgument(1, Map.class);
            return ("""
                    <runtime_context>
                    - 当前时间: %s
                    - 当前时区: %s
                    - 当前操作系统: %s %s
                    - 当前通道: %s
                    </runtime_context>
                    """.formatted(
                    vars.get("currentDateTime"),
                    vars.get("timezone"),
                    vars.get("osName"),
                    vars.get("osVersion"),
                    vars.get("channel")
            ).trim() + "\n\n"
                    + "<current_request>\n" + vars.get("userGoal") + "\n</current_request>").trim();
        });

        var assembler = new ContextAssembler(config, promptRegistry,
                null, null, null, null, null, null);

        String result = assembler.buildUserPrompt(buildState("web", "analyze logs after 2026-03-21 00:00"));

        assertThat(result)
                .contains("<runtime_context>")
                .contains("<current_request>")
                .contains("当前时间")
                .contains("当前时区")
                .contains("当前操作系统")
                .contains("analyze logs after 2026-03-21 00:00");
    }

    @Test
    void buildUserPrompt_whenSessionBoundToDatastoreAndKnowledgeBase_appendsBindingHints() {
        var config = buildConfig();
        var promptRegistry = mock(PromptRegistry.class);
        var sessionKnowledgeBaseRepository = mock(SessionKnowledgeBaseRepository.class);
        var sessionDatastoreRepository = mock(SessionDatastoreRepository.class);
        var knowledgeBaseRepository = mock(KnowledgeBaseRepository.class);
        var collectionRepository = mock(CollectionRepository.class);

        when(promptRegistry.render(eq("agent/react-user-prompt"), anyMap())).thenReturn("""
                <runtime_context>
                - 当前时间: 2026-03-27T16:55:12+08:00
                - 当前时区: Asia/Shanghai
                - 当前操作系统: Windows 11 10.0
                - 当前通道: web
                </runtime_context>

                <current_request>
                春季旅游
                </current_request>
                """.trim());
        when(sessionDatastoreRepository.findDatastoreIdsBySessionId("session-1"))
                .thenReturn(List.of("ds-xhs"));
        when(sessionKnowledgeBaseRepository.findKnowledgeBaseIdsBySessionId("session-1"))
                .thenReturn(List.of("kb-xhs"));
        when(collectionRepository.findById("ds-xhs"))
                .thenReturn(java.util.Optional.of(new Collection(
                        "ds-xhs", "小红书集合", "春季旅游素材", false,
                        null, null, null, Instant.now().toString(), Instant.now().toString()
                )));
        when(knowledgeBaseRepository.findById("kb-xhs"))
                .thenReturn(java.util.Optional.of(new KnowledgeBase(
                        "kb-xhs", "小红书资料库", "", null, null, "smart", Map.of(),
                        0, 0, List.of(), Instant.now(), Instant.now(), false, null, List.of()
                )));

        var assembler = new ContextAssembler(
                config,
                promptRegistry,
                null, null, null, null, null, null,
                null, null,
                sessionKnowledgeBaseRepository,
                sessionDatastoreRepository,
                knowledgeBaseRepository,
                collectionRepository
        );

        String result = assembler.buildUserPrompt(buildState("web", "春季旅游"));

        assertThat(result)
                .contains("<active_knowledge_bindings>")
                .contains("小红书集合 [GENERAL] (ds-xhs)")
                .contains("小红书资料库 (kb-xhs)")
                .contains("knowledge.search");
    }

    @Test
    void buildAugmentedSystemPrompt_keepsRuntimeContextOutOfSystemPrompt() {
        var config = buildConfig();
        var promptRegistry = mock(PromptRegistry.class);

        when(promptRegistry.render(eq("agent/role-definition"))).thenReturn("role");
        when(promptRegistry.render(eq("agent/context-guide"))).thenReturn("guide");
        when(promptRegistry.render(eq("agent/react-system"), anyMap())).thenReturn("system prompt");
        when(promptRegistry.render(eq("memory/agentic-tool-guide"))).thenReturn("");

        var assembler = new ContextAssembler(config, promptRegistry,
                null, null, null, null, null, null);

        String result = assembler.buildAugmentedSystemPrompt(
                buildState("web", "分析 2026-03-21 00:00 之后的日志")
        );

        assertThat(result).contains("system prompt");
        assertThat(result).doesNotContain("<runtime_context>");
        assertThat(result).doesNotContain("<time_constraints>");
    }

    @Test
    void buildContextMessages_putsInjectedContextIntoXmlMessages() {
        var assembler = new ContextAssembler(buildConfig(), mock(PromptRegistry.class),
                null, null, null, null, null, null);

        var messages = assembler.buildContextMessages(
                "用户画像",
                "工作区",
                "产物摘要",
                "经验片段",
                "记忆上下文",
                ContextAssembler.MemoryCounts.EMPTY
        );

        assertThat(messages).hasSize(5);
        assertThat(messages.getFirst().getText()).contains("<user_profile_context>");
        assertThat(messages.getFirst().getText()).contains("用户画像");
        assertThat(messages.get(1).getText()).contains("<workspace_context>");
        assertThat(messages.get(1).getText()).contains("工作区");
        assertThat(messages.get(2).getText()).contains("<artifact_context>");
        assertThat(messages.get(2).getText()).contains("产物摘要");
        assertThat(messages.get(3).getText()).contains("<experience_context>");
        assertThat(messages.get(3).getText()).contains("经验片段");
        assertThat(messages.getLast().getText()).contains("<memory_context>");
        assertThat(messages.getLast().getText()).contains("记忆上下文");
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

    // ── stripYamlFrontmatter 测试 ──

    @Test
    void stripYamlFrontmatter_正常剥离frontmatter() {
        String input = "---\nid: test\nname: 测试\n---\n# 标题\n正文内容";
        assertThat(ContextAssembler.stripYamlFrontmatter(input)).isEqualTo("# 标题\n正文内容");
    }

    @Test
    void stripYamlFrontmatter_无frontmatter时原样返回() {
        String input = "# 标题\n正文内容";
        assertThat(ContextAssembler.stripYamlFrontmatter(input)).isEqualTo("# 标题\n正文内容");
    }

    @Test
    void stripYamlFrontmatter_仅有开头分隔符时原样返回() {
        String input = "---\nid: test\nname: 测试";
        assertThat(ContextAssembler.stripYamlFrontmatter(input)).isEqualTo(input);
    }

    @Test
    void stripYamlFrontmatter_frontmatter后无内容时返回空() {
        String input = "---\nid: test\n---";
        assertThat(ContextAssembler.stripYamlFrontmatter(input)).isEmpty();
    }

    @Test
    void stripYamlFrontmatter_处理CRLF换行() {
        String input = "---\r\nid: test\r\n---\r\n# 标题\r\n正文";
        assertThat(ContextAssembler.stripYamlFrontmatter(input)).isEqualTo("# 标题\n正文");
    }
}
