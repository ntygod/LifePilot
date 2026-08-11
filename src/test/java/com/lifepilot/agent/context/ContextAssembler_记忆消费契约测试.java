package com.lifepilot.agent.context;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.intelligence.AdaptiveDecisionEngine;
import com.lifepilot.agent.intelligence.model.DecisionSignal;
import com.lifepilot.agent.model.Budget;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.ReactStep;
import com.lifepilot.interaction.model.InteractionSource;
import com.lifepilot.interaction.web.model.ChatSession;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.memory.consumption.hot.HotMemoryDigest;
import com.lifepilot.memory.consumption.hot.HotMemoryDigestService;
import com.lifepilot.memory.consumption.hot.HotMemorySectionKind;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.prompt.PromptRegistry;
import com.lifepilot.project.context.ProjectContext;
import com.lifepilot.project.context.ProjectContextResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ContextAssembler 记忆消费契约测试。
 *
 * @author zsg
 * @since 2026-05-05
 */
@DisplayName("ContextAssembler 记忆消费契约")
class ContextAssembler_记忆消费契约测试 {

    @Test
    void assemble只消费热摘要并记录来源实体Id() {
        var semanticMemory = mock(SemanticMemory.class);
        when(semanticMemory.countByEntityType(any())).thenReturn(Map.of());
        var contextEngine = mock(ContextEngine.class);
        when(contextEngine.load(any(), anyInt())).thenReturn(ContextEngine.ContextSnapshot.empty());
        var hotDigestService = mock(HotMemoryDigestService.class);
        when(hotDigestService.build(any(), anyString())).thenReturn(new HotMemoryDigest(
                "hot-1",
                "personal",
                Instant.now(),
                "rev-1",
                List.of(
                        new HotMemoryDigest.HotMemorySection(
                                HotMemorySectionKind.USER_PROFILE,
                                "L3.5 热记忆 - 用户画像:\n- [L4偏好|EXPLICIT/USER_CONFIRMED|confidence=0.9] response_language = 中文",
                                List.of("pref-1"),
                                500),
                        new HotMemoryDigest.HotMemorySection(
                                HotMemorySectionKind.EXPERIENCE,
                                "L3.5 热记忆 - 高价值经验:\n- [经验|DERIVED/LLM_SUMMARIZED_EXPERIENCE] 先跑测试",
                                List.of("exp-1"),
                                500),
                        new HotMemoryDigest.HotMemorySection(
                                HotMemorySectionKind.FACTS,
                                "L3.5 热记忆 - 常用事实:\n- [主题|VERIFIED/DOCUMENT_GROUNDED] 项目使用 SQLite",
                                List.of("fact-1"),
                                400))));
        var assembler = newAssembler(semanticMemory, contextEngine, hotDigestService);

        var context = assembler.assemble(state("继续优化记忆"));

        String contextText = context.contextMessages().stream()
                .map(org.springframework.ai.chat.messages.Message::getText)
                .reduce("", (left, right) -> left + "\n" + right);
        assertThat(contextText)
                .contains("response_language = 中文")
                .contains("先跑测试")
                .contains("项目使用 SQLite");
        assertThat(context.injectedEntityIds()).containsExactly("pref-1", "exp-1", "fact-1");
        verify(hotDigestService).build(any(), anyString());
        verify(semanticMemory, never()).findCurrentByNameAndType(anyString(), any(), any());
        verify(semanticMemory, never()).findCurrentByType(any(), any());
        verify(semanticMemory, never()).findByIds(any(), any());
    }

