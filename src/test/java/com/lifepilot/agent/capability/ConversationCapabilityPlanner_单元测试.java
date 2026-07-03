package com.lifepilot.agent.capability;

import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.AgentTaskMode;
import com.lifepilot.agent.model.Budget;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.interaction.model.InteractionSource;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.model.SkillSource;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.skill.spec.SkillRequires;
import com.lifepilot.skill.spec.SkillZhiweiMeta;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolBudget;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 对话能力预发现器单元测试。
 *
 * @author zsg
 * @since 2026-07-02
 */
class ConversationCapabilityPlanner_单元测试 {

    @Test
    void Git目标应预发现已注册的Git工具() {
        var registry = new DynamicToolRegistry(_ -> {});
        registry.registerBuiltinTool(tool("git.query"));
        registry.registerBuiltinTool(tool("git.mutate"));
        var planner = new ConversationCapabilityPlanner(registry);
        ReactAgentState state = state("帮我看 git diff，然后提交代码");

        ReactAgentState enriched = planner.enrich(state);

        assertThat(enriched.discoveredToolIds())
                .contains("git.query", "git.mutate");
        assertThat(planner.suggest(state))
                .extracting(ConversationCapabilityPlanner.SuggestedCapability::label)
                .contains("查看仓库", "提交分支");
    }

    @Test
    void 未注册工具不应进入发现集合() {
        var planner = new ConversationCapabilityPlanner(new DynamicToolRegistry(_ -> {}));

        ReactAgentState enriched = planner.enrich(state("打开网页，点击页面上的按钮"));

        assertThat(enriched.discoveredToolIds()).isNull();
    }

    @Test
    void 受限白名单场景不应预发现额外工具() {
        var registry = new DynamicToolRegistry(_ -> {});
        registry.registerBuiltinTool(tool("browser"));
        var planner = new ConversationCapabilityPlanner(registry);
        ReactAgentState restricted = state("打开网页")
                .toBuilder()
                .allowedToolIds(List.of("memory"))
                .build();

        ReactAgentState enriched = planner.enrich(restricted);

        assertThat(enriched.discoveredToolIds()).isNull();
    }

    @Test
    void Answer模式不应预发现工具() {
        var registry = new DynamicToolRegistry(_ -> {});
        registry.registerBuiltinTool(tool("web.search"));
        var planner = new ConversationCapabilityPlanner(registry);
        ReactAgentState answerOnly = state("帮我查最新新闻")
                .toBuilder()
                .taskMode(AgentTaskMode.ANSWER)
                .build();

        ReactAgentState enriched = planner.enrich(answerOnly);

        assertThat(enriched.discoveredToolIds()).isNull();
    }

    @Test
    void 工具原文目标应规划Transcript工具() {
        var planner = new ConversationCapabilityPlanner(new DynamicToolRegistry(_ -> {}));

        Set<String> planned = planner.planToolIds("把上次工具调用原文按 callId 取出来");

        assertThat(planned).contains("transcript.search", "transcript.get");
    }

    @Test
    void 能力建议事件应携带Trace和Turn信息() {
        var registry = new DynamicToolRegistry(_ -> {});
        registry.registerBuiltinTool(tool("ui.render"));
        var planner = new ConversationCapabilityPlanner(registry);
        ReactAgentState state = state("给我做一个可交互表单")
                .toBuilder()
                .turnId("turn-1")
                .build();

        var suggestions = planner.suggest(state);
        var event = planner.toEvent(state, suggestions);

        assertThat(event.traceId()).isEqualTo(state.traceId());
        assertThat(event.turnId()).isEqualTo("turn-1");
        assertThat(event.tools()).extracting(ConversationCapabilityPlanner.SuggestedCapability::id)
                .containsExactly("ui.render");
        assertThat(event.tools()).extracting(ConversationCapabilityPlanner.SuggestedCapability::kind)
                .containsExactly("tool");
    }

    @Test
    void 调研目标应提示已注册Skill但不污染工具发现集合() {
        var toolRegistry = new DynamicToolRegistry(_ -> {});
        var skillRegistry = mock(SkillRegistry.class);
        when(skillRegistry.find("research-assistant"))
                .thenReturn(Optional.of(skill("research-assistant")));
        var planner = new ConversationCapabilityPlanner(toolRegistry, skillRegistry, null);
        ReactAgentState state = state("帮我做一个竞品调研和事实核查");

        var suggestions = planner.suggest(state);
        ReactAgentState enriched = planner.enrich(state);

        assertThat(suggestions)
                .extracting(ConversationCapabilityPlanner.SuggestedCapability::kind)
                .contains("skill");
        assertThat(suggestions)
                .extracting(ConversationCapabilityPlanner.SuggestedCapability::label)
                .contains("调研策略");
        assertThat(suggestions)
                .filteredOn(s -> "research-assistant".equals(s.id()))
                .allSatisfy(s -> assertThat(s.outputs()).contains("text", "file"));
        assertThat(enriched.discoveredToolIds()).isNull();
    }

    private ReactAgentState state(String goal) {
        var request = new AgentRequest(goal, "s1", InteractionSource.system("test"));
        return ReactAgentState.init(request, budget());
    }

    private Budget budget() {
        return Budget.builder()
                .maxTokens(1000)
                .tokensUsed(0)
                .tokensReserved(0)
                .maxSteps(20)
                .stepsUsed(0)
                .maxDuration(Duration.ofMinutes(5))
                .elapsed(Duration.ZERO)
                .build();
    }

    private BuiltinTool tool(String id) {
        return BuiltinTool.builder()
                .id(id)
                .name("测试工具")
                .description("用于单元测试的工具描述，验证能力预发现会过滤未注册工具。")
                .inputSchema(JsonSchema.empty())
                .outputSchema(JsonSchema.empty())
                .riskLevel(RiskLevel.LOW)
                .idempotent(true)
                .executionSemantics(ToolExecutionSemantics.generic())
                .budget(ToolBudget.DEFAULT)
                .tags(List.of("测试", "能力", "工具"))
                .category(ToolCategory.PERCEPTION)
                .executor(_ -> null)
                .build();
    }

    private SkillDefinition skill(String id) {
        return SkillDefinition.builder()
                .id(id)
                .name(id)
                .description("当用户需要相关任务策略时使用。")
                .version("1.0.0")
                .source(new SkillSource.UserDefined("skills/" + id, Instant.now()))
                .instructions("""
                        ## 触发判断
                        - 测试
                        ## 决策路径
                        - 判断后执行
                        ## 输出标准
                        - 文本和文件
                        ## 失败策略
                        - 失败时说明原因
                        """)
                .suggestedTools(List.of())
                .metadata(Map.of())
                .zhiweiMeta(new SkillZhiweiMeta(
                        List.of(), List.of("research"), List.of("text", "file"), SkillRequires.empty()))
                .build();
    }
}
