package com.lifepilot.agent.streaming;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.Budget;
import com.lifepilot.agent.model.CompletionMode;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.ReactStep;
import com.lifepilot.agent.model.SuspendReason;
import com.lifepilot.agent.learning.extraction.MemoryExtractionCandidateRepository;
import com.lifepilot.interaction.model.ArtifactRef;
import com.lifepilot.memory.consumption.quality.MemoryEvidenceKind;
import com.lifepilot.memory.consumption.quality.MemoryTrustLevel;
import com.lifepilot.memory.governance.lifecycle.LifecycleState;
import com.lifepilot.memory.governance.lifecycle.Temporality;
import com.lifepilot.memory.retrieval.InjectionRecordRepository;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import com.lifepilot.tool.model.ArtifactKind;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * StreamingEventHandler 记忆来源摘要测试。
 *
 * @author zsg
 * @since 2026-07-04
 */
class StreamingEventHandler记忆来源测试 {

    @Test
    @SuppressWarnings("unchecked")
    void DONE事件应带出本轮执行约束摘要() {
        var handler = new StreamingEventHandler(
                new ObjectMapper(), null, null, null, null, null, null);
        var request = new AgentRequest(
                "不要联网，整理本地资料",
                "session-constraints",
                com.lifepilot.interaction.model.InteractionSource.legacy("web", "session-constraints"),
                null,
                null,
                null,
                null,
                0,
                null,
                null,
                List.of("web", "browser"),
                null,
                null);
        var state = ReactAgentState.init(request, testBudget());

        var payload = handler.buildDoneEventPayload(
                request, state, "turn-constraints", null, List.of(), null,
                "已按本地资料整理。", "assistant-entry-constraints", null, null);

        var executionConstraints = (Map<String, Object>) payload.get("executionConstraints");
        assertThat(executionConstraints).isNotNull();
        var disabledTools = (List<Map<String, Object>>) executionConstraints.get("disabledTools");
        assertThat(disabledTools)
                .extracting(item -> item.get("id"))
                .containsExactly("web", "browser");
        assertThat(disabledTools)
                .extracting(item -> item.get("reason"))
                .containsOnly("按本轮用户要求禁用");
    }

    @Test
    @SuppressWarnings("unchecked")
    void DONE事件应带出本轮最终产物引用() {
        var handler = new StreamingEventHandler(
                new ObjectMapper(), null, null, null, null, null, null);
        var request = new AgentRequest("生成报告", "session-1", "web");
        var state = ReactAgentState.init(request, testBudget());

        var payload = handler.buildDoneEventPayload(
                request, state, "turn-1", null, List.of(), null,
                "报告已生成。", "assistant-entry-1", null, null,
                List.of(new ArtifactRef(
                        "artifact-1",
                        "report.md",
                        "text/markdown",
                        ArtifactKind.FILE,
                        128L)));

        var artifactRefs = (List<Map<String, Object>>) payload.get("artifactRefs");
        assertThat(artifactRefs).hasSize(1);
        assertThat(artifactRefs.getFirst())
                .containsEntry("artifactId", "artifact-1")
                .containsEntry("fileName", "report.md")
                .containsEntry("mimeType", "text/markdown")
                .containsEntry("kind", "FILE")
                .containsEntry("size", 128L)
                .containsEntry("downloadUrl", "/api/artifacts/artifact-1/download");
    }

