package com.lifepilot.agent.recovery;

import com.lifepilot.agent.model.CompletionMode;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.ReactStep;
import com.lifepilot.agent.model.ReactStepSerializer;
import com.lifepilot.agent.model.SuspendReason;
import com.lifepilot.interaction.model.ArtifactRef;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 任务恢复摘要构造器。
 *
 * <p>把挂起、降级和工具失败压缩成主对话可直接展示的轻量语义，
 * 避免前端从错误文本或工具轨迹里反推恢复方式。</p>
 *
 * @author zsg
 * @since 2026-07-04
 */
public final class TaskRecoverySummaryBuilder {

    public static final String AWAIT_USER_INPUT_SOURCE_ID = "__await_user_input__";
    private static final int MAX_RECOVERY_ARTIFACT_REFS = 8;

    private TaskRecoverySummaryBuilder() {}

    /**
     * 从最终 Agent 状态构建恢复摘要。
     *
     * @param state Agent 最终状态
     * @return 可展示的恢复摘要
     */
    public static Optional<Map<String, Object>> fromState(ReactAgentState state) {
        return fromState(state, toolSummariesFromSteps(state.steps()));
    }

    /**
     * 从最终 Agent 状态构建恢复摘要。
     *
     * @param state         Agent 最终状态
     * @param toolSummaries 本轮工具摘要
     * @return 可展示的恢复摘要
     */
    public static Optional<Map<String, Object>> fromState(
            ReactAgentState state,
            List<Map<String, Object>> toolSummaries) {
        if (state.suspended() && state.suspendReason() != null) {
            return Optional.of(fromSuspendReason(
                    state.suspendReason(),
                    formatSuspendReason(state.suspendReason())));
        }

        if (state.completionMode() == CompletionMode.DEGRADED
                || (state.terminationReason() != null && !state.terminationReason().isBlank())) {
            return Optional.of(fromDegradedState(state, toolSummaries));
        }

        return Optional.empty();
    }

    /**
     * 从最终 Agent 状态构建恢复摘要，并把本轮产物引用合入可恢复工具断点。
     *
     * @param state            Agent 最终状态
     * @param toolSummaries    本轮工具摘要
     * @param turnArtifactRefs 本轮工具产物引用
     * @return 可展示的恢复摘要
     */
    public static Optional<Map<String, Object>> fromState(
            ReactAgentState state,
            List<Map<String, Object>> toolSummaries,
            @Nullable List<Map<String, Object>> turnArtifactRefs) {
        return fromState(state, attachTurnArtifactRefs(toolSummaries, turnArtifactRefs));
    }

    /**
     * 从 ReAct 步骤压缩工具摘要，供持久化路径生成同一份恢复语义。
     *
     * @param steps ReAct 步骤
     * @return 工具摘要
     */
    public static List<Map<String, Object>> toolSummariesFromSteps(List<ReactStep> steps) {
        return toolSummariesFromSerializedSteps(ReactStepSerializer.serialize(steps));
    }

    /**
     * 从 ReAct 步骤压缩工具摘要，并把本轮产物引用合入可恢复工具断点。
     *
     * @param steps        ReAct 步骤
     * @param artifactRefs 本轮工具产物引用
     * @return 工具摘要
     */
    public static List<Map<String, Object>> toolSummariesFromSteps(
            List<ReactStep> steps,
            @Nullable List<ArtifactRef> artifactRefs) {
        return attachTurnArtifactRefs(toolSummariesFromSteps(steps), artifactRefPayloads(artifactRefs));
    }

    /**
     * 将本轮工具产物引用转换为前端可直接使用的 payload。
     *
     * @param artifactRefs 产物引用
     * @return artifact-ref payload 列表
     */
    public static List<Map<String, Object>> artifactRefPayloads(@Nullable List<ArtifactRef> artifactRefs) {
        if (artifactRefs == null || artifactRefs.isEmpty()) {
            return List.of();
        }
        var result = new ArrayList<Map<String, Object>>();
        var seen = new LinkedHashSet<String>();
        for (ArtifactRef ref : artifactRefs) {
            if (ref == null || ref.artifactId() == null || ref.artifactId().isBlank()) {
                continue;
            }
            if (!seen.add(ref.artifactId())) {
                continue;
            }
            var payload = new LinkedHashMap<String, Object>();
            payload.put("artifactId", ref.artifactId());
            putIfPresent(payload, "fileName", ref.fileName());
            putIfPresent(payload, "mimeType", ref.mimeType());
            if (ref.kind() != null) {
                payload.put("kind", ref.kind().name());
            }
            payload.put("size", ref.size());
            payload.put("downloadUrl", "/api/artifacts/" + ref.artifactId() + "/download");
            result.add(freezeMap(payload));
            if (result.size() >= MAX_RECOVERY_ARTIFACT_REFS) {
                break;
            }
        }
        return result.isEmpty() ? List.of() : List.copyOf(result);
    }

