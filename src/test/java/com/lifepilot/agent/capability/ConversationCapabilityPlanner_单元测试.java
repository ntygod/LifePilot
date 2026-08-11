package com.lifepilot.agent.capability;

import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.AgentTaskMode;
import com.lifepilot.agent.model.Budget;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.interaction.model.InteractionSource;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolBudget;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeout;

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
    void 配置关闭时应完全跳过能力预发现() {
        var registry = new DynamicToolRegistry(_ -> {});
        registry.registerBuiltinTool(tool("web.search"));
        registry.registerBuiltinTool(tool("web.fetch"));
        var planner = new ConversationCapabilityPlanner(registry, false, 96, 320);
        ReactAgentState state = state("帮我调研今日最新资讯");

        ReactAgentState enriched = planner.enrich(state);

        assertThat(planner.planToolIds("帮我调研今日最新资讯")).isEmpty();
        assertThat(planner.suggest(state)).isEmpty();
        assertThat(enriched.discoveredToolIds()).isNull();
    }

    @Test
    void 非法预发现探针配置应启动失败() {
        var registry = new DynamicToolRegistry(_ -> {});

        assertThatThrownBy(() -> new ConversationCapabilityPlanner(registry, true, 0, 320))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("控制前缀字符数");
        assertThatThrownBy(() -> new ConversationCapabilityPlanner(registry, true, 96, 19))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("探针最大字符数");
    }

    @Test
    void 工具原文目标应规划Transcript工具() {
        var planner = new ConversationCapabilityPlanner(new DynamicToolRegistry(_ -> {}));

        Set<String> planned = planner.planToolIds("把上次工具调用原文按 callId 取出来");

        assertThat(planned).contains("transcript.search", "transcript.get");
    }

    @Test
    void 普通日期表达不应预发现联网工具() {
        var planner = new ConversationCapabilityPlanner(new DynamicToolRegistry(_ -> {}));

        Set<String> planned = planner.planToolIds("今天帮我整理一下项目计划");

        assertThat(planned).doesNotContain("web.search", "web.fetch");
    }

    @Test
    void 最新资讯和日期查询仍应预发现联网工具() {
        var planner = new ConversationCapabilityPlanner(new DynamicToolRegistry(_ -> {}));

        assertThat(planner.planToolIds("帮我调研一下今日最新资讯"))
                .contains("web.search", "web.fetch");
        assertThat(planner.planToolIds("查一下今天的行业新闻"))
                .contains("web.search", "web.fetch");
    }

    @Test
    void 建议列表只包含已注册工具() {
        var registry = new DynamicToolRegistry(_ -> {});
        registry.registerBuiltinTool(tool("ui.render"));
        var planner = new ConversationCapabilityPlanner(registry);
        ReactAgentState state = state("给我做一个可交互表单")
                .toBuilder()
                .turnId("turn-1")
                .build();

        var suggestions = planner.suggest(state);

        assertThat(suggestions).extracting(ConversationCapabilityPlanner.SuggestedCapability::id)
                .containsExactly("ui.render");
        assertThat(suggestions)
                .extracting(ConversationCapabilityPlanner.SuggestedCapability::label)
                .containsExactly("交互组件");
    }

    @Test
    void 预发现工具顺序应稳定追加到已有工具之后() {
        var registry = new DynamicToolRegistry(_ -> {});
        registry.registerBuiltinTool(tool("web.search"));
        registry.registerBuiltinTool(tool("web.fetch"));
        var planner = new ConversationCapabilityPlanner(registry);
        ReactAgentState state = state("帮我调研今日最新资讯")
                .toBuilder()
                .discoveredToolIds(new LinkedHashSet<>(List.of("memory.search")))
                .build();

        ReactAgentState enriched = planner.enrich(state);

        assertThat(enriched.discoveredToolIds())
                .containsExactly("memory.search", "web.search", "web.fetch");
        assertThat(planner.planToolIds("帮我调研今日最新资讯"))
                .containsExactly("web.search", "web.fetch");
    }

    @Test
    void 状态禁用父级工具时不应预发现对应子工具() {
        var registry = new DynamicToolRegistry(_ -> {});
        registry.registerBuiltinTool(tool("web.search"));
        registry.registerBuiltinTool(tool("web.fetch"));
        registry.registerBuiltinTool(tool("shell.exec"));
        var planner = new ConversationCapabilityPlanner(registry);
        ReactAgentState state = state("帮我调研今日最新资讯，并用命令行看一下日志")
                .toBuilder()
                .disabledToolIds(List.of("web"))
                .build();

        ReactAgentState enriched = planner.enrich(state);

        assertThat(enriched.discoveredToolIds())
                .contains("shell.exec")
                .doesNotContain("web.search", "web.fetch");
        assertThat(planner.suggest(state))
                .extracting(ConversationCapabilityPlanner.SuggestedCapability::id)
                .doesNotContain("web.search", "web.fetch");
    }

    @Test
    void 显式跳过意图识别时不做能力预发现() {
        var registry = new DynamicToolRegistry(_ -> {});
        registry.registerBuiltinTool(tool("web.search"));
        registry.registerBuiltinTool(tool("web.fetch"));
        registry.registerBuiltinTool(tool("shell.exec"));
        var planner = new ConversationCapabilityPlanner(registry);
        ReactAgentState state = state("跳过意图识别，直接回答：帮我调研今日最新资讯并运行验证");

        ReactAgentState enriched = planner.enrich(state);

        assertThat(enriched.discoveredToolIds()).isNull();
        assertThat(planner.planToolIds("跳过意图识别，直接回答：帮我调研今日最新资讯并运行验证"))
                .isEmpty();
    }

    @Test
    void 口语化跳过意图分析时不做能力预发现() {
        var registry = new DynamicToolRegistry(_ -> {});
        registry.registerBuiltinTool(tool("web.search"));
        registry.registerBuiltinTool(tool("web.fetch"));
        registry.registerBuiltinTool(tool("shell.exec"));
        var planner = new ConversationCapabilityPlanner(registry);
        ReactAgentState state = state("别分析意图，帮我调研今日最新资讯并运行验证");

        ReactAgentState enriched = planner.enrich(state);

        assertThat(enriched.discoveredToolIds()).isNull();
        assertThat(planner.planToolIds("别分析意图，帮我调研今日最新资讯并运行验证"))
                .isEmpty();
        assertThat(planner.planToolIds("不做能力预判：帮我调研今日最新资讯并运行验证"))
                .isEmpty();
    }

    @Test
    void 直接做和不用判断时不做能力预发现() {
        var registry = new DynamicToolRegistry(_ -> {});
        registry.registerBuiltinTool(tool("web.search"));
        registry.registerBuiltinTool(tool("web.fetch"));
        registry.registerBuiltinTool(tool("shell.exec"));
        var planner = new ConversationCapabilityPlanner(registry);
        ReactAgentState directState = state("直接做：帮我调研今日最新资讯并运行验证");
        ReactAgentState skipState = state("不用判断，帮我调研今日最新资讯并运行验证");

        assertThat(planner.enrich(directState).discoveredToolIds()).isNull();
        assertThat(planner.enrich(skipState).discoveredToolIds()).isNull();
        assertThat(planner.planToolIds("直接开始，帮我调研今日最新资讯并运行验证"))
                .isEmpty();
        assertThat(planner.planToolIds("直接帮我调研今日最新资讯并运行验证"))
                .isEmpty();
        assertThat(planner.planToolIds("不用判断，帮我调研今日最新资讯并运行验证"))
                .isEmpty();
    }

    @Test
    void 正文提到直接回答不应误判为跳过能力预发现() {
        var planner = new ConversationCapabilityPlanner(new DynamicToolRegistry(_ -> {}));

        assertThat(planner.planToolIds("帮我调研一句话里的关键词：用户喜欢直接回答今日新闻"))
                .contains("web.search", "web.fetch");
        assertThat(planner.planToolIds("帮我调研一句话里的关键词：用户说直接做今日新闻"))
                .contains("web.search", "web.fetch");
        assertThat(planner.planToolIds("帮我调研一句话里的关键词：用户说不用判断今日新闻"))
                .contains("web.search", "web.fetch");
    }

    @Test
    void 正文提到别分析意图不应误判为跳过能力预发现() {
        var planner = new ConversationCapabilityPlanner(new DynamicToolRegistry(_ -> {}));

        assertThat(planner.planToolIds("帮我调研一句话里的关键词：用户说别分析意图也要看新闻"))
                .contains("web.search", "web.fetch");
    }

    @Test
    void 明确不要联网时只跳过网页工具预发现() {
        var planner = new ConversationCapabilityPlanner(new DynamicToolRegistry(_ -> {}));

        assertThat(planner.planToolIds("不要联网，帮我调研今日最新资讯，并用命令行查看日志"))
                .contains("shell.exec", "shell.process")
                .doesNotContain("web.search", "web.fetch");
    }

    @Test
    void 口语化不要查资料时只跳过网页工具预发现() {
        var planner = new ConversationCapabilityPlanner(new DynamicToolRegistry(_ -> {}));

        assertThat(planner.planToolIds("别查资料，帮我调研今日最新资讯，并用命令行查看日志"))
                .contains("shell.exec", "shell.process")
                .doesNotContain("web.search", "web.fetch");
    }

    @Test
    void 只根据用户提供资料时应跳过网页工具预发现() {
        var planner = new ConversationCapabilityPlanner(new DynamicToolRegistry(_ -> {}));

        assertThat(planner.planToolIds("只根据我给的资料总结今日最新新闻，并用命令行查看日志"))
                .contains("shell.exec", "shell.process")
                .doesNotContain("web.search", "web.fetch");
        assertThat(planner.planToolIds("""
                请总结上传附件：
                今日最新新闻资讯：某产品发布了新版本。
                这里的“最新”只是附件正文的一部分，不代表需要联网。
                """))
                .doesNotContain("web.search", "web.fetch");
    }

    @Test
    void 超长资料正文里的热点词不应触发联网预发现() {
        var planner = new ConversationCapabilityPlanner(new DynamicToolRegistry(_ -> {}));
        String body = "资料正文".repeat(180)
                + " 今日最新新闻资讯实时行情 "
                + "资料正文".repeat(180);

        Set<String> planned = planner.planToolIds("请总结下面资料：\n" + body);

        assertThat(planned).doesNotContain("web.search", "web.fetch");
    }

    @Test
    void 资料正文开头的热点词不应触发联网预发现() {
        var planner = new ConversationCapabilityPlanner(new DynamicToolRegistry(_ -> {}));

        Set<String> planned = planner.planToolIds("""
                请总结下面资料：
                今日最新新闻资讯：某产品发布了新版本。
                这里的“最新”只是资料正文的一部分，不代表需要联网。
                """);

        assertThat(planned).doesNotContain("web.search", "web.fetch");
    }

    @Test
    void 资料任务明确要求核对来源时仍应触发联网预发现() {
        var planner = new ConversationCapabilityPlanner(new DynamicToolRegistry(_ -> {}));

        Set<String> planned = planner.planToolIds("""
                请总结下面资料，并核对来源：
                今日最新新闻资讯：某产品发布了新版本。
                https://example.com/report
                """);

        assertThat(planned).contains("web.search", "web.fetch");
    }

    @Test
    void 超长输入尾部的网页线索仍应触发联网预发现() {
        var planner = new ConversationCapabilityPlanner(new DynamicToolRegistry(_ -> {}));
        String body = "背景资料".repeat(220);

        Set<String> planned = planner.planToolIds("请阅读下面资料，并在最后这个链接里核对来源：\n"
                + body + "\nhttps://example.com/report");

        assertThat(planned).contains("web.search", "web.fetch");
    }

    @Test
    void 超长无换行输入应快速截取头尾探针并保留尾部线索() {
        var planner = new ConversationCapabilityPlanner(new DynamicToolRegistry(_ -> {}));
        String body = "背景资料".repeat(50_000);

        Set<String> planned = assertTimeout(Duration.ofSeconds(1), () -> planner.planToolIds(
                "请阅读下面资料，并在最后这个链接里核对来源：" + body + " https://example.com/report"));

        assertThat(planned).contains("web.search", "web.fetch");
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

}