    @Test
    @SuppressWarnings("unchecked")
    void DONE事件应把本轮产物引用合入失败恢复断点() {
        var handler = new StreamingEventHandler(
                new ObjectMapper(), null, null, null, null, null, null);
        var request = new AgentRequest("生成报告并跑测试", "session-1", "web");
        var state = ReactAgentState.init(request, testBudget())
                .toBuilder()
                .completionMode(CompletionMode.DEGRADED)
                .terminationReason("测试失败")
                .build();
        var steps = List.<ReactStep>of(
                new ReactStep.ToolCall(
                        "shell.exec",
                        "Shell 执行",
                        "{\"command\":\"npm test\"}",
                        37,
                        "call-shell-1"),
                new ReactStep.Observation(
                        "shell.exec",
                        "Shell 执行",
                        false,
                        "{\"error\":\"测试失败\"}",
                        0,
                        "call-shell-1")
        );

        var payload = handler.buildDoneEventPayload(
                request, state, "turn-1", null, steps, null,
                "报告已生成，但测试失败。", "assistant-entry-1", null, null,
                List.of(new ArtifactRef(
                        "artifact-report",
                        "report.md",
                        "text/markdown",
                        ArtifactKind.FILE,
                        128L)));

        var artifactRefs = (List<Map<String, Object>>) payload.get("artifactRefs");
        assertThat(artifactRefs).hasSize(1);
        var summaries = (List<Map<String, Object>>) payload.get("toolsSummary");
        assertThat((List<Map<String, Object>>) summaries.getFirst().get("artifactRefs"))
                .containsExactlyElementsOf(artifactRefs);
        var recoveryActions = (List<Map<String, Object>>) summaries.getFirst().get("recoveryActions");
        assertThat((List<Map<String, Object>>) recoveryActions.getFirst().get("artifactRefs"))
                .containsExactlyElementsOf(artifactRefs);

        var recovery = (Map<String, Object>) payload.get("taskRecovery");
        var checkpoint = (Map<String, Object>) recovery.get("checkpoint");
        assertThat((List<Map<String, Object>>) checkpoint.get("artifactRefs"))
                .containsExactlyElementsOf(artifactRefs);
    }

    @Test
    @SuppressWarnings("unchecked")
    void DONE事件应带出本轮注入记忆来源() {
        var injectionRecordRepository = mock(InjectionRecordRepository.class);
        var semanticMemory = mock(SemanticMemory.class);
        var handler = new StreamingEventHandler(
                new ObjectMapper(), null, null, null, injectionRecordRepository, semanticMemory, null);
        var request = new AgentRequest("帮我整理今天的计划", "session-1", "web");
        var state = ReactAgentState.init(request, testBudget());

        when(injectionRecordRepository.findEntityIdsBySourceEntryId("assistant-entry-1"))
                .thenReturn(List.of("memory-1", "missing-memory"));
        when(semanticMemory.findById("memory-1"))
                .thenReturn(Optional.of(memory("memory-1", "用户偏好")));
        when(semanticMemory.findById("missing-memory"))
                .thenReturn(Optional.empty());

        var payload = handler.buildDoneEventPayload(
                request, state, "turn-1", null, List.of(), null,
                "已经整理好了。", "assistant-entry-1", null, null);

        var sources = (List<Map<String, Object>>) payload.get("sources");
        assertThat(sources).hasSize(1);
        assertThat(sources.getFirst())
                .containsEntry("type", "memory")
                .containsEntry("id", "memory-1")
                .containsEntry("name", "用户偏好");

        var extra = (Map<String, Object>) sources.getFirst().get("extra");
        assertThat(extra)
                .containsEntry("sourceKind", "INJECTED")
                .containsEntry("sourceKindLabel", "本轮实际参考")
                .containsEntry("usageReason", "这条回答参考了「用户偏好」这条偏好：用户希望主界面保持轻量。")
                .containsEntry("usageImpact", "会影响语气、方案取舍和界面建议；长期生效；优先级较高；有 2 条证据支撑")
                .containsEntry("entityType", "PREFERENCE")
                .containsEntry("entityTypeLabel", "偏好")
                .containsEntry("sourceConversationId", "session-1")
                .containsEntry("extractionConfidence", 0.92f)
                .containsEntry("importanceScore", 0.75f)
                .containsEntry("lifecycleState", "ACTIVE")
                .containsEntry("temporality", "PERSISTENT")
                .containsEntry("evidenceKind", "USER_EXPLICIT")
                .containsEntry("trustLevel", "EXPLICIT")
                .containsEntry("evidenceCount", 2)
                .containsEntry("createdAt", "2026-07-04T00:00:00Z")
                .containsEntry("updatedAt", "2026-07-04T00:00:00Z");
        assertThat(extra.get("description")).isEqualTo("用户希望主界面保持轻量。");
    }