    /**
     * 把本轮产物引用合入最后一个真实失败工具；没有失败工具时合入最后一个可见工具摘要。
     *
     * <p>工具产物有时由 {@code AgentLoopContext} 旁路收集，不一定出现在 observation JSON 中。
     * 恢复摘要需要知道这些产物，避免继续任务时重复生成或丢失可复用文件。</p>
     *
     * @param toolSummaries    工具摘要
     * @param turnArtifactRefs 本轮产物引用 payload
     * @return 合并产物后的工具摘要
     */
    public static List<Map<String, Object>> attachTurnArtifactRefs(
            @Nullable List<Map<String, Object>> toolSummaries,
            @Nullable List<Map<String, Object>> turnArtifactRefs) {
        var artifactRefs = normalizeArtifactRefs(turnArtifactRefs);
        if (toolSummaries == null || toolSummaries.isEmpty() || artifactRefs.isEmpty()) {
            return toolSummaries == null || toolSummaries.isEmpty() ? List.of() : List.copyOf(toolSummaries);
        }
        int targetIndex = lastFailedToolIndex(toolSummaries);
        if (targetIndex < 0) {
            targetIndex = toolSummaries.size() - 1;
        }
        var result = new ArrayList<Map<String, Object>>(toolSummaries.size());
        for (int index = 0; index < toolSummaries.size(); index++) {
            var summary = toolSummaries.get(index);
            if (index != targetIndex) {
                result.add(summary);
                continue;
            }
            var enriched = new LinkedHashMap<>(summary);
            var mergedArtifactRefs = mergeArtifactRefs(enriched.get("artifactRefs"), artifactRefs);
            if (!mergedArtifactRefs.isEmpty()) {
                enriched.put("artifactRefs", mergedArtifactRefs);
                Object actions = attachArtifactRefsToRecoveryActions(
                        enriched.get("recoveryActions"),
                        mergedArtifactRefs);
                if (actions != null) {
                    enriched.put("recoveryActions", actions);
                }
            }
            result.add(freezeMap(enriched));
        }
        return List.copyOf(result);
    }

    /**
     * 从步骤历史提取可注入 prompt 的恢复上下文。
     *
     * <p>用于 checkpoint 恢复后的下一轮推理：此时 {@link ReactAgentState#completionMode()}
     * 已重置为 NORMAL，不能再依赖终态判断；但步骤里仍保留失败工具调用，可据此告诉模型从哪里继续。</p>
     *
     * @param steps ReAct 步骤
     * @return 包含 checkpoint 与 nextActions 的恢复上下文
     */
    public static Optional<Map<String, Object>> recoveryContextFromSteps(List<ReactStep> steps) {
        return recoveryContextFromSteps(steps, null);
    }

    /**
     * 从步骤历史提取可注入 prompt 的恢复上下文，并保留本轮旁路收集的产物引用。
     *
     * @param steps        ReAct 步骤
     * @param artifactRefs 本轮工具产物引用
     * @return 包含 checkpoint 与 nextActions 的恢复上下文
     */
    public static Optional<Map<String, Object>> recoveryContextFromSteps(
            List<ReactStep> steps,
            @Nullable List<ArtifactRef> artifactRefs) {
        var failedTool = lastFailedTool(toolSummariesFromSteps(steps, artifactRefs));
        if (failedTool == null) {
            return Optional.empty();
        }
        var context = new LinkedHashMap<String, Object>();
        var checkpoint = failedToolCheckpoint(failedTool);
        if (!checkpoint.isEmpty()) {
            context.put("checkpoint", checkpoint);
        }
        var nextActions = failedToolNextActions(failedTool);
        context.put("resumeStrategy", resumeStrategy("RESUME", checkpoint));
        context.put("nextActions", nextActions);
        return Optional.of(freezeMap(context));
    }

    /**
     * 从挂起原因构建恢复摘要。
     *
     * @param reason 挂起原因
     * @param detail 已格式化的人类可读说明
     * @return 可展示的恢复摘要
     */
    public static Map<String, Object> fromSuspendReason(SuspendReason reason, @Nullable String detail) {
        var summary = new LinkedHashMap<String, Object>();
        String reasonType = reason.getClass().getSimpleName();
        String reasonSourceId = sourceId(reason);
        String resumeMode = resumeMode(reason);
        boolean canResume = canManualResume(reason);

        summary.put("status", "SUSPENDED");
        summary.put("reasonType", reasonType);
        if (reasonSourceId != null && !reasonSourceId.isBlank()) {
            summary.put("reasonSourceId", reasonSourceId);
        }
        summary.put("resumeMode", resumeMode);
        summary.put("canResume", canResume);
        summary.put("canRestart", true);
        summary.put("title", suspendTitle(reason));
        summary.put("detail", cleanDetail(detail, suspendDetail(reason)));
        summary.put("actionLabel", actionLabel(resumeMode, canResume));
        summary.put("nextActions", suspendNextActions(reason, resumeMode));
        return freezeMap(summary);
    }

    @Nullable
    public static String sourceId(SuspendReason reason) {
        return switch (reason) {
            case SuspendReason.WorkflowWait workflowWait -> workflowWait.executionId();
            case SuspendReason.UserConfirmation confirmation -> confirmation.confirmationId();
            case SuspendReason.RemoteDelegation remoteDelegation -> remoteDelegation.remoteTaskId();
            case SuspendReason.ScheduledWakeup _ -> null;
            case SuspendReason.ExternalDataWait externalDataWait -> externalDataWait.dataSourceId();
            case SuspendReason.BrowserTakeover browserTakeover -> browserTakeover.sessionId();
        };
    }

    private static Map<String, Object> fromDegradedState(
            ReactAgentState state,
            List<Map<String, Object>> toolSummaries) {
        var summary = new LinkedHashMap<String, Object>();
        summary.put("status", "DEGRADED");
        summary.put("resumeMode", "manual");
        summary.put("canResume", true);
        summary.put("canRestart", true);
        summary.put("actionLabel", "继续");

        var failedTool = lastFailedTool(toolSummaries);
        if (failedTool != null) {
            summary.put("actionLabel", ToolExecutionSummarySupport.resumeActionLabel(
                    stringValue(failedTool.get("failureCategory"))));
            summary.put("title", failedExecutionTitle(failedTool));
            summary.put("detail", cleanDetail(
                    stringValue(failedTool.get("recoveryHint")),
                    "可以从失败处继续，或重新开始这一轮。"));
            var checkpoint = failedToolCheckpoint(failedTool);
            if (!checkpoint.isEmpty()) {
                summary.put("checkpoint", checkpoint);
            }
            summary.put("nextActions", failedToolNextActions(failedTool));
            return freezeMap(summary);
        }

        summary.put("title", "可以继续这一轮");
        summary.put("actionLabel", "继续处理");
        summary.put("detail", generalDegradedDetail(state.terminationReason()));
        summary.put("nextActions", List.of("复用已有结果", "继续处理剩余任务"));
        return freezeMap(summary);
    }

