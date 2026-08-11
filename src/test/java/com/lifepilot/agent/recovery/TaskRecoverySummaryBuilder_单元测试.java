package com.lifepilot.agent.recovery;

import com.lifepilot.agent.model.AgentTaskMode;
import com.lifepilot.agent.model.Budget;
import com.lifepilot.agent.model.CompletionMode;
import com.lifepilot.agent.model.CompletionReason;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.ReactStep;
import com.lifepilot.interaction.model.ArtifactRef;
import com.lifepilot.interaction.model.InteractionSource;
import com.lifepilot.tool.model.ArtifactKind;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TaskRecoverySummaryBuilder 单元测试。
 *
 * @author zsg
 * @since 2026-07-05
 */
class TaskRecoverySummaryBuilder_单元测试 {

    @Test
    @SuppressWarnings("unchecked")
    void 未返回观察结果的技能调用应生成可恢复中断摘要() {
        var toolSummaries = TaskRecoverySummaryBuilder.toolSummariesFromSerializedSteps(List.of(Map.ofEntries(
                Map.entry("type", "TOOL_CALL"),
                Map.entry("index", 0),
                Map.entry("toolId", "skill.load"),
                Map.entry("toolName", "加载 Skill"),
                Map.entry("callId", "call-skill-load-1"),
                Map.entry("subjectLabel", "技能"),
                Map.entry("subjectNames", List.of("research-assistant")),
                Map.entry("inputSummary", "加载 research-assistant"),
                Map.entry("latencyMs", 12L)
        )));

        assertThat(toolSummaries).hasSize(1);
        var toolSummary = toolSummaries.getFirst();
        assertThat(toolSummary)
                .containsEntry("toolId", "skill.load")
                .containsEntry("toolName", "加载 Skill")
                .containsEntry("callId", "call-skill-load-1")
                .containsEntry("executionKind", "SKILL")
                .containsEntry("status", "FAILED")
                .containsEntry("success", false)
                .containsEntry("hasMoreSteps", false)
                .containsEntry("interrupted", true)
                .containsEntry("failureCategory", "SKILL")
                .containsEntry("inputStepIndex", 0)
                .containsEntry("inputSummary", "加载 research-assistant")
                .containsEntry("outputSummary", "这一步已经开始，但没有返回执行结果。")
                .containsEntry("recoveryHint", "技能加载已经开始但没有返回结果，可以检查技能名称或依赖后继续。");
        assertThat((List<String>) toolSummary.get("subjectNames"))
                .containsExactly("research-assistant");
        var recoveryActions = (List<Map<String, Object>>) toolSummary.get("recoveryActions");
        assertThat(recoveryActions)
                .allSatisfy(action -> assertThat(action)
                        .containsEntry("callId", "call-skill-load-1")
                        .containsEntry("interrupted", true))
                .extracting(action -> action.get("label"))
                .containsExactly("检查技能后继续", "重新开始");
        assertThat(recoveryActions.get(0))
                .containsEntry("description", "保留当前进度，检查技能后从失败步骤接上。");
        assertThat(recoveryActions.get(1))
                .containsEntry("description", "保留技能失败线索，重新加载或执行失败技能步骤。");
        assertThat((List<String>) recoveryActions.get(0).get("nextActions"))
                .containsExactly(
                        "确认技能 research-assistant 的名称和依赖是否可用",
                        "重新加载技能后继续当前任务");
        assertThat((List<String>) recoveryActions.get(1).get("nextActions"))
                .containsExactly(
                        "保留技能 research-assistant 的加载失败原因",
                        "重新加载技能后重跑当前任务");

        var summary = TaskRecoverySummaryBuilder.fromState(降级状态(), toolSummaries).orElseThrow();

        assertThat(summary)
                .containsEntry("status", "DEGRADED")
                .containsEntry("actionLabel", "检查技能后继续")
                .containsEntry("title", "技能 research-assistant 没有完成")
                .containsEntry("detail", "技能加载已经开始但没有返回结果，可以检查技能名称或依赖后继续。");
        assertThat((List<String>) summary.get("nextActions"))
                .containsExactly(
                        "确认技能 research-assistant 的名称和依赖是否可用",
                        "重新加载技能后继续当前任务");

        Map<String, Object> checkpoint = (Map<String, Object>) summary.get("checkpoint");
        assertThat(checkpoint)
                .containsEntry("kind", "TOOL_FAILURE")
                .containsEntry("toolId", "skill.load")
                .containsEntry("callId", "call-skill-load-1")
                .containsEntry("executionKind", "SKILL")
                .containsEntry("failureCategory", "SKILL")
                .containsEntry("interrupted", true)
                .containsEntry("inputStepIndex", 0)
                .containsEntry("inputSummary", "加载 research-assistant")
                .containsEntry("outputSummary", "这一步已经开始，但没有返回执行结果。");
        assertThat(new ArrayList<>(checkpoint.keySet()))
                .containsExactly(
                        "kind",
                        "toolId",
                        "toolName",
                        "executionKind",
                        "action",
                        "failureCategory",
                        "callId",
                        "interrupted",
                        "subjectLabel",
                        "subjectNames",
                        "inputSummary",
                        "outputSummary",
                        "inputStepIndex");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 从步骤恢复上下文应识别未返回观察结果的技能调用() {
        var context = TaskRecoverySummaryBuilder.recoveryContextFromSteps(List.of(
                new ReactStep.ToolCall(
                        "skill.load",
                        "加载 Skill",
                        "{\"names\":[\"research-assistant\"]}",
                        12L,
                        "call-skill-load-1")
        )).orElseThrow();

        Map<String, Object> checkpoint = (Map<String, Object>) context.get("checkpoint");
        assertThat(checkpoint)
                .containsEntry("kind", "TOOL_FAILURE")
                .containsEntry("toolId", "skill.load")
                .containsEntry("toolName", "加载 Skill")
                .containsEntry("executionKind", "SKILL")
                .containsEntry("action", "加载技能")
                .containsEntry("failureCategory", "SKILL")
                .containsEntry("callId", "call-skill-load-1")
                .containsEntry("interrupted", true)
                .containsEntry("subjectLabel", "技能")
                .containsEntry("inputSummary", "加载技能「research-assistant」")
                .containsEntry("inputDetail", "{\"names\":[\"research-assistant\"]}");
        assertThat((List<String>) checkpoint.get("subjectNames"))
                .containsExactly("research-assistant");
        assertThat((List<String>) context.get("nextActions"))
                .containsExactly(
                "确认技能 research-assistant 的名称和依赖是否可用",
                        "重新加载技能后继续当前任务");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 从步骤恢复上下文应识别技能加载别名参数() {
        var context = TaskRecoverySummaryBuilder.recoveryContextFromSteps(List.of(
                new ReactStep.ToolCall(
                        "skill.load",
                        "加载 Skill",
                        "{\"skillName\":\"market-research\"}",
                        10L,
                        "call-skill-load-alias")
        )).orElseThrow();

        Map<String, Object> checkpoint = (Map<String, Object>) context.get("checkpoint");
        assertThat(checkpoint)
                .containsEntry("toolId", "skill.load")
                .containsEntry("executionKind", "SKILL")
                .containsEntry("failureCategory", "SKILL")
                .containsEntry("callId", "call-skill-load-alias")
                .containsEntry("subjectLabel", "技能")
                .containsEntry("inputSummary", "加载技能「market-research」")
                .containsEntry("inputDetail", "{\"skillName\":\"market-research\"}");
        assertThat((List<String>) checkpoint.get("subjectNames"))
                .containsExactly("market-research");
        assertThat((List<String>) context.get("nextActions"))
                .containsExactly(
                        "确认技能 market-research 的名称和依赖是否可用",
                        "重新加载技能后继续当前任务");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 从真实步骤生成技能执行失败恢复上下文时应保留技能主体() {
        var context = TaskRecoverySummaryBuilder.recoveryContextFromSteps(List.of(
                new ReactStep.ToolCall(
                        "skill.run",
                        "执行 Skill",
                        "{\"skill\":\"research-assistant\",\"step\":\"collect\"}",
                        18L,
                        "call-skill-run-1"),
                new ReactStep.Observation(
                        "skill.run",
                        "执行 Skill",
                        false,
                        "{\"message\":\"资料源不可用\",\"skill\":\"research-assistant\"}",
                        0,
                        "call-skill-run-1")
        )).orElseThrow();

        Map<String, Object> checkpoint = (Map<String, Object>) context.get("checkpoint");
        assertThat(checkpoint)
                .containsEntry("kind", "TOOL_FAILURE")
                .containsEntry("toolId", "skill.run")
                .containsEntry("toolName", "执行 Skill")
                .containsEntry("executionKind", "SKILL")
                .containsEntry("action", "执行技能")
                .containsEntry("failureCategory", "SKILL")
                .containsEntry("callId", "call-skill-run-1")
                .containsEntry("subjectLabel", "技能")
                .containsEntry("inputSummary", "执行技能「research-assistant」")
                .containsEntry("inputDetail", "{\"skill\":\"research-assistant\",\"step\":\"collect\"}")
                .containsEntry("outputSummary", "资料源不可用")
                .containsEntry("outputDetail", "资料源不可用");
        assertThat((List<String>) checkpoint.get("subjectNames"))
                .containsExactly("research-assistant");
        assertThat((List<String>) context.get("nextActions"))
                .containsExactly(
                        "检查技能 research-assistant 的输入、依赖和执行步骤",
                        "保留当前进度并从失败技能步骤继续");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 技能失败应生成带技能主体的恢复摘要() {
        var summary = TaskRecoverySummaryBuilder.fromState(
                        降级状态(),
                        List.of(Map.ofEntries(
                                Map.entry("toolId", "skill.load"),
                                Map.entry("toolName", "加载 Skill"),
                                Map.entry("executionKind", "SKILL"),
                                Map.entry("status", "FAILED"),
                                Map.entry("success", false),
                                Map.entry("failureCategory", "SKILL"),
                                Map.entry("subjectLabel", "技能"),
                                Map.entry("subjectNames", List.of("research-assistant")),
                                Map.entry("inputSummary", "加载 research-assistant"),
                                Map.entry("outputSummary", "技能文件不存在"),
                                Map.entry("recoveryHint", "技能加载没有完成，可以检查技能名称或依赖后继续。")
                        )))
                .orElseThrow();

        assertThat(summary)
                .containsEntry("status", "DEGRADED")
                .containsEntry("resumeMode", "manual")
                .containsEntry("actionLabel", "检查技能后继续")
                .containsEntry("title", "技能 research-assistant 没有完成")
                .containsEntry("detail", "技能加载没有完成，可以检查技能名称或依赖后继续。");

        assertThat((List<String>) summary.get("nextActions"))
                .containsExactly(
                        "确认技能 research-assistant 的名称和依赖是否可用",
                        "重新加载技能后继续当前任务");

        Map<String, Object> checkpoint = (Map<String, Object>) summary.get("checkpoint");
        assertThat(checkpoint)
                .containsEntry("kind", "TOOL_FAILURE")
                .containsEntry("toolId", "skill.load")
                .containsEntry("toolName", "加载 Skill")
                .containsEntry("executionKind", "SKILL")
                .containsEntry("failureCategory", "SKILL")
                .containsEntry("subjectLabel", "技能")
                .containsEntry("inputSummary", "加载 research-assistant")
                .containsEntry("outputSummary", "技能文件不存在");
        assertThat((List<String>) checkpoint.get("subjectNames"))
                .containsExactly("research-assistant");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 技能执行失败应提示从失败技能步骤继续() {
        var summary = TaskRecoverySummaryBuilder.fromState(
                        降级状态(),
                        List.of(Map.ofEntries(
                                Map.entry("toolId", "skill.run"),
                                Map.entry("toolName", "执行 Skill"),
                                Map.entry("executionKind", "SKILL"),
                                Map.entry("action", "执行技能"),
                                Map.entry("status", "FAILED"),
                                Map.entry("success", false),
                                Map.entry("failureCategory", "SKILL"),
                                Map.entry("subjectLabel", "技能"),
                                Map.entry("subjectNames", List.of("research-assistant")),
                                Map.entry("inputSummary", "执行 research-assistant 的调研步骤"),
                                Map.entry("outputSummary", "资料源不可用"),
                                Map.entry("recoveryHint", "技能执行没有完成，可以检查输入、依赖或技能步骤后继续。")
                        )))
                .orElseThrow();

        assertThat(summary)
                .containsEntry("title", "技能 research-assistant 没有完成")
                .containsEntry("detail", "技能执行没有完成，可以检查输入、依赖或技能步骤后继续。");
        assertThat((List<String>) summary.get("nextActions"))
                .containsExactly(
                        "检查技能 research-assistant 的输入、依赖和执行步骤",
                        "保留当前进度并从失败技能步骤继续");

        Map<String, Object> checkpoint = (Map<String, Object>) summary.get("checkpoint");
        assertThat(checkpoint)
                .containsEntry("toolId", "skill.run")
                .containsEntry("action", "执行技能")
                .containsEntry("executionKind", "SKILL")
                .containsEntry("failureCategory", "SKILL");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 资料类工具失败应生成明确恢复语义() {
        var toolSummaries = TaskRecoverySummaryBuilder.toolSummariesFromSerializedSteps(List.of(
                Map.ofEntries(
                        Map.entry("type", "TOOL_CALL"),
                        Map.entry("index", 0),
                        Map.entry("toolId", "knowledge.search"),
                        Map.entry("toolName", "知识库检索"),
                        Map.entry("inputSummary", "检索本地资料"),
                        Map.entry("latencyMs", 18L),
                        Map.entry("callId", "call-knowledge-1")),
                Map.ofEntries(
                        Map.entry("type", "OBSERVATION"),
                        Map.entry("index", 1),
                        Map.entry("toolId", "knowledge.search"),
                        Map.entry("toolName", "知识库检索"),
                        Map.entry("success", false),
                        Map.entry("outputSummary", "索引暂不可用"),
                        Map.entry("outputDetail", "collection not ready"),
                        Map.entry("callId", "call-knowledge-1"))
        ));

        var toolSummary = toolSummaries.getFirst();
        assertThat(toolSummary)
                .containsEntry("toolId", "knowledge.search")
                .containsEntry("executionKind", "TOOL")
                .containsEntry("action", "处理资料")
                .containsEntry("status", "FAILED")
                .containsEntry("failureCategory", "KNOWLEDGE")
                .containsEntry("recoveryHint", "资料处理没有完成，可以检查资料来源、索引或解析结果后继续。");
        var recoveryActions = (List<Map<String, Object>>) toolSummary.get("recoveryActions");
        assertThat(recoveryActions)
                .extracting(action -> action.get("label"))
                .containsExactly("调整资料后继续", "重新开始");
        assertThat(recoveryActions.get(0))
                .containsEntry("description", "保留已处理资料，调整来源、索引或解析后继续。");
        assertThat((List<String>) recoveryActions.get(0).get("nextActions"))
                .containsExactly("检查资料来源、索引或解析结果", "保留已完成资料处理并从失败处继续");
        assertThat((List<String>) recoveryActions.get(1).get("nextActions"))
                .containsExactly("保留已处理资料线索", "重新检索或重建资料处理步骤");

        var summary = TaskRecoverySummaryBuilder.fromState(降级状态(), toolSummaries).orElseThrow();

        assertThat(summary)
                .containsEntry("actionLabel", "调整资料后继续")
                .containsEntry("title", "知识库检索 没有完成")
                .containsEntry("detail", "资料处理没有完成，可以检查资料来源、索引或解析结果后继续。");
        assertThat((List<String>) summary.get("nextActions"))
                .containsExactly("检查资料来源、索引或解析结果", "保留已完成资料处理并从失败处继续");

        Map<String, Object> checkpoint = (Map<String, Object>) summary.get("checkpoint");
        assertThat(checkpoint)
                .containsEntry("toolId", "knowledge.search")
                .containsEntry("toolName", "知识库检索")
                .containsEntry("action", "处理资料")
                .containsEntry("failureCategory", "KNOWLEDGE")
                .containsEntry("outputSummary", "索引暂不可用");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 失败步骤已有产物引用时恢复摘要应保留可复用产物() {
        Map<String, Object> artifactRef = new LinkedHashMap<>();
        artifactRef.put("artifactId", "artifact-report");
        artifactRef.put("fileName", "测试报告.md");
        artifactRef.put("mimeType", "text/markdown");
        artifactRef.put("kind", "FILE");
        artifactRef.put("size", 128L);
        artifactRef.put("downloadUrl", "/api/artifacts/artifact-report/download");
        List<Map<String, Object>> artifactRefs = List.of(artifactRef);
        var toolSummaries = TaskRecoverySummaryBuilder.toolSummariesFromSerializedSteps(List.of(
                Map.ofEntries(
                        Map.entry("type", "TOOL_CALL"),
                        Map.entry("index", 0),
                        Map.entry("toolId", "shell.exec"),
                        Map.entry("toolName", "Shell 执行"),
                        Map.entry("inputSummary", "执行 `npm test`"),
                        Map.entry("latencyMs", 24L),
                        Map.entry("callId", "call-shell-1")),
                Map.ofEntries(
                        Map.entry("type", "OBSERVATION"),
                        Map.entry("index", 1),
                        Map.entry("toolId", "shell.exec"),
                        Map.entry("toolName", "Shell 执行"),
                        Map.entry("success", false),
                        Map.entry("outputSummary", "测试失败"),
                        Map.entry("outputDetail", "AssertionError"),
                        Map.entry("artifactRefs", artifactRefs),
                        Map.entry("callId", "call-shell-1"))
        ));

        var toolSummary = toolSummaries.getFirst();
        assertThat((List<Map<String, Object>>) toolSummary.get("artifactRefs"))
                .containsExactlyElementsOf(artifactRefs);
        var recoveryActions = (List<Map<String, Object>>) toolSummary.get("recoveryActions");
        assertThat((List<Map<String, Object>>) recoveryActions.getFirst().get("artifactRefs"))
                .containsExactlyElementsOf(artifactRefs);

        var summary = TaskRecoverySummaryBuilder.fromState(降级状态(), toolSummaries).orElseThrow();

        Map<String, Object> checkpoint = (Map<String, Object>) summary.get("checkpoint");
        assertThat((List<Map<String, Object>>) checkpoint.get("artifactRefs"))
                .containsExactlyElementsOf(artifactRefs);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 本轮上下文产物引用应合入失败工具恢复断点() {
        Map<String, Object> artifactRef = new LinkedHashMap<>();
        artifactRef.put("artifactId", "artifact-turn-report");
        artifactRef.put("fileName", "调研结果.md");
        artifactRef.put("mimeType", "text/markdown");
        artifactRef.put("kind", "FILE");
        artifactRef.put("size", 256L);
        artifactRef.put("downloadUrl", "/api/artifacts/artifact-turn-report/download");
        List<Map<String, Object>> artifactRefs = List.of(artifactRef);
        var toolSummaries = TaskRecoverySummaryBuilder.toolSummariesFromSerializedSteps(List.of(
                Map.ofEntries(
                        Map.entry("type", "TOOL_CALL"),
                        Map.entry("index", 0),
                        Map.entry("toolId", "shell.exec"),
                        Map.entry("toolName", "Shell 执行"),
                        Map.entry("inputSummary", "执行 `npm test`"),
                        Map.entry("latencyMs", 24L),
                        Map.entry("callId", "call-shell-1")),
                Map.ofEntries(
                        Map.entry("type", "OBSERVATION"),
                        Map.entry("index", 1),
                        Map.entry("toolId", "shell.exec"),
                        Map.entry("toolName", "Shell 执行"),
                        Map.entry("success", false),
                        Map.entry("outputSummary", "测试失败"),
                        Map.entry("outputDetail", "AssertionError"),
                        Map.entry("callId", "call-shell-1"))
        ));

        var enrichedSummaries = TaskRecoverySummaryBuilder.attachTurnArtifactRefs(
                toolSummaries,
                artifactRefs);

        var toolSummary = enrichedSummaries.getFirst();
        assertThat((List<Map<String, Object>>) toolSummary.get("artifactRefs"))
                .containsExactlyElementsOf(artifactRefs);
        var recoveryActions = (List<Map<String, Object>>) toolSummary.get("recoveryActions");
        assertThat((List<Map<String, Object>>) recoveryActions.getFirst().get("artifactRefs"))
                .containsExactlyElementsOf(artifactRefs);

        var summary = TaskRecoverySummaryBuilder.fromState(
                降级状态(),
                enrichedSummaries,
                artifactRefs).orElseThrow();

        Map<String, Object> checkpoint = (Map<String, Object>) summary.get("checkpoint");
        assertThat((List<Map<String, Object>>) checkpoint.get("artifactRefs"))
                .containsExactlyElementsOf(artifactRefs);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 合并重复产物引用时应补齐别名字段里的文件信息() {
        var toolSummaries = TaskRecoverySummaryBuilder.toolSummariesFromSerializedSteps(List.of(
                Map.ofEntries(
                        Map.entry("type", "TOOL_CALL"),
                        Map.entry("index", 0),
                        Map.entry("toolId", "file.write"),
                        Map.entry("toolName", "文件写入"),
                        Map.entry("inputSummary", "写入调研结果"),
                        Map.entry("latencyMs", 24L),
                        Map.entry("callId", "call-file-1")),
                Map.ofEntries(
                        Map.entry("type", "OBSERVATION"),
                        Map.entry("index", 1),
                        Map.entry("toolId", "file.write"),
                        Map.entry("toolName", "文件写入"),
                        Map.entry("success", false),
                        Map.entry("outputSummary", "写入失败"),
                        Map.entry("artifactRefs", List.of(Map.of("artifactId", "artifact-report"))),
                        Map.entry("callId", "call-file-1"))
        ));
        List<Map<String, Object>> turnArtifactRefs = List.of(
                Map.ofEntries(
                        Map.entry("artifact_id", " artifact-report "),
                        Map.entry("file_name", "调研结果.md"),
                        Map.entry("mime_type", "text/markdown"),
                        Map.entry("size", "128"),
                        Map.entry("download_url", "/api/artifacts/artifact-report/download?version=full")),
                Map.of(
                        "artifact_id", "artifact-screen",
                        "name", "截图.png",
                        "media_type", "image/png"));

        var enrichedSummaries = TaskRecoverySummaryBuilder.attachTurnArtifactRefs(
                toolSummaries,
                turnArtifactRefs);

        var toolSummary = enrichedSummaries.getFirst();
        List<Map<String, Object>> mergedRefs = (List<Map<String, Object>>) toolSummary.get("artifactRefs");
        assertThat(mergedRefs).hasSize(2);
        assertThat(mergedRefs.get(0))
                .containsEntry("artifactId", "artifact-report")
                .containsEntry("fileName", "调研结果.md")
                .containsEntry("mimeType", "text/markdown")
                .containsEntry("kind", "FILE")
                .containsEntry("size", 128L)
                .containsEntry("downloadUrl", "/api/artifacts/artifact-report/download?version=full");
        assertThat(mergedRefs.get(1))
                .containsEntry("artifactId", "artifact-screen")
                .containsEntry("fileName", "截图.png")
                .containsEntry("mimeType", "image/png")
                .containsEntry("kind", "IMAGE")
                .containsEntry("downloadUrl", "/api/artifacts/artifact-screen/download");

        var recoveryActions = (List<Map<String, Object>>) toolSummary.get("recoveryActions");
        assertThat((List<Map<String, Object>>) recoveryActions.getFirst().get("artifactRefs"))
                .containsExactlyElementsOf(mergedRefs);

        var summary = TaskRecoverySummaryBuilder.fromState(
                降级状态(),
                enrichedSummaries,
                turnArtifactRefs).orElseThrow();
        Map<String, Object> checkpoint = (Map<String, Object>) summary.get("checkpoint");
        assertThat((List<Map<String, Object>>) checkpoint.get("artifactRefs"))
                .containsExactlyElementsOf(mergedRefs);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 从步骤恢复上下文时应保留本轮旁路产物引用() {
        var artifactRef = new ArtifactRef(
                "artifact-recovery-report",
                "恢复报告.md",
                "text/markdown",
                ArtifactKind.FILE,
                512L);

        var context = TaskRecoverySummaryBuilder.recoveryContextFromSteps(
                List.of(
                        new ReactStep.ToolCall(
                                "shell.exec",
                                "Shell 执行",
                                "{\"command\":\"npm test\"}",
                                24L,
                                "call-shell-artifact"),
                        new ReactStep.Observation(
                                "shell.exec",
                                "Shell 执行",
                                false,
                                "{\"error\":\"测试失败\"}",
                                0,
                                "call-shell-artifact")),
                List.of(artifactRef)).orElseThrow();

        Map<String, Object> checkpoint = (Map<String, Object>) context.get("checkpoint");
        assertThat((List<Map<String, Object>>) checkpoint.get("artifactRefs"))
                .containsExactly(Map.of(
                        "artifactId", "artifact-recovery-report",
                        "fileName", "恢复报告.md",
                        "mimeType", "text/markdown",
                        "kind", "FILE",
                        "size", 512L,
                        "downloadUrl", "/api/artifacts/artifact-recovery-report/download"));
        assertThat(context)
                .containsEntry("resumeStrategy", "从失败断点继续，保留已完成步骤和失败输出，不要重复成功部分。");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 浏览器工具失败应生成页面操作恢复语义() {
        var toolSummaries = TaskRecoverySummaryBuilder.toolSummariesFromSerializedSteps(List.of(
                Map.ofEntries(
                        Map.entry("type", "TOOL_CALL"),
                        Map.entry("index", 0),
                        Map.entry("toolId", "browser.click"),
                        Map.entry("toolName", "浏览器点击"),
                        Map.entry("inputSummary", "点击 #submit"),
                        Map.entry("latencyMs", 21L),
                        Map.entry("callId", "call-browser-1")),
                Map.ofEntries(
                        Map.entry("type", "OBSERVATION"),
                        Map.entry("index", 1),
                        Map.entry("toolId", "browser.click"),
                        Map.entry("toolName", "浏览器点击"),
                        Map.entry("success", false),
                        Map.entry("outputSummary", "选择器未匹配到任何元素"),
                        Map.entry("outputDetail", "Timeout waiting for selector #submit"),
                        Map.entry("callId", "call-browser-1"))
        ));

        var toolSummary = toolSummaries.getFirst();
        assertThat(toolSummary)
                .containsEntry("toolId", "browser.click")
                .containsEntry("action", "操作浏览器")
                .containsEntry("status", "FAILED")
                .containsEntry("failureCategory", "BROWSER")
                .containsEntry("recoveryHint", "浏览器操作没有完成，可以检查页面状态、登录或元素选择后继续。");
        var recoveryActions = (List<Map<String, Object>>) toolSummary.get("recoveryActions");
        assertThat(recoveryActions)
                .extracting(action -> action.get("label"))
                .containsExactly("检查页面后继续", "重新开始");
        assertThat(recoveryActions.getFirst())
                .containsEntry("description", "保留当前浏览器上下文，检查页面状态、登录或元素选择后继续。");
        assertThat((List<String>) recoveryActions.getFirst().get("nextActions"))
                .containsExactly("检查浏览器页面状态、登录或元素选择", "保留当前上下文并从失败页面操作继续");

        var summary = TaskRecoverySummaryBuilder.fromState(降级状态(), toolSummaries).orElseThrow();

        assertThat(summary)
                .containsEntry("actionLabel", "检查页面后继续")
                .containsEntry("title", "浏览器点击 没有完成")
                .containsEntry("detail", "浏览器操作没有完成，可以检查页面状态、登录或元素选择后继续。");
        assertThat((List<String>) summary.get("nextActions"))
                .containsExactly("检查浏览器页面状态、登录或元素选择", "保留当前上下文并从失败页面操作继续");

        Map<String, Object> checkpoint = (Map<String, Object>) summary.get("checkpoint");
        assertThat(checkpoint)
                .containsEntry("toolId", "browser.click")
                .containsEntry("toolName", "浏览器点击")
                .containsEntry("action", "操作浏览器")
                .containsEntry("failureCategory", "BROWSER")
                .containsEntry("outputSummary", "选择器未匹配到任何元素");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 未注册工具失败应生成能力缺口恢复语义() {
        var toolSummaries = TaskRecoverySummaryBuilder.toolSummariesFromSerializedSteps(List.of(
                Map.ofEntries(
                        Map.entry("type", "TOOL_CALL"),
                        Map.entry("index", 0),
                        Map.entry("toolId", "web.search"),
                        Map.entry("toolName", "联网搜索"),
                        Map.entry("inputSummary", "搜索资料"),
                        Map.entry("latencyMs", 15L),
                        Map.entry("callId", "call-missing-tool-1")),
                Map.ofEntries(
                        Map.entry("type", "OBSERVATION"),
                        Map.entry("index", 1),
                        Map.entry("toolId", "web.search"),
                        Map.entry("toolName", "联网搜索"),
                        Map.entry("success", false),
                        Map.entry("outputSummary", "工具未注册: web.search"),
                        Map.entry("outputDetail", "工具未注册: web.search"),
                        Map.entry("callId", "call-missing-tool-1"))
        ));

        var toolSummary = toolSummaries.getFirst();
        assertThat(toolSummary)
                .containsEntry("toolId", "web.search")
                .containsEntry("status", "FAILED")
                .containsEntry("failureCategory", "CAPABILITY")
                .containsEntry("recoveryHint", "依赖的工具或技能当前不可用，可以在能力中心修复连接或调整 Skill 元数据后继续。");
        assertThat((List<Map<String, Object>>) toolSummary.get("missingCapabilities"))
                .containsExactly(Map.of(
                        "kind", "TOOL",
                        "id", "web.search",
                        "source", "runtime_tool_call",
                        "reason", "工具未注册或当前不可用"));
        var recoveryActions = (List<Map<String, Object>>) toolSummary.get("recoveryActions");
        assertThat(recoveryActions)
                .extracting(action -> action.get("label"))
                .containsExactly("修复能力后继续", "重新开始");
        assertThat((List<String>) recoveryActions.getFirst().get("nextActions"))
                .containsExactly("补齐缺失能力：web.search", "到能力中心检查 Skill suggestedTools、MCP 工具提供方或本地工具连接");

        var summary = TaskRecoverySummaryBuilder.fromState(降级状态(), toolSummaries).orElseThrow();

        assertThat(summary)
                .containsEntry("actionLabel", "修复能力后继续")
                .containsEntry("title", "联网搜索 没有完成")
                .containsEntry("detail", "依赖的工具或技能当前不可用，可以在能力中心修复连接或调整 Skill 元数据后继续。");
        assertThat((List<String>) summary.get("nextActions"))
                .containsExactly("补齐缺失能力：web.search", "到能力中心检查 Skill suggestedTools、MCP 工具提供方或本地工具连接");

        Map<String, Object> checkpoint = (Map<String, Object>) summary.get("checkpoint");
        assertThat(checkpoint)
                .containsEntry("toolId", "web.search")
                .containsEntry("toolName", "联网搜索")
                .containsEntry("failureCategory", "CAPABILITY")
                .containsEntry("outputSummary", "工具未注册: web.search");
        assertThat((List<Map<String, Object>>) checkpoint.get("missingCapabilities"))
                .containsExactly(Map.of(
                        "kind", "TOOL",
                        "id", "web.search",
                        "source", "runtime_tool_call",
                        "reason", "工具未注册或当前不可用"));
        assertThat(TaskRecoverySummaryBuilder.resumeStrategy("RESUME", checkpoint))
                .isEqualTo("先修复缺失工具或技能连接，再从失败断点继续，保留已完成步骤。");

        assertThat(ToolExecutionSummarySupport.failureCategory(
                "web.search", "工具未注册: web.search", null, null))
                .isEqualTo("CAPABILITY");
        assertThat(ToolExecutionSummarySupport.failureCategory(
                "web.search", "工具 web.search 未注册", null, null))
                .isEqualTo("CAPABILITY");
        assertThat(ToolExecutionSummarySupport.missingCapabilities(
                "skill.load", "unknown suggested tool: web.search", null, null))
                .containsExactly(Map.of(
                        "kind", "TOOL",
                        "id", "web.search",
                        "source", "skill_reference",
                        "reason", "Skill 引用了当前不可用工具"));
        assertThat(ToolExecutionSummarySupport.resumeActionLabel("CAPABILITY"))
                .isEqualTo("修复能力后继续");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 内部编排工具失败不应生成用户可见工具摘要和恢复断点() {
        var toolSummaries = TaskRecoverySummaryBuilder.toolSummariesFromSerializedSteps(List.of(
                Map.ofEntries(
                        Map.entry("type", "TOOL_CALL"),
                        Map.entry("index", 0),
                        Map.entry("toolId", "tool.search"),
                        Map.entry("toolName", "搜索工具"),
                        Map.entry("inputSummary", "查找可用工具"),
                        Map.entry("latencyMs", 18L),
                        Map.entry("callId", "call-internal-1")),
                Map.ofEntries(
                        Map.entry("type", "OBSERVATION"),
                        Map.entry("index", 1),
                        Map.entry("toolId", "tool.search"),
                        Map.entry("toolName", "搜索工具"),
                        Map.entry("success", false),
                        Map.entry("outputSummary", "工具索引暂不可用"),
                        Map.entry("callId", "call-internal-1")),
                Map.ofEntries(
                        Map.entry("type", "TOOL_CALL"),
                        Map.entry("index", 2),
                        Map.entry("toolId", "capability.assess"),
                        Map.entry("toolName", "能力核查"),
                        Map.entry("inputSummary", "核查能力路径"),
                        Map.entry("latencyMs", 9L),
                        Map.entry("callId", "call-internal-2"))
        ));

        assertThat(toolSummaries).isEmpty();

        var summary = TaskRecoverySummaryBuilder.fromState(降级状态(), toolSummaries).orElseThrow();

        assertThat(summary)
                .containsEntry("status", "DEGRADED")
                .containsEntry("title", "可以继续这一轮")
                .containsEntry("actionLabel", "继续处理")
                .doesNotContainKey("checkpoint");
        assertThat((List<String>) summary.get("nextActions"))
                .containsExactly("复用已有结果", "继续处理剩余任务");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 内部失败晚于真实失败时恢复摘要仍应选择真实失败步骤() {
        var visibleFailure = new LinkedHashMap<String, Object>();
        visibleFailure.put("toolId", "shell.exec");
        visibleFailure.put("toolName", "Shell 执行");
        visibleFailure.put("executionKind", "TOOL");
        visibleFailure.put("status", "FAILED");
        visibleFailure.put("success", false);
        visibleFailure.put("failureCategory", "COMMAND");
        visibleFailure.put("inputSummary", "执行 `npm test`");
        visibleFailure.put("outputSummary", "测试失败");
        visibleFailure.put("recoveryHint", "命令或代码没有完成，可以修正错误后继续执行。");
        var internalFailure = new LinkedHashMap<String, Object>();
        internalFailure.put("toolId", "tool.search");
        internalFailure.put("toolName", "搜索工具");
        internalFailure.put("status", "FAILED");
        internalFailure.put("success", false);
        internalFailure.put("outputSummary", "内部工具搜索失败");

        var summary = TaskRecoverySummaryBuilder.fromState(
                降级状态(),
                List.of(visibleFailure, internalFailure)).orElseThrow();

        assertThat(summary)
                .containsEntry("title", "Shell 执行 没有完成")
                .containsEntry("actionLabel", "修正后继续");
        Map<String, Object> checkpoint = (Map<String, Object>) summary.get("checkpoint");
        assertThat(checkpoint)
                .containsEntry("toolId", "shell.exec")
                .containsEntry("toolName", "Shell 执行")
                .containsEntry("failureCategory", "COMMAND")
                .containsEntry("outputSummary", "测试失败");
        assertThat(checkpoint)
                .doesNotContainEntry("toolId", "tool.search");
    }

    @Test
    void 产品级能力工具应有稳定分类和恢复动作() {
        assertThat(ToolExecutionSummarySupport.executionAction("workflow.run")).isEqualTo("执行工作流");
        assertThat(ToolExecutionSummarySupport.failureCategory("workflow.run")).isEqualTo("WORKFLOW");
        assertThat(ToolExecutionSummarySupport.resumeActionLabel("WORKFLOW")).isEqualTo("检查流程后继续");

        assertThat(ToolExecutionSummarySupport.executionAction("mcp.call")).isEqualTo("调用连接器");
        assertThat(ToolExecutionSummarySupport.failureCategory("mcp.call")).isEqualTo("INTEGRATION");
        assertThat(ToolExecutionSummarySupport.resumeActionLabel("INTEGRATION")).isEqualTo("检查连接后继续");

        assertThat(ToolExecutionSummarySupport.executionAction("agent.delegate")).isEqualTo("协作智能体");
        assertThat(ToolExecutionSummarySupport.failureCategory("agent.delegate")).isEqualTo("AGENT");
        assertThat(ToolExecutionSummarySupport.resumeActionLabel("AGENT")).isEqualTo("检查协作后继续");

        assertThat(ToolExecutionSummarySupport.executionAction("llm.generate")).isEqualTo("整理回答");
        assertThat(ToolExecutionSummarySupport.failureCategory("llm.generate")).isEqualTo("MODEL");
        assertThat(ToolExecutionSummarySupport.recoveryHint("llm.generate"))
                .isEqualTo("模型处理没有完成，可以检查模型配置、输入或重试策略后继续。");
        assertThat(ToolExecutionSummarySupport.resumeActionLabel("MODEL")).isEqualTo("检查模型后继续");

        assertThat(ToolExecutionSummarySupport.executionAction("git.clone")).isEqualTo("操作仓库");
        assertThat(ToolExecutionSummarySupport.failureCategory("git.clone")).isEqualTo("REPOSITORY");
        assertThat(ToolExecutionSummarySupport.resumeActionLabel("REPOSITORY")).isEqualTo("检查仓库后继续");

        assertThat(ToolExecutionSummarySupport.executionAction("browser.click")).isEqualTo("操作浏览器");
        assertThat(ToolExecutionSummarySupport.failureCategory("browser.click")).isEqualTo("BROWSER");
        assertThat(ToolExecutionSummarySupport.resumeActionLabel("BROWSER")).isEqualTo("检查页面后继续");

        assertThat(ToolExecutionSummarySupport.isVisibleExecution("tool.search", "搜索工具")).isFalse();
        assertThat(ToolExecutionSummarySupport.isVisibleExecution("capability.assess", "能力核查")).isFalse();
        assertThat(ToolExecutionSummarySupport.isVisibleExecution("intent.match", "意图识别")).isFalse();
        assertThat(ToolExecutionSummarySupport.isVisibleExecution("experience.match", "经验匹配")).isFalse();
        assertThat(ToolExecutionSummarySupport.isVisibleExecution("decision.signal", "决策信号")).isFalse();
        assertThat(ToolExecutionSummarySupport.isVisibleExecution("adaptive.decision", "自适应决策")).isFalse();
        assertThat(ToolExecutionSummarySupport.isVisibleExecution("context.assemble", "上下文装配")).isFalse();
        assertThat(ToolExecutionSummarySupport.isVisibleExecution("memory.intent.match", "记忆意图识别")).isFalse();
        assertThat(ToolExecutionSummarySupport.isVisibleExecution("memory.extract", "记忆抽取")).isFalse();
        assertThat(ToolExecutionSummarySupport.isVisibleExecution("memory.consolidation", "记忆巩固")).isFalse();
        assertThat(ToolExecutionSummarySupport.isVisibleExecution("memory.index", "记忆索引")).isFalse();
        assertThat(ToolExecutionSummarySupport.isVisibleExecution("memory.revalidation", "记忆复核")).isFalse();
        assertThat(ToolExecutionSummarySupport.isVisibleExecution("internal-stage-1", "经验匹配")).isFalse();
        assertThat(ToolExecutionSummarySupport.isVisibleExecution("web.search", "联网搜索")).isTrue();
        assertThat(ToolExecutionSummarySupport.isVisibleExecution("memory.search", "检索记忆")).isTrue();
        assertThat(ToolExecutionSummarySupport.isVisibleExecution("memory.recall", "回忆相关信息")).isTrue();
        assertThat(ToolExecutionSummarySupport.isVisibleExecution("skill.load", "加载技能")).isTrue();
        assertThat(ToolExecutionSummarySupport.isVisibleExecution(null, "搜索工具")).isFalse();
        assertThat(ToolExecutionSummarySupport.isVisibleExecution(null, "准备上下文")).isFalse();
        assertThat(ToolExecutionSummarySupport.isVisibleExecution(null, "历史经验匹配")).isFalse();
        assertThat(ToolExecutionSummarySupport.isVisibleExecution(null, "Tool Search")).isFalse();
        assertThat(ToolExecutionSummarySupport.isVisibleExecution(null, "Capability Check")).isFalse();
        assertThat(ToolExecutionSummarySupport.isVisibleExecution(null, "Experience Matching")).isFalse();
        assertThat(ToolExecutionSummarySupport.isVisibleExecution(null, "Context Assembly")).isFalse();
        assertThat(ToolExecutionSummarySupport.isVisibleExecution("tool-search", "Tool Search")).isFalse();
        assertThat(ToolExecutionSummarySupport.isVisibleExecution(null, "联网搜索")).isTrue();
        assertThat(ToolExecutionSummarySupport.isVisibleExecution(null, "Web Search")).isTrue();
    }

    private ReactAgentState 降级状态() {
        return ReactAgentState.builder()
                .traceId("trace-skill-failure")
                .sessionId("session-1")
                .turnId("turn-1")
                .goal("帮我调研资料")
                .source(InteractionSource.legacy("web", "session-1"))
                .taskMode(AgentTaskMode.AUTO)
                .steps(List.of())
                .stepCount(0)
                .shortTermMemory(List.of())
                .mentionedEntities(List.of())
                .budget(Budget.builder()
                        .maxTokens(1000)
                        .tokensUsed(0)
                        .tokensReserved(0)
                        .maxSteps(5)
                        .stepsUsed(0)
                        .maxDuration(Duration.ofMinutes(5))
                        .elapsed(Duration.ZERO)
                        .build())
                .depth(0)
                .done(true)
                .terminationReason("技能加载失败")
                .completionReason(CompletionReason.UNEXPECTED_EXCEPTION)
                .completionMode(CompletionMode.DEGRADED)
                .suspended(false)
                .build();
    }
}