    @Test
    @SuppressWarnings("unchecked")
    void DONE事件应带出工具执行摘要() {
        var handler = new StreamingEventHandler(
                new ObjectMapper(), null, null, null, null, null, null);
        var request = new AgentRequest("跑一下测试", "session-1", "web");
        var state = ReactAgentState.init(request, testBudget());
        var steps = List.<ReactStep>of(
                new ReactStep.ToolCall(
                        "shell.exec",
                        "Shell 执行",
                        "{\"command\":\"npm test\"}",
                        37,
                        "call-shell-1"),
                new ReactStep.Observation(
                        "shell.exec",
                        "Shell 执行",
                        false,
                        "{\"error\":\"测试失败\",\"workingDirectory\":\"D:\\\\WorkSpace\\\\Project\\\\News\"}",
                        0,
                        "call-shell-1")
        );

        var payload = handler.buildDoneEventPayload(
                request, state, "turn-1", null, steps, null,
                "测试失败，需要修复。", "assistant-entry-1", null, null);

        var summaries = (List<Map<String, Object>>) payload.get("toolsSummary");
        assertThat(summaries).hasSize(1);
        assertThat(summaries.getFirst())
                .containsEntry("toolId", "shell.exec")
                .containsEntry("toolName", "Shell 执行")
                .containsEntry("executionKind", "TOOL")
                .containsEntry("status", "FAILED")
                .containsEntry("success", false)
                .containsEntry("latencyMs", 37L)
                .containsEntry("inputSummary", "执行 `npm test`")
                .containsEntry("outputSummary", "测试失败")
                .containsEntry("outputDetail", "测试失败")
                .containsEntry("workingDirectory", "D:\\WorkSpace\\Project\\News")
                .containsEntry("failureCategory", "COMMAND")
                .containsEntry("recoveryHint", "命令或代码没有完成，可以修正错误后继续执行。");
        var recoveryActions = (List<Map<String, Object>>) summaries.getFirst().get("recoveryActions");
        assertThat(recoveryActions)
                .extracting(action -> action.get("label"))
                .contains("修正后继续", "重新开始");
    }

    @Test
    @SuppressWarnings("unchecked")
    void DONE事件不应把内部编排工具作为主对话工具摘要或恢复断点() {
        var handler = new StreamingEventHandler(
                new ObjectMapper(), null, null, null, null, null, null);
        var request = new AgentRequest("帮我调研资料", "session-1", "web");
        var state = ReactAgentState.init(request, testBudget())
                .toBuilder()
                .completionMode(CompletionMode.DEGRADED)
                .terminationReason("内部工具搜索失败")
                .build();
        var steps = List.<ReactStep>of(
                new ReactStep.ToolCall(
                        "tool.search",
                        "搜索工具",
                        "{\"q\":\"web.search\"}",
                        10,
                        "call-internal-1"),
                new ReactStep.Observation(
                        "tool.search",
                        "搜索工具",
                        false,
                        "{\"error\":\"工具索引暂不可用\"}",
                        0,
                        "call-internal-1"),
                new ReactStep.ToolCall(
                        "experience.match",
                        "经验匹配",
                        "{\"query\":\"帮我调研资料\"}",
                        11,
                        "call-internal-2"),
                new ReactStep.Observation(
                        "experience.match",
                        "经验匹配",
                        false,
                        "{\"error\":\"经验库后台超时\"}",
                        0,
                        "call-internal-2"),
                new ReactStep.ToolCall(
                        "context.assemble",
                        "上下文装配",
                        "{\"sessionId\":\"session-1\"}",
                        12,
                        "call-internal-3"),
                new ReactStep.Observation(
                        "context.assemble",
                        "上下文装配",
                        true,
                        "{\"summary\":\"准备对话上下文\"}",
                        0,
                        "call-internal-3"),
                new ReactStep.ToolCall(
                        "web.search",
                        "联网搜索",
                        "{\"query\":\"知微\"}",
                        22,
                        "call-web-1"),
                new ReactStep.Observation(
                        "web.search",
                        "联网搜索",
                        true,
                        "{\"summary\":\"找到 3 条资料\"}",
                        0,
                        "call-web-1")
        );

        var payload = handler.buildDoneEventPayload(
                request, state, "turn-1", null, steps, null,
                "已经整理出一部分资料。", "assistant-entry-1", null, null);

        var summaries = (List<Map<String, Object>>) payload.get("toolsSummary");
        assertThat(summaries).hasSize(1);
        assertThat(summaries.getFirst())
                .containsEntry("toolId", "web.search")
                .containsEntry("toolName", "联网搜索")
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("success", true);

        var recovery = (Map<String, Object>) payload.get("taskRecovery");
        assertThat(recovery)
                .containsEntry("title", "可以继续这一轮")
                .containsEntry("actionLabel", "继续处理")
                .doesNotContainKey("checkpoint");
        assertThat(String.valueOf(summaries) + String.valueOf(recovery))
                .doesNotContain("搜索工具")
                .doesNotContain("工具索引暂不可用")
                .doesNotContain("经验匹配")
                .doesNotContain("经验库后台超时")
                .doesNotContain("上下文装配")
                .doesNotContain("准备对话上下文");
    }