    private static String generalDegradedDetail(@Nullable String terminationReason) {
        String reason = cleanDetail(terminationReason, "");
        String guidance = "已有结果会保留，知微可以继续处理剩余部分。";
        if (reason == null || reason.isBlank()) {
            return guidance;
        }
        return reason + "；" + guidance;
    }

    private static Map<String, Object> failedToolCheckpoint(Map<String, Object> failedTool) {
        var checkpoint = new LinkedHashMap<String, Object>();
        checkpoint.put("kind", "TOOL_FAILURE");
        copyIfPresent(failedTool, checkpoint, "toolId");
        copyIfPresent(failedTool, checkpoint, "toolName");
        copyIfPresent(failedTool, checkpoint, "executionKind");
        copyIfPresent(failedTool, checkpoint, "action");
        copyIfPresent(failedTool, checkpoint, "failureCategory");
        copyIfPresent(failedTool, checkpoint, "callId");
        copyIfPresent(failedTool, checkpoint, "interrupted");
        copyIfPresent(failedTool, checkpoint, "subjectLabel");
        copyIfPresent(failedTool, checkpoint, "subjectNames");
        copyIfPresent(failedTool, checkpoint, "inputSummary");
        copyIfPresent(failedTool, checkpoint, "inputDetail");
        copyIfPresent(failedTool, checkpoint, "outputSummary");
        copyIfPresent(failedTool, checkpoint, "outputDetail");
        copyIfPresent(failedTool, checkpoint, "workingDirectory");
        copyIfPresent(failedTool, checkpoint, "generatedFilePath");
        copyIfPresent(failedTool, checkpoint, "artifactRefs");
        copyIfPresent(failedTool, checkpoint, "missingCapabilities");
        copyIfPresent(failedTool, checkpoint, "inputStepIndex");
        copyIfPresent(failedTool, checkpoint, "outputStepIndex");
        return freezeMap(checkpoint);
    }

    private static List<String> failedToolNextActions(Map<String, Object> failedTool) {
        String category = stringValue(failedTool.get("failureCategory"));
        return switch (category != null ? category : "UNKNOWN") {
            case "CAPABILITY" -> capabilityFailureNextActions(failedTool);
            case "COMMAND" -> List.of("查看命令输出并修正报错原因", "从失败命令后继续执行验证");
            case "FILE" -> List.of("检查文件路径或权限", "保留已完成修改并从失败文件操作继续");
            case "BROWSER" -> List.of("检查浏览器页面状态、登录或元素选择", "保留当前上下文并从失败页面操作继续");
            case "NETWORK" -> List.of("确认外部访问是否可用", "更换资料来源或重试失败请求");
            case "MEMORY" -> List.of("检查记忆条件或内容", "调整后继续沉淀或检索记忆");
            case "SKILL" -> skillFailureNextActions(failedTool);
            case "KNOWLEDGE" -> List.of("检查资料来源、索引或解析结果", "保留已完成资料处理并从失败处继续");
            case "WORKFLOW" -> List.of("查看当前工作流节点状态", "从失败节点继续或重新执行该节点");
            case "INTEGRATION" -> List.of("检查连接器服务、授权或参数", "连接恢复后继续当前步骤");
            case "AGENT" -> List.of("确认委托目标或远程任务状态", "合并已有结果后继续本地处理");
            case "MODEL" -> List.of("检查模型配置、输入长度或限流状态", "调整后继续生成或重试模型调用");
            case "REPOSITORY" -> List.of("检查分支、权限、冲突或仓库状态", "保留已完成修改并继续仓库操作");
            default -> List.of("查看失败步骤的输入输出", "从失败处继续或重新开始这一轮");
        };
    }

    public static String resumeStrategy(String action, Map<String, Object> checkpoint) {
        boolean restart = "RESTART".equalsIgnoreCase(action)
                || "restart".equalsIgnoreCase(stringValue(checkpoint.get("recoveryActionMode")));
        boolean hasCheckpoint = checkpoint != null && !checkpoint.isEmpty();
        if (restart) {
            return hasCheckpoint
                    ? "重新开始原始任务，优先修正上一轮失败点，再完成原始目标。"
                    : "重新开始原始任务，保留可复用线索并按恢复计划推进。";
        }
        if (!hasCheckpoint) {
            return "按恢复计划继续处理剩余任务，保留已完成内容。";
        }
        if (Boolean.TRUE.equals(checkpoint.get("interrupted"))) {
            return "从已开始但未返回结果的步骤继续，保留已完成步骤，不要重复成功部分。";
        }
        String executionKind = stringValue(checkpoint.get("executionKind"));
        String failureCategory = stringValue(checkpoint.get("failureCategory"));
        if ("CAPABILITY".equals(failureCategory)) {
            return "先修复缺失工具或技能连接，再从失败断点继续，保留已完成步骤。";
        }
        if ("SKILL".equals(executionKind) || "SKILL".equals(failureCategory)) {
            return "从失败技能步骤继续，保留当前对话、技能主体和失败输出，不要重新规划无关步骤。";
        }
        return "从失败断点继续，保留已完成步骤和失败输出，不要重复成功部分。";
    }

    private static String failedExecutionTitle(Map<String, Object> failedTool) {
        String executionKind = stringValue(failedTool.get("executionKind"));
        String category = stringValue(failedTool.get("failureCategory"));
        if ("SKILL".equals(executionKind) || "SKILL".equals(category)) {
            String subjectName = firstSubjectName(failedTool);
            if (subjectName != null) {
                return "技能 " + subjectName + " 没有完成";
            }
            return isSkillLoadFailure(failedTool) ? "技能加载没有完成" : "技能执行没有完成";
        }

        String toolName = stringValue(failedTool.get("toolName"));
        if (toolName == null) {
            toolName = stringValue(failedTool.get("toolId"));
        }
        return (toolName != null ? toolName : "工具") + " 没有完成";
    }