    @Test
    void 热摘要构建失败时应直接暴露且不回退旧画像或冷检索路径() {
        var semanticMemory = mock(SemanticMemory.class);
        when(semanticMemory.countByEntityType(any())).thenReturn(Map.of());
        var contextEngine = mock(ContextEngine.class);
        when(contextEngine.load(any(), anyInt())).thenReturn(ContextEngine.ContextSnapshot.empty());
        var hotDigestService = mock(HotMemoryDigestService.class);
        when(hotDigestService.build(any(), anyString())).thenThrow(new IllegalStateException("构建失败"));
        var assembler = newAssembler(semanticMemory, contextEngine, hotDigestService);

        assertThatThrownBy(() -> assembler.assemble(state("继续优化记忆")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("上下文组装失败")
                .hasRootCauseMessage("构建失败");
        verify(semanticMemory, never()).findCurrentByNameAndType(anyString(), any(), any());
        verify(semanticMemory, never()).findCurrentByType(any(), any());
        verify(semanticMemory, never()).findByIds(any(), any());
    }

    @Test
    void 项目上下文解析失败时跳过热摘要且不回退个人记忆() {
        var semanticMemory = mock(SemanticMemory.class);
        when(semanticMemory.countByEntityType(any())).thenReturn(Map.of());
        var contextEngine = mock(ContextEngine.class);
        when(contextEngine.load(any(), anyInt())).thenReturn(ContextEngine.ContextSnapshot.empty());
        var hotDigestService = mock(HotMemoryDigestService.class);
        var chatSessionRepository = mock(ChatSessionRepository.class);
        var projectContextResolver = mock(ProjectContextResolver.class);
        when(chatSessionRepository.findById("session-1"))
                .thenReturn(Optional.of(session("session-1", "project-1")));
        when(projectContextResolver.resolve("project-1"))
                .thenThrow(new IllegalStateException("项目缺失"));
        var assembler = newAssembler(
                semanticMemory,
                contextEngine,
                hotDigestService,
                projectContextResolver,
                chatSessionRepository);

        var context = assembler.assemble(state("继续优化记忆"));

        assertThat(context.injectedEntityIds()).isEmpty();
        verify(hotDigestService, never()).build(any(), anyString());
        verify(semanticMemory, never()).findCurrentByNameAndType(anyString(), any(), any());
        verify(semanticMemory, never()).findCurrentByType(any(), any());
        verify(semanticMemory, never()).findByIds(any(), any());
    }

    @Test
    void 用户明确要求不使用记忆时跳过默认热摘要() {
        var semanticMemory = mock(SemanticMemory.class);
        var contextEngine = mock(ContextEngine.class);
        when(contextEngine.load(any(), anyInt())).thenReturn(ContextEngine.ContextSnapshot.empty());
        var hotDigestService = mock(HotMemoryDigestService.class);
        var assembler = newAssembler(semanticMemory, contextEngine, hotDigestService);

        var context = assembler.assemble(state("请直接回答，不要参考记忆，也不要写入长期记忆。"));

        assertThat(context.injectedEntityIds()).isEmpty();
        verify(hotDigestService, never()).build(any(), anyString());
        verify(semanticMemory, never()).incrementAccessCount(anyString());
    }

    @Test
    void 显式选择记忆上下文时应强化热记忆注入并写入策略提示() {
        var semanticMemory = mock(SemanticMemory.class);
        when(semanticMemory.countByEntityType(any())).thenReturn(Map.of());
        var contextEngine = mock(ContextEngine.class);
        when(contextEngine.load(any(), anyInt())).thenReturn(ContextEngine.ContextSnapshot.empty());
        var hotDigestService = mock(HotMemoryDigestService.class);
        when(hotDigestService.build(any(), anyString())).thenReturn(new HotMemoryDigest(
                "hot-focused",
                "personal",
                Instant.now(),
                "rev-focused",
                List.of(new HotMemoryDigest.HotMemorySection(
                        HotMemorySectionKind.FACTS,
                        "L3.5 热记忆 - 常用事实:\n- [事实|VERIFIED] 用户偏好主界面轻量",
                        List.of("fact-focused"),
                        300))));
        var assembler = newAssembler(semanticMemory, contextEngine, hotDigestService);
        var state = state("请直接回答，不要参考记忆，也不要写入长期记忆。")
                .toBuilder()
                .memoryContextMode("focused")
                .build();

        var context = assembler.assemble(state);
        String contextText = context.contextMessages().stream()
                .map(org.springframework.ai.chat.messages.Message::getText)
                .reduce("", (left, right) -> left + "\n" + right);

        assertThat(contextText).contains("用户偏好主界面轻量");
        assertThat(context.injectedEntityIds()).containsExactly("fact-focused");
        assertThat(assembler.buildUserPrompt(state))
                .contains("<memory_context_policy>")
                .contains("用户本轮显式选择了\"我的记忆\"上下文")
                .contains("memory.search / memory.recall");
        verify(hotDigestService).build(any(), anyString());
    }

    @Test
    void 单轮关闭记忆上下文时跳过热摘要并写入策略提示() {
        var semanticMemory = mock(SemanticMemory.class);
        var contextEngine = mock(ContextEngine.class);
        when(contextEngine.load(any(), anyInt())).thenReturn(ContextEngine.ContextSnapshot.empty());
        var hotDigestService = mock(HotMemoryDigestService.class);
        var adaptiveDecisionEngine = mock(AdaptiveDecisionEngine.class);
        when(adaptiveDecisionEngine.buildDecisionSignal(anyString(), any(), eq(false)))
                .thenReturn(DecisionSignal.empty());
        when(adaptiveDecisionEngine.formatForPrompt(any()))
                .thenReturn(null);
        var assembler = newAssembler(semanticMemory, contextEngine, hotDigestService);
        assembler.setAdaptiveDecisionEngine(adaptiveDecisionEngine);
        var state = state("继续优化记忆")
                .toBuilder()
                .memoryContextMode("off")
                .build();

        var context = assembler.assemble(state);

        assertThat(context.injectedEntityIds()).isEmpty();
        assertThat(assembler.buildUserPrompt(state))
                .contains("<memory_context_policy>")
                .contains("用户本轮关闭了默认长期记忆上下文");
        verify(hotDigestService, never()).build(any(), anyString());
        verify(semanticMemory, never()).incrementAccessCount(anyString());
        verify(adaptiveDecisionEngine, timeout(200)).buildDecisionSignal(eq("继续优化记忆"), any(), eq(false));
    }

    @Test
    void 决策信号预算为零时不注入但后台热身() {
        var semanticMemory = mock(SemanticMemory.class);
        when(semanticMemory.countByEntityType(any())).thenReturn(Map.of());
        var contextEngine = mock(ContextEngine.class);
        when(contextEngine.load(any(), anyInt())).thenReturn(ContextEngine.ContextSnapshot.empty());
        var hotDigestService = mock(HotMemoryDigestService.class);
        var config = new AgentConfigProperties();
        config.getContext().setDecisionSignalTimeoutMs(0);
        var assembler = newAssembler(config, semanticMemory, contextEngine, hotDigestService);
        var adaptiveDecisionEngine = mock(AdaptiveDecisionEngine.class);
        when(adaptiveDecisionEngine.buildDecisionSignal(anyString(), any(), anyBoolean()))
                .thenReturn(new DecisionSignal(
                        new DecisionSignal.ExperienceHint("后台经验", 0.9f, "后台热身", null),
                        List.of(),
                        List.of(),
                        null));
        when(adaptiveDecisionEngine.formatForPrompt(any()))
                .thenReturn("- 可沿用的做法: 后台热身");
        assembler.setAdaptiveDecisionEngine(adaptiveDecisionEngine);

        var context = assembler.assemble(state("帮我整理项目计划"));

        String contextText = context.contextMessages().stream()
                .map(org.springframework.ai.chat.messages.Message::getText)
                .reduce("", (left, right) -> left + "\n" + right);
        assertThat(contextText)
                .doesNotContain("<decision_context>")
                .doesNotContain("后台热身");
        verify(adaptiveDecisionEngine, timeout(200))
                .buildDecisionSignal(eq("帮我整理项目计划"), any(), eq(true));
    }

    @Test
    void 决策信号后台热身慢时同一轮不重复启动() throws Exception {
        var semanticMemory = mock(SemanticMemory.class);
        when(semanticMemory.countByEntityType(any())).thenReturn(Map.of());
        var contextEngine = mock(ContextEngine.class);
        when(contextEngine.load(any(), anyInt())).thenReturn(ContextEngine.ContextSnapshot.empty());
        var hotDigestService = mock(HotMemoryDigestService.class);
        var config = new AgentConfigProperties();
        config.getContext().setDecisionSignalTimeoutMs(0);
        var assembler = newAssembler(config, semanticMemory, contextEngine, hotDigestService);
        var adaptiveDecisionEngine = mock(AdaptiveDecisionEngine.class);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(adaptiveDecisionEngine.buildDecisionSignal(anyString(), any(), anyBoolean()))
                .thenAnswer(_ -> {
                    started.countDown();
                    release.await(1, TimeUnit.SECONDS);
                    return DecisionSignal.empty();
                });
        assembler.setAdaptiveDecisionEngine(adaptiveDecisionEngine);
        var sameTurnState = state("帮我整理项目计划")
                .toBuilder()
                .traceId("trace-decision-warm")
                .turnId("turn-decision-warm")
                .build();

        try {
            assembler.assemble(sameTurnState);
            assertThat(started.await(100, TimeUnit.MILLISECONDS)).isTrue();

            assembler.assemble(sameTurnState);
            Thread.sleep(80);

            verify(adaptiveDecisionEngine, times(1))
                    .buildDecisionSignal(eq("帮我整理项目计划"), any(), eq(true));
        } finally {
            release.countDown();
        }
    }

    @Test
    void 决策信号构建慢时跳过注入且不阻塞上下文组装() throws Exception {
        var semanticMemory = mock(SemanticMemory.class);
        when(semanticMemory.countByEntityType(any())).thenReturn(Map.of());
        var contextEngine = mock(ContextEngine.class);
        when(contextEngine.load(any(), anyInt())).thenReturn(ContextEngine.ContextSnapshot.empty());
        var hotDigestService = mock(HotMemoryDigestService.class);
        var config = new AgentConfigProperties();
        config.getContext().setDecisionSignalTimeoutMs(20);
        var assembler = newAssembler(config, semanticMemory, contextEngine, hotDigestService);
        var adaptiveDecisionEngine = mock(AdaptiveDecisionEngine.class);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(adaptiveDecisionEngine.buildDecisionSignal(anyString(), any(), anyBoolean()))
                .thenAnswer(_ -> {
                    started.countDown();
                    release.await(1, TimeUnit.SECONDS);
                    return new DecisionSignal(
                            new DecisionSignal.ExperienceHint("慢经验", 0.9f, "慢速匹配", null),
                            List.of(),
                            List.of(),
                            null);
                });
        when(adaptiveDecisionEngine.formatForPrompt(any()))
                .thenReturn("- 可沿用的做法: 慢速匹配");
        assembler.setAdaptiveDecisionEngine(adaptiveDecisionEngine);

        long startedAt = System.nanoTime();
        try {
            var context = assembler.assemble(state("帮我整理项目计划"));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            assertThat(started.await(100, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(elapsedMs).isLessThan(300);
            String contextText = context.contextMessages().stream()
                    .map(org.springframework.ai.chat.messages.Message::getText)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertThat(contextText)
                    .doesNotContain("<decision_context>")
                    .doesNotContain("慢速匹配");
        } finally {
            release.countDown();
        }
    }

    @Test
    void 主对话冒烟测试消息跳过默认热摘要() {
        var semanticMemory = mock(SemanticMemory.class);
        var contextEngine = mock(ContextEngine.class);
        when(contextEngine.load(any(), anyInt())).thenReturn(ContextEngine.ContextSnapshot.empty());
        var hotDigestService = mock(HotMemoryDigestService.class);
        var assembler = newAssembler(semanticMemory, contextEngine, hotDigestService);

        var context = assembler.assemble(state("请用一句话回复：这是知微主对话闭环冒烟测试。不要调用工具，不要写入长期记忆。"));

        assertThat(context.injectedEntityIds()).isEmpty();
        verify(hotDigestService, never()).build(any(), anyString());
        verify(semanticMemory, never()).incrementAccessCount(anyString());
    }

    @Test
    void 恢复运行应把失败工具断点注入用户提示词() {
        var assembler = newAssembler(
                mock(SemanticMemory.class),
                mock(ContextEngine.class),
                mock(HotMemoryDigestService.class));
        var state = state("检查项目并修复失败测试")
                .toBuilder()
                .resumedFromTraceId("trace-before-failure")
                .steps(List.of(
                        new ReactStep.ToolCall(
                                "shell.exec",
                                "Shell 执行",
                                "{\"command\":\"npm test\"}",
                                12L,
                                "call-shell-1"),
                        new ReactStep.Observation(
                                "shell.exec",
                                "Shell 执行",
                                false,
                                "{\"error\":\"测试失败：断言不匹配\",\"workingDirectory\":\"D:\\\\WorkSpace\\\\Project\\\\News\"}",
                                0,
                                "call-shell-1")
                ))
                .stepCount(2)
                .build();

        String checkpointSection = assembler.buildTaskRecoveryCheckpointSection(state);

        assertThat(checkpointSection)
                .contains("<task_recovery_checkpoint>")
                .contains("上次 trace: trace-before-failure")
                .contains("继续策略: 从失败断点继续，保留已完成步骤和失败输出，不要重复成功部分。")
                .contains("调用 ID: call-shell-1")
                .contains("工具: Shell 执行")
                .contains("失败分类: 命令执行/COMMAND")
                .contains("工作目录: D:\\WorkSpace\\Project\\News")
                .contains("输入: 执行 `npm test`")
                .contains("输出: 测试失败：断言不匹配")
                .contains("详细输出: 测试失败：断言不匹配")
                .contains("从失败命令后继续执行验证")
                .contains("不要无意义重放已成功步骤");
    }

    @Test
    void 恢复运行应把失败技能主体注入用户提示词() {
        var assembler = newAssembler(
                mock(SemanticMemory.class),
                mock(ContextEngine.class),
                mock(HotMemoryDigestService.class));
        var state = state("按调研技能整理资料")
                .toBuilder()
                .resumedFromTraceId("trace-skill-failure")
                .steps(List.of(
                        new ReactStep.ToolCall(
                                "skill.load",
                                "加载 Skill",
                                "{\"names\":[\"research-assistant\"]}",
                                12L,
                                "call-skill-1"),
                        new ReactStep.Observation(
                                "skill.load",
                                "加载 Skill",
                                false,
                                "{\"error\":\"技能 research-assistant 不存在\"}",
                                0,
                                "call-skill-1")
                ))
                .stepCount(2)
                .build();

        String checkpointSection = assembler.buildTaskRecoveryCheckpointSection(state);

        assertThat(checkpointSection)
                .contains("<task_recovery_checkpoint>")
                .contains("上次 trace: trace-skill-failure")
                .contains("继续策略: 从失败技能步骤继续，保留当前对话、技能主体和失败输出，不要重新规划无关步骤。")
                .contains("调用 ID: call-skill-1")
                .contains("工具: 加载 Skill")
                .contains("执行类型: SKILL")
                .contains("失败分类: 技能/SKILL")
                .contains("操作: 加载技能")
                .contains("关联对象: 技能 research-assistant")
                .contains("输入: 加载技能「research-assistant」")
                .contains("输出: 技能 research-assistant 不存在")
                .contains("确认技能 research-assistant 的名称和依赖是否可用")
                .contains("重新加载技能后继续当前任务");
    }

    @Test
    void 恢复运行应把未返回观察结果的调用状态注入用户提示词() {
        var assembler = newAssembler(
                mock(SemanticMemory.class),
                mock(ContextEngine.class),
                mock(HotMemoryDigestService.class));
        var state = state("按调研技能整理资料")
                .toBuilder()
                .resumedFromTraceId("trace-skill-interrupted")
                .steps(List.of(
                        new ReactStep.ToolCall(
                                "skill.load",
                                "加载 Skill",
                                "{\"names\":[\"research-assistant\"]}",
                                12L,
                                "call-skill-interrupted")
                ))
                .stepCount(1)
                .build();

        String checkpointSection = assembler.buildTaskRecoveryCheckpointSection(state);

        assertThat(checkpointSection)
                .contains("<task_recovery_checkpoint>")
                .contains("上次 trace: trace-skill-interrupted")
                .contains("继续策略: 从已开始但未返回结果的步骤继续，保留已完成步骤，不要重复成功部分。")
                .contains("调用 ID: call-skill-interrupted")
                .contains("工具: 加载 Skill")
                .contains("执行类型: SKILL")
                .contains("失败分类: 技能/SKILL")
                .contains("中断状态: 已开始但没有返回执行结果")
                .contains("输出: 这一步已经开始，但没有返回执行结果。")
                .contains("确认技能 research-assistant 的名称和依赖是否可用");
    }

    @Test
    void 恢复运行应优先把Web恢复上下文注入用户提示词() {
        var assembler = newAssembler(
                mock(SemanticMemory.class),
                mock(ContextEngine.class),
                mock(HotMemoryDigestService.class));
        Map<String, Object> checkpoint = Map.ofEntries(
                Map.entry("kind", "TOOL_FAILURE"),
                Map.entry("callId", "call-shell-1"),
                Map.entry("toolId", "shell.exec"),
                Map.entry("toolName", "Shell 执行"),
                Map.entry("executionKind", "TOOL"),
                Map.entry("failureCategory", "COMMAND"),
                Map.entry("action", "执行命令"),
                Map.entry("interrupted", true),
                Map.entry("workingDirectory", "D:\\WorkSpace\\Project\\News"),
                Map.entry("inputSummary", "执行 `npm test`"),
                Map.entry("inputDetail", "{\"command\":\"npm test\",\"workingDirectory\":\"D:\\\\WorkSpace\\\\Project\\\\News\"}"),
                Map.entry("outputSummary", "测试失败"),
                Map.entry("outputDetail", "AssertionError: expected true to be false"),
                Map.entry("generatedFilePath", "D:\\WorkSpace\\Project\\News\\target\\report.md"),
                Map.entry("artifactRefs", List.of(Map.of(
                        "artifactId", "artifact-1",
                        "fileName", "report.md",
                        "mimeType", "text/markdown",
                        "kind", "FILE",
                        "size", 128L,
                        "downloadUrl", "/api/artifacts/artifact-1/download"))),
                Map.entry("subjectLabel", "命令"),
                Map.entry("subjectNames", List.of("npm test")));
        var state = state("""
                检查项目并修复失败测试

                <resume_user_input>
                从上一轮断点继续。
                </resume_user_input>
                """)
                .toBuilder()
                .turnRecoveryContext(Map.of(
                        "action", "RESUME",
                        "sourceTraceId", "trace-failed-1",
                        "title", "修正后继续",
                        "detail", "先修复失败断言再继续验证",
                        "resumeStrategy", "从已开始但未返回结果的步骤继续，保留已完成步骤，不要重复成功部分。",
                        "checkpoint", checkpoint,
                        "nextActions", List.of("查看命令输出并修正报错原因", "从失败命令后继续执行验证")))
                .steps(List.of())
                .build();

        String checkpointSection = assembler.buildTaskRecoveryCheckpointSection(state);

        assertThat(checkpointSection)
                .contains("<task_recovery_checkpoint>")
                .contains("恢复动作: RESUME")
                .contains("上次 trace: trace-failed-1")
                .contains("标题: 修正后继续")
                .contains("详情: 先修复失败断言再继续验证")
                .contains("继续策略: 从已开始但未返回结果的步骤继续，保留已完成步骤，不要重复成功部分。")
                .contains("调用 ID: call-shell-1")
                .contains("工具: Shell 执行")
                .contains("执行类型: TOOL")
                .contains("失败分类: 命令执行/COMMAND")
                .contains("操作: 执行命令")
                .contains("中断状态: 已开始但没有返回执行结果")
                .contains("工作目录: D:\\WorkSpace\\Project\\News")
                .contains("输入: 执行 `npm test`")
                .contains("输入详情: {\"command\":\"npm test\",\"workingDirectory\":\"D:\\\\WorkSpace\\\\Project\\\\News\"}")
                .contains("输出: 测试失败")
                .contains("详细输出: AssertionError: expected true to be false")
                .contains("生成文件: D:\\WorkSpace\\Project\\News\\target\\report.md")
                .contains("产物复用: 已生成文件可直接复用：D:\\WorkSpace\\Project\\News\\target\\report.md。继续时先检查并引用它，不要无故重复生成或覆盖。")
                .contains("产物引用: report.md（FILE/text/markdown） id=artifact-1 url=/api/artifacts/artifact-1/download。继续时优先复用这些产物，不要无故重复生成。")
                .contains("关联对象: 命令 npm test")
                .contains("查看命令输出并修正报错原因")
                .contains("不要无意义重放已成功步骤");
    }

    @Test
    void 恢复运行应把能力缺失分类以可读语义注入用户提示词() {
        var assembler = newAssembler(
                mock(SemanticMemory.class),
                mock(ContextEngine.class),
                mock(HotMemoryDigestService.class));
        Map<String, Object> checkpoint = Map.ofEntries(
                Map.entry("kind", "TOOL_FAILURE"),
                Map.entry("callId", "call-web-1"),
                Map.entry("toolId", "web.search"),
                Map.entry("toolName", "网页搜索"),
                Map.entry("executionKind", "TOOL"),
                Map.entry("failureCategory", "CAPABILITY"),
                Map.entry("action", "搜索资料"),
                Map.entry("inputSummary", "搜索今天的最新资讯"),
                Map.entry("outputSummary", "工具 web.search 未注册"));
        var state = state("帮我调研今天的最新资讯")
                .toBuilder()
                .turnRecoveryContext(Map.of(
                        "action", "RESUME",
                        "sourceTraceId", "trace-capability-1",
                        "title", "修复能力后继续",
                        "detail", "依赖的工具或技能当前不可用，可以在能力中心修复连接或调整 Skill 元数据后继续。",
                        "resumeStrategy", "先修复缺失工具或技能连接，再从失败断点继续，保留已完成步骤。",
                        "checkpoint", checkpoint,
                        "nextActions", List.of("修复 web.search 工具能力", "从搜索步骤继续整理资料")))
                .steps(List.of())
                .build();

        String checkpointSection = assembler.buildTaskRecoveryCheckpointSection(state);

        assertThat(checkpointSection)
                .contains("<task_recovery_checkpoint>")
                .contains("标题: 修复能力后继续")
                .contains("继续策略: 先修复缺失工具或技能连接，再从失败断点继续，保留已完成步骤。")
                .contains("工具: 网页搜索")
                .contains("失败分类: 能力缺口/CAPABILITY")
                .contains("输出: 工具 web.search 未注册")
                .contains("修复 web.search 工具能力")
                .contains("从搜索步骤继续整理资料");
    }

    @Test
    void 恢复运行应裁剪超长断点输出和恢复计划() {
        var assembler = newAssembler(
                mock(SemanticMemory.class),
                mock(ContextEngine.class),
                mock(HotMemoryDigestService.class));
        String longOutputDetail = "日志开始-" + "x".repeat(1500) + "-日志尾部";
        Map<String, Object> checkpoint = Map.ofEntries(
                Map.entry("kind", "TOOL_FAILURE"),
                Map.entry("toolId", "shell.exec"),
                Map.entry("toolName", "Shell 执行"),
                Map.entry("failureCategory", "COMMAND"),
                Map.entry("outputSummary", "测试失败"),
                Map.entry("outputDetail", longOutputDetail));
        var state = state("检查项目并修复失败测试")
                .toBuilder()
                .turnRecoveryContext(Map.of(
                        "action", "RESUME",
                        "sourceTraceId", "trace-failed-1",
                        "checkpoint", checkpoint,
                        "nextActions", List.of(
                                "查看命令输出",
                                "修正失败断言",
                                "运行最小验证",
                                "更新恢复说明",
                                "整理恢复说明-" + "x".repeat(300) + "-尾部",
                                "第六步不应注入")))
                .steps(List.of())
                .build();

        String checkpointSection = assembler.buildTaskRecoveryCheckpointSection(state);

        String detailLine = checkpointSection.lines()
                .filter(line -> line.startsWith("- 详细输出:"))
                .findFirst()
                .orElseThrow();
        String longActionLine = checkpointSection.lines()
                .filter(line -> line.contains("整理恢复说明-"))
                .findFirst()
                .orElseThrow();
        assertThat(detailLine)
                .contains("日志开始-")
                .endsWith("…")
                .doesNotContain("日志尾部");
        assertThat(longActionLine)
                .endsWith("…")
                .doesNotContain("尾部");
        assertThat(checkpointSection)
                .doesNotContain(longOutputDetail)
                .doesNotContain("第六步不应注入");
        assertThat(checkpointSection.lines().filter(line -> line.startsWith("  - ")).count())
                .isEqualTo(5);
    }

    @Test
    void 重启运行应把结构化重启契约注入用户提示词() {
        var assembler = newAssembler(
                mock(SemanticMemory.class),
                mock(ContextEngine.class),
                mock(HotMemoryDigestService.class));
        var state = state("""
                <restart_original_user_input>
                检查项目并修复失败测试
                </restart_original_user_input>

                <restart_instruction>
                重新开始：重新开始。目标：Shell 执行（命令执行）。
                </restart_instruction>
                """);

        String userPrompt = assembler.buildUserPrompt(state);

        assertThat(userPrompt)
                .contains("<restart_instruction_contract>")
                .contains("这是对同一轮任务的重新开始")
                .contains("<restart_original_user_input> 作为用户原始目标")
                .contains("<restart_instruction> 和恢复断点作为本次重启约束")
                .contains("优先修正上一轮失败点");
    }

    private ContextAssembler newAssembler(SemanticMemory semanticMemory,
                                          ContextEngine contextEngine,
                                          HotMemoryDigestService hotDigestService) {
        var projectContextResolver = mock(ProjectContextResolver.class);
        var chatSessionRepository = mock(ChatSessionRepository.class);
        when(chatSessionRepository.findById("session-1"))
                .thenReturn(Optional.of(session("session-1", null)));
        when(projectContextResolver.resolve(null))
                .thenReturn(ProjectContext.personal("space-personal", "space-experience"));
        return newAssembler(
                new AgentConfigProperties(),
                semanticMemory,
                contextEngine,
                hotDigestService,
                projectContextResolver,
                chatSessionRepository);
    }

    private ContextAssembler newAssembler(SemanticMemory semanticMemory,
                                          ContextEngine contextEngine,
                                          HotMemoryDigestService hotDigestService,
                                          ProjectContextResolver projectContextResolver,
                                          ChatSessionRepository chatSessionRepository) {
        return newAssembler(new AgentConfigProperties(), semanticMemory, contextEngine, hotDigestService,
                projectContextResolver, chatSessionRepository);
    }

    private ContextAssembler newAssembler(AgentConfigProperties config,
                                          SemanticMemory semanticMemory,
                                          ContextEngine contextEngine,
                                          HotMemoryDigestService hotDigestService) {
        var projectContextResolver = mock(ProjectContextResolver.class);
        var chatSessionRepository = mock(ChatSessionRepository.class);
        when(chatSessionRepository.findById("session-1"))
                .thenReturn(Optional.of(session("session-1", null)));
        when(projectContextResolver.resolve(null))
                .thenReturn(ProjectContext.personal("space-personal", "space-experience"));
        return newAssembler(
                config,
                semanticMemory,
                contextEngine,
                hotDigestService,
                projectContextResolver,
                chatSessionRepository);
    }

    private ContextAssembler newAssembler(AgentConfigProperties config,
                                          SemanticMemory semanticMemory,
                                          ContextEngine contextEngine,
                                          HotMemoryDigestService hotDigestService,
                                          ProjectContextResolver projectContextResolver,
                                          ChatSessionRepository chatSessionRepository) {
        var promptRegistry = mock(PromptRegistry.class);
        when(promptRegistry.render(anyString())).thenReturn("");
        when(promptRegistry.render(anyString(), anyMap())).thenReturn("");
        var assembler = new ContextAssembler(
                config,
                promptRegistry,
                null,
                semanticMemory,
                null,
                null,
                null,
                contextEngine,
                null,
                null,
                null,
                null);
        assembler.setProjectContextResolver(projectContextResolver);
        assembler.setChatSessionRepository(chatSessionRepository);
        assembler.setHotMemoryDigestService(hotDigestService);
        return assembler;
    }

    private ReactAgentState state(String goal) {
        return ReactAgentState.builder()
                .traceId("trace-1")
                .sessionId("session-1")
                .goal(goal)
                .source(InteractionSource.system("test"))
                .steps(List.of())
                .shortTermMemory(List.of())
                .mentionedEntities(List.of())
                .budget(Budget.builder()
                        .maxTokens(4096)
                        .maxSteps(10)
                        .maxDuration(Duration.ofMinutes(5))
                        .elapsed(Duration.ZERO)
                        .build())
                .completionMode(com.lifepilot.agent.model.CompletionMode.NORMAL)
                .build();
    }

    private ChatSession session(String id, String projectId) {
        Instant now = Instant.now();
        return new ChatSession(id, "title", null, 0, false, false, null, now, now, projectId);
    }
}