    @Test
    @SuppressWarnings("unchecked")
    void DONE事件应带出技能加载执行摘要() {
        var handler = new StreamingEventHandler(
                new ObjectMapper(), null, null, null, null, null, null);
        var request = new AgentRequest("按调研技能整理资料", "session-1", "web");
        var state = ReactAgentState.init(request, testBudget());
        var steps = List.<ReactStep>of(
                new ReactStep.ToolCall(
                        "skill.load",
                        "加载 Skill",
                        "{\"names\":[\"research-assistant\"]}",
                        12,
                        "call-skill-1"),
                new ReactStep.Observation(
                        "skill.load",
                        "加载 Skill",
                        true,
                        "{\"content\":\"<skill name=\\\"research-assistant\\\">调研指南</skill>\"}",
                        0,
                        "call-skill-1")
        );

        var payload = handler.buildDoneEventPayload(
                request, state, "turn-1", null, steps, null,
                "我已经按调研策略整理好了。", "assistant-entry-1", null, null);

        var summaries = (List<Map<String, Object>>) payload.get("toolsSummary");
        assertThat(summaries).hasSize(1);
        assertThat(summaries.getFirst())
                .containsEntry("toolId", "skill.load")
                .containsEntry("toolName", "加载 Skill")
                .containsEntry("executionKind", "SKILL")
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("action", "加载技能")
                .containsEntry("subjectLabel", "技能")
                .containsEntry("success", true)
                .containsEntry("inputSummary", "加载技能「research-assistant」")
                .containsEntry("outputSummary", "已加载 1 个技能");
        assertThat((List<String>) summaries.getFirst().get("subjectNames"))
                .containsExactly("research-assistant");
    }