    private static List<String> skillFailureNextActions(Map<String, Object> failedTool) {
        if (!isSkillLoadFailure(failedTool)) {
            String subjectName = firstSubjectName(failedTool);
            if (subjectName != null) {
                return List.of(
                        "检查技能 " + subjectName + " 的输入、依赖和执行步骤",
                        "保留当前进度并从失败技能步骤继续");
            }
            return List.of("检查技能输入、依赖和执行步骤", "保留当前进度并从失败技能步骤继续");
        }
        String subjectName = firstSubjectName(failedTool);
        if (subjectName != null) {
            return List.of(
                    "确认技能 " + subjectName + " 的名称和依赖是否可用",
                    "重新加载技能后继续当前任务");
        }
        return List.of("确认技能名称和依赖是否可用", "重新加载技能后继续当前任务");
    }

    private static boolean isSkillLoadFailure(Map<String, Object> failedTool) {
        return "skill.load".equals(stringValue(failedTool.get("toolId")))
                || "加载技能".equals(stringValue(failedTool.get("action")));
    }

    private static List<String> capabilityFailureNextActions(Map<String, Object> failedTool) {
        String missing = missingCapabilitiesLine(failedTool);
        if (missing != null) {
            return List.of(
                    "补齐缺失能力：" + missing,
                    "到能力中心检查 Skill suggestedTools、MCP 工具提供方或本地工具连接");
        }
        return List.of("确认能力中心能看到所需工具和技能", "修正 Skill suggestedTools 或恢复缺失的工具提供方");
    }

    @Nullable
    private static String missingCapabilitiesLine(Map<String, Object> failedTool) {
        Object value = failedTool.get("missingCapabilities");
        if (!(value instanceof List<?> items) || items.isEmpty()) {
            return null;
        }
        var names = new ArrayList<String>();
        for (Object item : items) {
            if (item instanceof Map<?, ?> map) {
                String id = stringValue(map.get("id"));
                if (id != null && !names.contains(id)) {
                    names.add(id);
                }
                continue;
            }
            String text = stringValue(item);
            if (text != null && !names.contains(text)) {
                names.add(text);
            }
        }
        return names.isEmpty() ? null : String.join("、", names);
    }

    @Nullable
    private static String firstSubjectName(Map<String, Object> summary) {
        Object value = summary.get("subjectNames");
        if (value instanceof List<?> names) {
            for (Object name : names) {
                String text = stringValue(name);
                if (text != null) {
                    return text;
                }
            }
        }
        return null;
    }

    private static List<String> suspendNextActions(SuspendReason reason, String resumeMode) {
        if ("user_reply".equals(resumeMode)) {
            return List.of("直接回复缺失信息", "知微会接着当前进度继续");
        }
        return switch (reason) {
            case SuspendReason.BrowserTakeover _ -> List.of("完成浏览器里的人工操作", "回到知微继续当前任务");
            case SuspendReason.UserConfirmation _ -> List.of("确认或拒绝这一步操作", "知微会按你的选择继续");
            case SuspendReason.WorkflowWait _ -> List.of("等待工作流完成", "完成后合并结果继续处理");
            case SuspendReason.RemoteDelegation _ -> List.of("等待远程任务返回", "返回后合并结果继续处理");
            case SuspendReason.ScheduledWakeup _ -> List.of("等待设定时间到达", "到时自动恢复当前任务");
            case SuspendReason.ExternalDataWait _ -> List.of("等待外部数据就绪", "数据可用后从当前进度继续");
        };
    }

    @Nullable
    private static Map<String, Object> lastFailedTool(List<Map<String, Object>> toolSummaries) {
        int index = lastFailedToolIndex(toolSummaries);
        return index >= 0 ? toolSummaries.get(index) : null;
    }

    private static int lastFailedToolIndex(List<Map<String, Object>> toolSummaries) {
        for (int index = toolSummaries.size() - 1; index >= 0; index--) {
            var tool = toolSummaries.get(index);
            if (!ToolExecutionSummarySupport.isVisibleExecution(
                    stringValue(tool.get("toolId")),
                    stringValue(tool.get("toolName")))) {
                continue;
            }
            Object success = tool.get("success");
            if (success instanceof Boolean bool && !bool) {
                return index;
            }
        }
        return -1;
    }