    @Test
    @SuppressWarnings("unchecked")
    void DONE事件应为技能失败带出技能化恢复摘要() {
        var handler = new StreamingEventHandler(
                new ObjectMapper(), null, null, null, null, null, null);
        var request = new AgentRequest("按调研技能整理资料", "session-1", "web");
        var state = ReactAgentState.init(request, testBudget())
                .toBuilder()
                .completionMode(CompletionMode.DEGRADED)
                .terminationReason("技能加载失败")
                .build();
        var steps = List.<ReactStep>of(
                new ReactStep.ToolCall(
                        "skill.load",
                        "加载 Skill",
                        "{\"names\":[\"research-assistant\"]}",
                        12,
                        "call-skill-1"),
                new ReactStep.Observation(
                        "skill.load",
                        "加载 Skill",
                        false,
                        "{\"error\":\"技能 research-assistant 不存在\"}",
                        0,
                        "call-skill-1")
        );

        var payload = handler.buildDoneEventPayload(
                request, state, "turn-1", null, steps, null,
                "技能加载失败。", "assistant-entry-1", null, null);

        var summaries = (List<Map<String, Object>>) payload.get("toolsSummary");
        assertThat(summaries).hasSize(1);
        assertThat(summaries.getFirst())
                .containsEntry("toolId", "skill.load")
                .containsEntry("executionKind", "SKILL")
                .containsEntry("status", "FAILED")
                .containsEntry("failureCategory", "SKILL")
                .containsEntry("recoveryHint", "技能加载没有完成，可以检查技能名称或依赖后继续。");
        assertThat((List<Map<String, Object>>) summaries.getFirst().get("recoveryActions"))
                .extracting(action -> action.get("label"))
                .containsExactly("检查技能后继续", "重新开始");
        assertThat((List<Map<String, Object>>) summaries.getFirst().get("recoveryActions"))
                .first()
                .satisfies(action -> {
                    assertThat(action)
                            .containsEntry("toolId", "skill.load")
                            .containsEntry("toolName", "加载 Skill")
                            .containsEntry("executionKind", "SKILL")
                            .containsEntry("action", "加载技能")
                            .containsEntry("category", "SKILL")
                            .containsEntry("subjectLabel", "技能")
                            .containsEntry("inputSummary", "加载技能「research-assistant」")
                            .containsEntry("outputSummary", "技能 research-assistant 不存在");
                    assertThat((List<String>) action.get("subjectNames"))
                            .containsExactly("research-assistant");
                });

        var recovery = (Map<String, Object>) payload.get("taskRecovery");
        assertThat(recovery)
                .containsEntry("status", "DEGRADED")
                .containsEntry("title", "技能 research-assistant 没有完成")
                .containsEntry("detail", "技能加载没有完成，可以检查技能名称或依赖后继续。");
        var checkpoint = (Map<String, Object>) recovery.get("checkpoint");
        assertThat(checkpoint)
                .containsEntry("executionKind", "SKILL")
                .containsEntry("failureCategory", "SKILL")
                .containsEntry("subjectLabel", "技能");
        assertThat((List<String>) checkpoint.get("subjectNames"))
                .containsExactly("research-assistant");
        assertThat((List<String>) recovery.get("nextActions"))
                .containsExactly(
                        "确认技能 research-assistant 的名称和依赖是否可用",
                        "重新加载技能后继续当前任务");
    }

    @Test
    @SuppressWarnings("unchecked")
    void DONE事件应带出降级任务恢复摘要() {
        var handler = new StreamingEventHandler(
                new ObjectMapper(), null, null, null, null, null, null);
        var request = new AgentRequest("跑一下测试", "session-1", "web");
        var state = ReactAgentState.init(request, testBudget())
                .toBuilder()
                .completionMode(CompletionMode.DEGRADED)
                .terminationReason("测试失败")
                .build();
        var steps = List.<ReactStep>of(
                new ReactStep.ToolCall(
                        "shell.exec",
                        "Shell 执行",
                        "{\"command\":\"npm test\"}",
                        37,
                        "call-shell-1"),
                new ReactStep.Observation(
                        "shell.exec",
                        "Shell 执行",
                        false,
                        "{\"error\":\"测试失败\",\"workingDirectory\":\"D:\\\\WorkSpace\\\\Project\\\\News\"}",
                        0,
                        "call-shell-1")
        );

        var payload = handler.buildDoneEventPayload(
                request, state, "turn-1", null, steps, null,
                "测试失败，需要修复。", "assistant-entry-1", null, null);

        var toolSummaries = (List<Map<String, Object>>) payload.get("toolsSummary");
        var recovery = (Map<String, Object>) payload.get("taskRecovery");
        assertThat(recovery)
                .containsEntry("status", "DEGRADED")
                .containsEntry("title", "Shell 执行 没有完成")
                .containsEntry("detail", "命令或代码没有完成，可以修正错误后继续执行。")
                .containsEntry("resumeMode", "manual")
                .containsEntry("actionLabel", "修正后继续")
                .containsEntry("canResume", true)
                .containsEntry("canRestart", true);
        var checkpoint = (Map<String, Object>) recovery.get("checkpoint");
        assertThat(checkpoint)
                .containsEntry("kind", "TOOL_FAILURE")
                .containsEntry("toolId", "shell.exec")
                .containsEntry("toolName", "Shell 执行")
                .containsEntry("action", "执行命令")
                .containsEntry("inputSummary", "执行 `npm test`")
                .containsEntry("outputSummary", "测试失败")
                .containsEntry("outputDetail", "测试失败")
                .containsEntry("workingDirectory", "D:\\WorkSpace\\Project\\News")
                .containsEntry("failureCategory", "COMMAND")
                .containsEntry("inputStepIndex", 0)
                .containsEntry("outputStepIndex", 1);
        assertThat((List<String>) recovery.get("nextActions"))
                .contains("查看命令输出并修正报错原因", "从失败命令后继续执行验证");
        var actions = (List<Map<String, Object>>) toolSummaries.getFirst().get("recoveryActions");
        assertThat(actions.getFirst())
                .containsEntry("label", "修正后继续")
                .containsEntry("action", "执行命令");
    }