    public static List<Map<String, Object>> toolSummariesFromSerializedSteps(List<Map<String, Object>> reactSteps) {
        if (reactSteps == null || reactSteps.isEmpty()) {
            return List.of();
        }
        var drafts = new ArrayList<ToolSummaryDraft>();
        for (Map<String, Object> step : reactSteps) {
            String type = stringValue(step.get("type"));
            if ("TOOL_CALL".equals(type)) {
                String toolId = stringValue(step.get("toolId"));
                if (toolId == null || toolId.isBlank()) {
                    continue;
                }
                String toolName = stringValue(step.get("toolName"));
                if (!ToolExecutionSummarySupport.isVisibleExecution(toolId, toolName)) {
                    continue;
                }
                var summary = new LinkedHashMap<String, Object>();
                summary.put("toolId", toolId);
                summary.put("executionKind", ToolExecutionSummarySupport.executionKind(toolId));
                summary.put("status", "RUNNING");
                putIfPresent(summary, "callId", stringValue(step.get("callId")));
                putIfPresent(summary, "inputStepIndex", integerValue(step.get("index")));
                putIfPresent(summary, "toolName", toolName);
                putIfPresent(summary, "action", ToolExecutionSummarySupport.executionAction(toolId));
                putIfPresent(summary, "subjectLabel", stringValue(step.get("subjectLabel")));
                putIfPresent(summary, "subjectNames", step.get("subjectNames"));
                putIfPresent(summary, "inputSummary", stringValue(step.get("inputSummary")));
                putIfPresent(summary, "inputDetail", stringValue(step.get("inputDetail")));
                summary.put("success", true);
                summary.put("latencyMs", longValue(step.get("latencyMs")));
                drafts.add(new ToolSummaryDraft(toolId, stringValue(step.get("callId")), summary));
                continue;
            }
            if ("OBSERVATION".equals(type)) {
                String toolId = stringValue(step.get("toolId"));
                if (toolId == null || toolId.isBlank()) {
                    continue;
                }
                String toolName = stringValue(step.get("toolName"));
                if (!ToolExecutionSummarySupport.isVisibleExecution(toolId, toolName)) {
                    continue;
                }
                var draft = findToolSummaryDraft(drafts, toolId, stringValue(step.get("callId")));
                if (draft == null) {
                    var summary = new LinkedHashMap<String, Object>();
                    summary.put("toolId", toolId);
                    summary.put("executionKind", ToolExecutionSummarySupport.executionKind(toolId));
                    summary.put("status", "RUNNING");
                    putIfPresent(summary, "callId", stringValue(step.get("callId")));
                    putIfPresent(summary, "toolName", toolName);
                    putIfPresent(summary, "action", ToolExecutionSummarySupport.executionAction(toolId));
                    summary.put("latencyMs", 0L);
                    draft = new ToolSummaryDraft(toolId, stringValue(step.get("callId")), summary);
                    drafts.add(draft);
                }
                draft.observed = true;
                boolean success = booleanValue(step.get("success"), true);
                draft.summary.put("success", success);
                draft.summary.put("status", success ? "SUCCEEDED" : "FAILED");
                putIfPresent(draft.summary, "outputStepIndex", integerValue(step.get("index")));
                putIfPresent(draft.summary, "subjectLabel", stringValue(step.get("subjectLabel")));
                putIfPresent(draft.summary, "subjectNames", step.get("subjectNames"));
                putIfPresent(draft.summary, "outputSummary", stringValue(step.get("outputSummary")));
                putIfPresent(draft.summary, "outputDetail", stringValue(step.get("outputDetail")));
                putIfPresent(draft.summary, "workingDirectory", stringValue(step.get("workingDirectory")));
                putIfPresent(draft.summary, "generatedFilePath", stringValue(step.get("generatedFilePath")));
                putIfPresent(draft.summary, "artifactRefs", step.get("artifactRefs"));
                putIfPresent(draft.summary, "missingCapabilities", step.get("missingCapabilities"));
                putIfPresent(draft.summary, "output", step.get("output"));
                if (!success) {
                    String failureCategory = ToolExecutionSummarySupport.failureCategory(
                            toolId,
                            stringValue(draft.summary.get("outputSummary")),
                            stringValue(draft.summary.get("outputDetail")),
                            draft.summary.get("output"));
                    draft.summary.put("failureCategory", failureCategory);
                    var missingCapabilities = ToolExecutionSummarySupport.missingCapabilities(
                            toolId,
                            stringValue(draft.summary.get("outputSummary")),
                            stringValue(draft.summary.get("outputDetail")),
                            draft.summary.get("output"));
                    if (!missingCapabilities.isEmpty() && !draft.summary.containsKey("missingCapabilities")) {
                        draft.summary.put("missingCapabilities", missingCapabilities);
                    }
                    putIfPresent(draft.summary, "recoveryHint",
                            ToolExecutionSummarySupport.recoveryHint(toolId, failureCategory));
                    draft.summary.put("recoveryActions", failedToolRecoveryActions(draft.summary, toolId));
                }
            }
        }
        return drafts.stream()
                .map(draft -> {
                    if (!draft.observed) {
                        markInterruptedTool(draft);
                    }
                    return Collections.unmodifiableMap(draft.summary);
                })
                .toList();
    }

    private static void markInterruptedTool(ToolSummaryDraft draft) {
        draft.summary.put("success", false);
        draft.summary.put("status", "FAILED");
        draft.summary.put("hasMoreSteps", false);
        draft.summary.put("interrupted", true);
        draft.summary.put("failureCategory", ToolExecutionSummarySupport.failureCategory(draft.toolId));
        putIfPresent(draft.summary, "outputSummary", "这一步已经开始，但没有返回执行结果。");
        putIfPresent(draft.summary, "recoveryHint", interruptedRecoveryHint(draft.toolId));
        draft.summary.put("recoveryActions", failedToolRecoveryActions(draft.summary, draft.toolId));
    }

    private static String interruptedRecoveryHint(String toolId) {
        if (ToolExecutionSummarySupport.isSkillLoad(toolId)) {
            return "技能加载已经开始但没有返回结果，可以检查技能名称或依赖后继续。";
        }
        if (ToolExecutionSummarySupport.isSkill(toolId)) {
            return "技能执行已经开始但没有返回结果，可以检查输入、依赖或技能步骤后继续。";
        }
        return ToolExecutionSummarySupport.recoveryHint(toolId);
    }

    private static List<Map<String, Object>> failedToolRecoveryActions(Map<String, Object> summary, String toolId) {
        return ToolExecutionSummarySupport.recoveryActions(toolId, stringValue(summary.get("failureCategory"))).stream()
                .map(action -> {
                    var enriched = new LinkedHashMap<>(action);
                    copyIfAbsent(summary, enriched, "toolId");
                    copyIfAbsent(summary, enriched, "toolName");
                    copyIfAbsent(summary, enriched, "executionKind");
                    copyIfAbsent(summary, enriched, "action");
                    copyIfAbsent(summary, enriched, "callId");
                    copyIfAbsent(summary, enriched, "interrupted");
                    copyIfAbsent(summary, enriched, "subjectLabel");
                    copyIfAbsent(summary, enriched, "subjectNames");
                    copyIfAbsent(summary, enriched, "inputSummary");
                    copyIfAbsent(summary, enriched, "inputDetail");
                    copyIfAbsent(summary, enriched, "outputSummary");
                    copyIfAbsent(summary, enriched, "outputDetail");
                    copyIfAbsent(summary, enriched, "workingDirectory");
                    copyIfAbsent(summary, enriched, "generatedFilePath");
                    copyIfAbsent(summary, enriched, "artifactRefs");
                    copyIfAbsent(summary, enriched, "missingCapabilities");
                    copyIfAbsent(summary, enriched, "recoveryHint");
                    enriched.putIfAbsent("nextActions", failedToolRecoveryNextActions(summary, action));
                    return freezeMap(enriched);
                })
                .toList();
    }

    private static List<String> failedToolRecoveryNextActions(Map<String, Object> failedTool,
                                                              Map<String, Object> action) {
        String mode = stringValue(action.get("mode"));
        if ("restart".equals(mode)) {
            return failedToolRestartNextActions(failedTool);
        }
        return failedToolNextActions(failedTool);
    }

    private static List<String> failedToolRestartNextActions(Map<String, Object> failedTool) {
        String category = stringValue(failedTool.get("failureCategory"));
        return switch (category != null ? category : "UNKNOWN") {
            case "CAPABILITY" -> capabilityRestartNextActions(failedTool);
            case "COMMAND" -> List.of("保留失败命令输出作为线索", "重新开始这一轮并优先修正命令错误");
            case "FILE" -> List.of("保留已完成文件修改线索", "重新开始文件操作并避开失败路径");
            case "BROWSER" -> List.of("保留页面状态和失败操作线索", "重新打开或重新操作目标页面");
            case "NETWORK" -> List.of("保留已获取资料线索", "重新选择资料来源后重新执行");
            case "MEMORY" -> List.of("保留当前记忆判断依据", "重新执行记忆沉淀或检索");
            case "SKILL" -> skillRestartNextActions(failedTool);
            case "KNOWLEDGE" -> List.of("保留已处理资料线索", "重新检索或重建资料处理步骤");
            case "WORKFLOW" -> List.of("保留当前工作流状态", "重新执行失败节点或整段流程");
            case "INTEGRATION" -> List.of("保留连接器失败原因", "连接恢复后重新执行调用");
            case "AGENT" -> List.of("保留远程协作状态", "重新委托或切换为本地处理");
            case "MODEL" -> List.of("保留模型调用失败原因", "调整模型配置后重新生成");
            case "REPOSITORY" -> List.of("保留仓库失败状态", "重新执行仓库操作并处理冲突");
            default -> List.of("保留失败步骤的输入输出", "重新开始这一轮并优先修正失败点");
        };
    }

    private static List<String> capabilityRestartNextActions(Map<String, Object> failedTool) {
        String missing = missingCapabilitiesLine(failedTool);
        if (missing != null) {
            return List.of("保留缺失能力线索：" + missing, "修复缺失能力后重新执行相关步骤");
        }
        return List.of("保留缺失工具和 Skill 引用线索", "修复缺失能力后重新执行相关步骤");
    }

    private static List<String> skillRestartNextActions(Map<String, Object> failedTool) {
        String subjectName = firstSubjectName(failedTool);
        if (isSkillLoadFailure(failedTool)) {
            return subjectName != null
                    ? List.of("保留技能 " + subjectName + " 的加载失败原因", "重新加载技能后重跑当前任务")
                    : List.of("保留技能加载失败原因", "重新加载技能后重跑当前任务");
        }
        return subjectName != null
                ? List.of("保留技能 " + subjectName + " 的失败输入输出", "重新执行失败技能步骤")
                : List.of("保留技能失败输入输出", "重新执行失败技能步骤");
    }

    @Nullable
    private static ToolSummaryDraft findToolSummaryDraft(List<ToolSummaryDraft> drafts,
                                                         String toolId,
                                                         @Nullable String callId) {
        if (callId != null && !callId.isBlank()) {
            for (var draft : drafts) {
                if (callId.equals(draft.callId)) {
                    return draft;
                }
            }
        }
        for (var draft : drafts) {
            if (!draft.observed && draft.toolId.equals(toolId)) {
                return draft;
            }
        }
        return null;
    }

    private static String resumeMode(SuspendReason reason) {
        return switch (reason) {
            case SuspendReason.ExternalDataWait externalDataWait
                    when AWAIT_USER_INPUT_SOURCE_ID.equals(externalDataWait.dataSourceId()) -> "user_reply";
            case SuspendReason.BrowserTakeover _ -> "browser";
            case SuspendReason.ScheduledWakeup _ -> "scheduled";
            case SuspendReason.WorkflowWait _, SuspendReason.RemoteDelegation _ -> "external";
            case SuspendReason.UserConfirmation _ -> "manual";
            case SuspendReason.ExternalDataWait _ -> "external";
        };
    }

    private static boolean canManualResume(SuspendReason reason) {
        return switch (reason) {
            case SuspendReason.ExternalDataWait externalDataWait ->
                    !AWAIT_USER_INPUT_SOURCE_ID.equals(externalDataWait.dataSourceId());
            case SuspendReason.ScheduledWakeup _ -> false;
            default -> true;
        };
    }

    private static String suspendTitle(SuspendReason reason) {
        return switch (reason) {
            case SuspendReason.ExternalDataWait externalDataWait
                    when AWAIT_USER_INPUT_SOURCE_ID.equals(externalDataWait.dataSourceId()) -> "等待你补充信息";
            case SuspendReason.BrowserTakeover _ -> "等待浏览器操作";
            case SuspendReason.UserConfirmation _ -> "等待确认";
            case SuspendReason.WorkflowWait _ -> "等待工作流完成";
            case SuspendReason.RemoteDelegation _ -> "等待远程任务返回";
            case SuspendReason.ScheduledWakeup _ -> "已安排稍后继续";
            case SuspendReason.ExternalDataWait _ -> "等待外部数据";
        };
    }