    @Test
    @SuppressWarnings("unchecked")
    void DONE事件恢复摘要应选择最后一个失败步骤() {
        var handler = new StreamingEventHandler(
                new ObjectMapper(), null, null, null, null, null, null);
        var request = new AgentRequest("先跑测试再写报告", "session-1", "web");
        var state = ReactAgentState.init(request, testBudget())
                .toBuilder()
                .completionMode(CompletionMode.DEGRADED)
                .terminationReason("报告写入失败")
                .build();
        var steps = List.<ReactStep>of(
                new ReactStep.ToolCall(
                        "shell.exec",
                        "Shell 执行",
                        "{\"command\":\"npm test\"}",
                        18,
                        "call-shell-1"),
                new ReactStep.Observation(
                        "shell.exec",
                        "Shell 执行",
                        false,
                        "{\"error\":\"测试失败\"}",
                        0,
                        "call-shell-1"),
                new ReactStep.ToolCall(
                        "file.write",
                        "写入文件",
                        "{\"path\":\"D:\\\\WorkSpace\\\\Project\\\\News\\\\report.md\"}",
                        9,
                        "call-file-1"),
                new ReactStep.Observation(
                        "file.write",
                        "写入文件",
                        false,
                        "{\"error\":\"没有写入权限\"}",
                        0,
                        "call-file-1")
        );

        var payload = handler.buildDoneEventPayload(
                request, state, "turn-1", null, steps, null,
                "报告写入失败，需要处理文件权限。", "assistant-entry-1", null, null);

        var recovery = (Map<String, Object>) payload.get("taskRecovery");
        assertThat(recovery)
                .containsEntry("title", "写入文件 没有完成")
                .containsEntry("actionLabel", "检查后继续");
        var checkpoint = (Map<String, Object>) recovery.get("checkpoint");
        assertThat(checkpoint)
                .containsEntry("toolId", "file.write")
                .containsEntry("toolName", "写入文件")
                .containsEntry("action", "写入文件")
                .containsEntry("failureCategory", "FILE")
                .containsEntry("inputSummary", "写入 D:\\WorkSpace\\Project\\News\\report.md")
                .containsEntry("outputSummary", "没有写入权限")
                .containsEntry("inputStepIndex", 2)
                .containsEntry("outputStepIndex", 3);
        assertThat((List<String>) recovery.get("nextActions"))
                .containsExactly("检查文件路径或权限", "保留已完成修改并从失败文件操作继续");
    }

    @Test
    @SuppressWarnings("unchecked")
    void DONE事件无失败工具时应生成计划续接恢复摘要() {
        var handler = new StreamingEventHandler(
                new ObjectMapper(), null, null, null, null, null, null);
        var request = new AgentRequest("帮我整理项目计划", "session-1", "web");
        var state = ReactAgentState.init(request, testBudget())
                .toBuilder()
                .completionMode(CompletionMode.DEGRADED)
                .terminationReason("达到最大步骤数")
                .build();

        var payload = handler.buildDoneEventPayload(
                request, state, "turn-1", null, List.of(), null,
                "已经整理出一部分计划。", "assistant-entry-1", null, null);

        var recovery = (Map<String, Object>) payload.get("taskRecovery");
        assertThat(recovery)
                .containsEntry("status", "DEGRADED")
                .containsEntry("title", "可以继续这一轮")
                .containsEntry("actionLabel", "继续处理")
                .containsEntry("detail", "达到最大步骤数；已有结果会保留，知微可以继续处理剩余部分。")
                .doesNotContainKey("checkpoint");
        assertThat((List<String>) recovery.get("nextActions"))
                .containsExactly("复用已有结果", "继续处理剩余任务");
        assertThat(String.valueOf(recovery))
                .doesNotContain("中断点")
                .doesNotContain("TOOL_FAILURE");
    }