    private static String suspendDetail(SuspendReason reason) {
        return switch (reason) {
            case SuspendReason.ExternalDataWait externalDataWait
                    when AWAIT_USER_INPUT_SOURCE_ID.equals(externalDataWait.dataSourceId()) ->
                    "你直接回复补充内容，知微会接着当前进度继续。";
            case SuspendReason.BrowserTakeover _ -> "完成浏览器里的操作后，可以回来继续当前任务。";
            case SuspendReason.UserConfirmation _ -> "确认后知微会从当前步骤继续执行。";
            case SuspendReason.WorkflowWait _ -> "工作流完成后知微会继续处理后续步骤。";
            case SuspendReason.RemoteDelegation _ -> "远程任务返回后知微会合并结果继续。";
            case SuspendReason.ScheduledWakeup _ -> "到达设定时间后知微会自动恢复。";
            case SuspendReason.ExternalDataWait _ -> "数据就绪后知微会从当前进度继续。";
        };
    }

    private static String actionLabel(String resumeMode, boolean canResume) {
        if (!canResume) {
            return "等待";
        }
        if ("browser".equals(resumeMode)) {
            return "继续";
        }
        if ("user_reply".equals(resumeMode)) {
            return "直接回复";
        }
        return "继续";
    }

    private static String formatSuspendReason(SuspendReason reason) {
        return switch (reason) {
            case SuspendReason.WorkflowWait w ->
                    "等待工作流完成 [%s] %s".formatted(w.executionId(), w.workflowName());
            case SuspendReason.UserConfirmation u ->
                    "等待用户确认工具执行 [%s] 风险等级: %s".formatted(u.toolId(), u.riskLevel());
            case SuspendReason.RemoteDelegation r ->
                    "等待远程 Agent 返回 [%s] 目标: %s".formatted(r.remoteTaskId(), r.delegatedGoal());
            case SuspendReason.ScheduledWakeup s ->
                    "定时唤醒 [%s] 原因: %s".formatted(s.wakeupAt(), s.reason());
            case SuspendReason.ExternalDataWait e ->
                    "等待外部数据就绪 [%s] %s".formatted(e.dataSourceId(), e.description());
            case SuspendReason.BrowserTakeover bt ->
                    "等待浏览器人工接管 [%s] %s".formatted(bt.sessionId(), bt.reason());
        };
    }

    private static String cleanDetail(@Nullable String preferred, String fallback) {
        if (preferred == null || preferred.isBlank()
                || "suspended".equals(preferred)
                || preferred.startsWith("__")) {
            return fallback;
        }
        return preferred.strip();
    }

    @Nullable
    private static String stringValue(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private static boolean booleanValue(@Nullable Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value != null) {
            return Boolean.parseBoolean(String.valueOf(value));
        }
        return fallback;
    }