    @Test
    @SuppressWarnings("unchecked")
    void DONE事件应带出等待用户补充的任务恢复摘要() {
        var handler = new StreamingEventHandler(
                new ObjectMapper(), null, null, null, null, null, null);
        var request = new AgentRequest("继续执行", "session-1", "web");
        var state = ReactAgentState.init(request, testBudget())
                .toBuilder()
                .completionMode(CompletionMode.SUSPENDED)
                .build()
                .suspend(new SuspendReason.ExternalDataWait("__await_user_input__", "请补充仓库地址"));

        var payload = handler.buildDoneEventPayload(
                request, state, "turn-1", null, List.of(), null,
                "我需要你补充仓库地址。", "assistant-entry-1", null, null);

        var recovery = (Map<String, Object>) payload.get("taskRecovery");
        assertThat(recovery)
                .containsEntry("status", "SUSPENDED")
                .containsEntry("title", "等待你补充信息")
                .containsEntry("resumeMode", "user_reply")
                .containsEntry("canResume", false)
                .containsEntry("canRestart", true)
                .containsEntry("reasonType", "ExternalDataWait")
                .containsEntry("reasonSourceId", "__await_user_input__");
    }

    @Test
    @SuppressWarnings("unchecked")
    void DONE事件应带出当前轮次恢复上下文() {
        var handler = new StreamingEventHandler(
                new ObjectMapper(), null, null, null, null, null, null);
        var request = new AgentRequest("继续执行", "session-1", "web");
        var turnRecoveryContext = Map.<String, Object>of(
                "action", "RESUME",
                "sourceTraceId", "trace-failed-1",
                "title", "修正后继续",
                "detail", "命令或代码没有完成，可以修正错误后继续执行。",
                "nextActions", List.of("查看命令输出并修正报错原因", "从失败命令后继续执行验证")
        );
        var state = ReactAgentState.init(request, testBudget())
                .toBuilder()
                .turnRecoveryContext(turnRecoveryContext)
                .resumedFromTraceId("trace-failed-1")
                .build();

        var payload = handler.buildDoneEventPayload(
                request, state, "turn-1", null, List.of(), null,
                "已经接着上次继续。", "assistant-entry-1", null, null);

        var recoveryContext = (Map<String, Object>) payload.get("turnRecoveryContext");
        assertThat(recoveryContext)
                .containsEntry("action", "RESUME")
                .containsEntry("sourceTraceId", "trace-failed-1")
                .containsEntry("title", "修正后继续")
                .containsEntry("detail", "命令或代码没有完成，可以修正错误后继续执行。");
        assertThat((List<String>) recoveryContext.get("nextActions"))
                .containsExactly("查看命令输出并修正报错原因", "从失败命令后继续执行验证");
    }

    @Test
    @SuppressWarnings("unchecked")
    void DONE事件应带出本轮沉淀记忆() {
        var candidateRepository = mock(MemoryExtractionCandidateRepository.class);
        var handler = new StreamingEventHandler(
                new ObjectMapper(), null, null, null, null, null, candidateRepository);
        var request = new AgentRequest("我喜欢轻量主界面", "session-1", "web");
        var state = ReactAgentState.init(request, testBudget());

        when(candidateRepository.findAppliedMemoryChangesByTurnId("turn-1", 5))
                .thenReturn(List.of(new MemoryExtractionCandidateRepository.AppliedMemoryChange(
                        "candidate-1",
                        null,
                        "ADD",
                        "主界面偏好",
                        "PREFERENCE",
                        "memory-1",
                        "用户偏好主界面更轻量。",
                        0.82f,
                        "PERSISTENT",
                        null,
                        "USER_EXPLICIT",
                        "EXPLICIT",
                        0.92f,
                        "用户说喜欢轻量主界面",
                        Instant.parse("2026-07-04T00:00:00Z"))));

        var payload = handler.buildDoneEventPayload(
                request, state, "turn-1", null, List.of(), null,
                "我记住了。", "assistant-entry-1", null, null);

        var memoryChanges = (List<Map<String, Object>>) payload.get("memoryChanges");
        assertThat(memoryChanges).hasSize(1);
        assertThat(memoryChanges.getFirst())
                .containsEntry("type", "memory")
                .containsEntry("id", "memory-1")
                .containsEntry("name", "主界面偏好");

        var extra = (Map<String, Object>) memoryChanges.getFirst().get("extra");
        assertThat(extra)
                .containsEntry("candidateId", "candidate-1")
                .containsEntry("operation", "ADD")
                .containsEntry("operationLabel", "新增")
                .containsEntry("entityType", "PREFERENCE")
                .containsEntry("entityTypeLabel", "偏好")
                .containsEntry("description", "用户偏好主界面更轻量。")
                .containsEntry("importanceScore", 0.82f)
                .containsEntry("temporality", "PERSISTENT")
                .containsEntry("trustLevel", "EXPLICIT")
                .containsEntry("trustScore", 0.92f)
                .containsEntry("evidenceExcerpt", "用户说喜欢轻量主界面");
    }

    @Test
    @SuppressWarnings("unchecked")
    void DONE事件应带出本轮忘记记忆() {
        var candidateRepository = mock(MemoryExtractionCandidateRepository.class);
        var handler = new StreamingEventHandler(
                new ObjectMapper(), null, null, null, null, null, candidateRepository);
        var request = new AgentRequest("以后不要再提醒我下午5点检查日志", "session-1", "web");
        var state = ReactAgentState.init(request, testBudget());

        when(candidateRepository.findAppliedMemoryChangesByTurnId("turn-1", 5))
                .thenReturn(List.of(new MemoryExtractionCandidateRepository.AppliedMemoryChange(
                        "candidate-delete-1",
                        null,
                        "DELETE",
                        "下午日志提醒偏好",
                        "PREFERENCE",
                        "memory-delete-1",
                        null,
                        0.6f,
                        null,
                        null,
                        "USER_EXPLICIT",
                        "EXPLICIT",
                        0.91f,
                        "以后不要再提醒我下午5点检查日志",
                        Instant.parse("2026-07-04T00:00:00Z"))));

        var payload = handler.buildDoneEventPayload(
                request, state, "turn-1", null, List.of(), null,
                "好的，以后不再默认参考这条提醒偏好。", "assistant-entry-1", null, null);

        var memoryChanges = (List<Map<String, Object>>) payload.get("memoryChanges");
        assertThat(memoryChanges).hasSize(1);
        assertThat(memoryChanges.getFirst())
                .containsEntry("type", "memory")
                .containsEntry("id", "memory-delete-1")
                .containsEntry("name", "下午日志提醒偏好");

        var extra = (Map<String, Object>) memoryChanges.getFirst().get("extra");
        assertThat(extra)
                .containsEntry("operation", "DELETE")
                .containsEntry("operationLabel", "忘记")
                .containsEntry("entityType", "PREFERENCE")
                .containsEntry("entityTypeLabel", "偏好")
                .containsEntry("evidenceExcerpt", "以后不要再提醒我下午5点检查日志");
    }

    private Budget testBudget() {
        return Budget.builder()
                .maxTokens(1000)
                .tokensUsed(0)
                .tokensReserved(0)
                .maxSteps(8)
                .stepsUsed(0)
                .maxDuration(Duration.ofSeconds(60))
                .elapsed(Duration.ZERO)
                .build();
    }

    private TemporalEntity memory(String id, String name) {
        var now = Instant.parse("2026-07-04T00:00:00Z");
        return new TemporalEntity(
                id,
                EntityType.PREFERENCE,
                name,
                "用户希望主界面保持轻量。",
                Map.of(),
                1,
                true,
                now,
                null,
                "session-1",
                0.92f,
                0.75f,
                3,
                now,
                now,
                now,
                LifecycleState.ACTIVE,
                null,
                null,
                Temporality.PERSISTENT,
                null,
                false,
                List.of(),
                MemoryEvidenceKind.USER_EXPLICIT,
                MemoryTrustLevel.EXPLICIT,
                0.88f,
                2,
                now
        );
    }
}