    private static long longValue(@Nullable Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    @Nullable
    private static Integer integerValue(@Nullable Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static void putIfPresent(Map<String, Object> target, String key, @Nullable Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String text && text.isBlank()) {
            return;
        }
        target.put(key, value);
    }

    private static List<Map<String, Object>> normalizeArtifactRefs(
            @Nullable List<Map<String, Object>> artifactRefs) {
        if (artifactRefs == null || artifactRefs.isEmpty()) {
            return List.of();
        }
        var result = new ArrayList<Map<String, Object>>();
        var seen = new LinkedHashSet<String>();
        for (Map<String, Object> artifactRef : artifactRefs) {
            addArtifactRef(result, seen, artifactRef);
            if (result.size() >= MAX_RECOVERY_ARTIFACT_REFS) {
                break;
            }
        }
        return result.isEmpty() ? List.of() : List.copyOf(result);
    }

    private static List<Map<String, Object>> mergeArtifactRefs(
            @Nullable Object existing,
            List<Map<String, Object>> additional) {
        var result = new ArrayList<Map<String, Object>>();
        var seen = new LinkedHashSet<String>();
        for (Map<String, Object> artifactRef : artifactRefsFromObject(existing)) {
            addArtifactRef(result, seen, artifactRef);
        }
        for (Map<String, Object> artifactRef : additional) {
            addArtifactRef(result, seen, artifactRef);
            if (result.size() >= MAX_RECOVERY_ARTIFACT_REFS) {
                break;
            }
        }
        return result.isEmpty() ? List.of() : List.copyOf(result);
    }

    private static List<Map<String, Object>> artifactRefsFromObject(@Nullable Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        var result = new ArrayList<Map<String, Object>>();
        var seen = new LinkedHashSet<String>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                addArtifactRef(result, seen, map);
            }
            if (result.size() >= MAX_RECOVERY_ARTIFACT_REFS) {
                break;
            }
        }
        return result.isEmpty() ? List.of() : List.copyOf(result);
    }

    private static void addArtifactRef(List<Map<String, Object>> target,
                                       LinkedHashSet<String> seen,
                                       Map<?, ?> source) {
        if (source == null || source.isEmpty()) {
            return;
        }
        String artifactId = firstStringValue(source, "artifactId", "artifact_id", "id");
        if (artifactId == null || artifactId.isBlank()) {
            return;
        }
        var normalized = normalizeArtifactRefPayload(source, artifactId);
        if (seen.contains(artifactId)) {
            enrichExistingArtifactRef(target, artifactId, normalized);
            return;
        }
        if (target.size() >= MAX_RECOVERY_ARTIFACT_REFS) {
            return;
        }
        seen.add(artifactId);
        target.add(freezeMap(normalized));
    }

    private static LinkedHashMap<String, Object> normalizeArtifactRefPayload(Map<?, ?> source, String artifactId) {
        var normalized = new LinkedHashMap<String, Object>();
        String type = firstStringValue(source, "type");
        String mimeType = firstStringValue(
                source,
                "mimeType",
                "mime_type",
                "contentType",
                "content_type",
                "mediaType",
                "media_type");
        if (mimeType == null && looksLikeMimeType(type)) {
            mimeType = type;
        }
        String kind = firstStringValue(source, "kind");
        if (kind == null && type != null && !looksLikeMimeType(type)) {
            kind = type;
        }
        normalized.put("artifactId", artifactId);
        putIfPresent(normalized, "fileName", firstStringValue(source, "fileName", "file_name", "filename", "name"));
        putIfPresent(normalized, "mimeType", mimeType);
        putIfPresent(normalized, "kind", normalizeArtifactKind(kind, mimeType));
        Object size = source.get("size");
        Long sizeValue = sizeValue(size);
        if (sizeValue != null) {
            normalized.put("size", sizeValue);
        }
        String downloadUrl = firstStringValue(source, "downloadUrl", "download_url", "url");
        normalized.put("downloadUrl", downloadUrl != null
                ? downloadUrl : defaultArtifactDownloadUrl(artifactId));
        return normalized;
    }

    private static void enrichExistingArtifactRef(List<Map<String, Object>> target,
                                                  String artifactId,
                                                  Map<String, Object> incoming) {
        for (int index = 0; index < target.size(); index++) {
            Map<String, Object> existing = target.get(index);
            if (!artifactId.equals(existing.get("artifactId"))) {
                continue;
            }
            var merged = new LinkedHashMap<String, Object>(existing);
            incoming.forEach((key, value) -> {
                if ("artifactId".equals(key)) {
                    return;
                }
                if (isMissingArtifactRefValue(merged.get(key))
                        || shouldReplaceDefaultDownloadUrl(key, artifactId, merged.get(key), value)) {
                    putIfPresent(merged, key, value);
                }
            });
            target.set(index, freezeMap(orderedArtifactRefPayload(merged)));
            return;
        }
    }

    private static boolean isMissingArtifactRefValue(@Nullable Object value) {
        return value == null || (value instanceof String text && text.isBlank());
    }

    private static boolean shouldReplaceDefaultDownloadUrl(String key,
                                                           String artifactId,
                                                           @Nullable Object existing,
                                                           @Nullable Object incoming) {
        if (!"downloadUrl".equals(key) || isMissingArtifactRefValue(incoming)) {
            return false;
        }
        String existingText = stringValue(existing);
        String incomingText = stringValue(incoming);
        return defaultArtifactDownloadUrl(artifactId).equals(existingText)
                && incomingText != null
                && !incomingText.equals(existingText);
    }

    private static LinkedHashMap<String, Object> orderedArtifactRefPayload(Map<String, Object> source) {
        var ordered = new LinkedHashMap<String, Object>();
        putIfPresent(ordered, "artifactId", source.get("artifactId"));
        putIfPresent(ordered, "fileName", source.get("fileName"));
        putIfPresent(ordered, "mimeType", source.get("mimeType"));
        putIfPresent(ordered, "kind", source.get("kind"));
        putIfPresent(ordered, "size", source.get("size"));
        putIfPresent(ordered, "downloadUrl", source.get("downloadUrl"));
        return ordered;
    }

    private static String defaultArtifactDownloadUrl(String artifactId) {
        return "/api/artifacts/" + artifactId + "/download";
    }

    @Nullable
    private static String normalizeArtifactKind(@Nullable String kind, @Nullable String mimeType) {
        String text = kind != null ? kind.strip() : null;
        if (text != null && !text.isBlank()) {
            String upper = text.toUpperCase(Locale.ROOT);
            if ("IMAGE".equals(upper) || "FILE".equals(upper)) {
                return upper;
            }
            return text;
        }
        return mimeType != null && mimeType.toLowerCase(Locale.ROOT).startsWith("image/")
                ? "IMAGE"
                : mimeType != null ? "FILE" : null;
    }

    private static boolean looksLikeMimeType(@Nullable String value) {
        return value != null && value.contains("/");
    }

    @Nullable
    private static Object attachArtifactRefsToRecoveryActions(@Nullable Object actionsObject,
                                                              List<Map<String, Object>> artifactRefs) {
        if (!(actionsObject instanceof List<?> actions) || actions.isEmpty() || artifactRefs.isEmpty()) {
            return actionsObject;
        }
        var result = new ArrayList<Map<String, Object>>();
        for (Object action : actions) {
            if (!(action instanceof Map<?, ?> map)) {
                continue;
            }
            var enriched = new LinkedHashMap<String, Object>();
            map.forEach((key, value) -> {
                if (key instanceof String textKey) {
                    enriched.put(textKey, value);
                }
            });
            var merged = mergeArtifactRefs(enriched.get("artifactRefs"), artifactRefs);
            if (!merged.isEmpty()) {
                enriched.put("artifactRefs", merged);
            }
            result.add(freezeMap(enriched));
        }
        return result.isEmpty() ? actionsObject : List.copyOf(result);
    }

    @Nullable
    private static String firstStringValue(Map<?, ?> source, String... keys) {
        for (String key : keys) {
            Object value = source.get(key);
            String text = stringValue(value);
            if (text != null) {
                return text.strip();
            }
        }
        return null;
    }

    @Nullable
    private static Long sizeValue(@Nullable Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = stringValue(value);
        if (text == null) {
            return null;
        }
        try {
            return Long.parseLong(text.strip());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        putIfPresent(target, key, source.get(key));
    }

    private static void copyIfAbsent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (target.containsKey(key)) {
            return;
        }
        putIfPresent(target, key, source.get(key));
    }

    private static Map<String, Object> freezeMap(LinkedHashMap<String, Object> source) {
        if (source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static final class ToolSummaryDraft {
        private final String toolId;
        @Nullable private final String callId;
        private final LinkedHashMap<String, Object> summary;
        private boolean observed;

        private ToolSummaryDraft(String toolId,
                                 @Nullable String callId,
                                 LinkedHashMap<String, Object> summary) {
            this.toolId = toolId;
            this.callId = callId;
            this.summary = summary;
        }
    }
}
