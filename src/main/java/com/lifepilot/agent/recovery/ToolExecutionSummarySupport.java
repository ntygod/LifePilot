package com.lifepilot.agent.recovery;

import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 工具执行摘要语义辅助。
 *
 * <p>集中生成主对话需要展示的工具/技能类型、失败分类和恢复动作，
 * 避免前端从 toolId 或错误文本里反推任务状态。</p>
 *
 * @author zsg
 * @since 2026-07-04
 */
public final class ToolExecutionSummarySupport {

    private static final int MAX_MISSING_CAPABILITIES = 6;
    private static final Pattern TOOL_ID_AFTER_FAILURE_MARKER = Pattern.compile(
            "(?i)(?:工具未注册|工具没有注册|工具尚未注册|未找到工具|找不到工具|工具不存在"
                    + "|unknown tool|tool not found|no such tool|missing tool|unknown tool reference"
                    + "|unknown suggested tool|suggestedTools)\\s*[:：=\\-]?\\s*[`\"']?([a-z][a-z0-9_.-]{1,80})");
    private static final Pattern TOOL_ID_BEFORE_NOT_REGISTERED = Pattern.compile(
            "(?i)(?:工具|tool)\\s*[`\"']?([a-z][a-z0-9_.-]{1,80})[`\"']?\\s*(?:未注册|not registered)");

    private ToolExecutionSummarySupport() {}

    public static String executionKind(String toolId) {
        return isSkill(toolId) ? "SKILL" : "TOOL";
    }

    public static boolean isSkill(@Nullable String toolId) {
        return toolId != null && ("skill.load".equals(toolId) || toolId.startsWith("skill."));
    }

    public static boolean isSkillLoad(@Nullable String toolId) {
        return "skill.load".equals(toolId);
    }

    public static boolean isVisibleExecution(@Nullable String toolId, @Nullable String toolName) {
        return !isInternalExecution(toolId, toolName);
    }

    public static boolean isInternalExecution(@Nullable String toolId, @Nullable String toolName) {
        String id = normalize(toolId);
        if (isInternalExecutionId(id)) {
            return true;
        }
        return isInternalExecutionName(normalize(toolName));
    }

    private static String normalize(@Nullable String value) {
        return value != null ? value.strip().toLowerCase(Locale.ROOT) : "";
    }

    private static boolean hasInternalHumanizedName(String value) {
        String phrase = value.replaceAll("[._-]+", " ");
        return phrase.contains("tool search")
                || phrase.contains("tool discovery")
                || phrase.contains("capability check")
                || phrase.contains("capability assess")
                || phrase.contains("capability inspection")
                || phrase.contains("experience match")
                || phrase.contains("experience matching")
                || phrase.contains("decision signal")
                || phrase.contains("decision enhance")
                || phrase.contains("adaptive decision")
                || phrase.contains("context assemble")
                || phrase.contains("context assembly")
                || phrase.contains("context prepare")
                || phrase.contains("context preparation")
                || phrase.contains("context load")
                || phrase.contains("context loading");
    }

    private static boolean isInternalExecutionId(String id) {
        return id.equals("intent")
                || id.equals("chat.intent")
                || id.equals("intent.match")
                || id.equals("intent.matching")
                || id.equals("query.intent")
                || id.equals("router")
                || id.equals("chat.router")
                || id.equals("router.plan")
                || id.equals("router.planning")
                || id.equals("tool.search")
                || id.equals("tool.discovery")
                || id.equals("capability.assess")
                || id.equals("capability.check")
                || id.equals("capability.inspect")
                || id.equals("experience.match")
                || id.equals("experience.matching")
                || id.equals("memory.intent.match")
                || id.startsWith("memory.extract")
                || id.startsWith("memory.extraction")
                || id.startsWith("memory.consolid")
                || id.startsWith("memory.index")
                || id.startsWith("memory.embed")
                || id.startsWith("memory.vector")
                || id.startsWith("memory.govern")
                || id.startsWith("memory.revalid")
                || id.startsWith("memory.lifecycle")
                || id.startsWith("memory.profile.consolid")
                || id.equals("decision.signal")
                || id.equals("decision.enhance")
                || id.equals("adaptive.decision")
                || id.equals("context.prepare")
                || id.equals("context.assemble")
                || id.equals("context.load")
                || id.startsWith("intent.")
                || id.startsWith("router.")
                || id.startsWith("decision.")
                || id.startsWith("adaptive.")
                || id.startsWith("context.")
                || id.startsWith("capability.")
                || id.startsWith("tool.search.")
                || id.startsWith("tool.discovery.")
                || hasInternalHumanizedName(id)
                || id.contains("experience.match")
                || id.contains("adaptive.decision")
                || id.contains("decision.signal")
                || id.contains("context.prepare")
                || id.contains("context.assemble")
                || id.contains("意图")
                || id.contains("路由")
                || id.contains("经验匹配")
                || id.contains("历史经验")
                || id.contains("记忆抽取")
                || id.contains("记忆提取")
                || id.contains("记忆沉淀")
                || id.contains("记忆索引")
                || id.contains("记忆向量")
                || id.contains("记忆巩固")
                || id.contains("记忆治理")
                || id.contains("记忆复核")
                || id.contains("画像巩固")
                || id.contains("决策信号")
                || id.contains("决策增强")
                || id.contains("后台增强")
                || id.contains("调用核查")
                || id.contains("能力核查")
                || id.contains("工具核查")
                || id.contains("工具搜索")
                || id.contains("搜索工具");
    }

    private static boolean isInternalExecutionName(String name) {
        return name.isBlank()
                || hasInternalHumanizedName(name)
                || name.contains("意图")
                || name.contains("路由")
                || name.contains("调用核查")
                || name.contains("能力核查")
                || name.contains("工具核查")
                || name.contains("工具搜索")
                || name.contains("搜索工具")
                || name.contains("intent")
                || name.contains("router")
                || name.contains("experience.match")
                || name.contains("memory.extract")
                || name.contains("memory.extraction")
                || name.contains("memory.consolid")
                || name.contains("memory.index")
                || name.contains("memory.embed")
                || name.contains("memory.vector")
                || name.contains("memory.govern")
                || name.contains("memory.revalid")
                || name.contains("memory.lifecycle")
                || name.contains("adaptive.decision")
                || name.contains("decision.signal")
                || name.contains("context.prepare")
                || name.contains("context.assemble")
                || name.contains("经验匹配")
                || name.contains("历史经验")
                || name.contains("记忆抽取")
                || name.contains("记忆提取")
                || name.contains("记忆沉淀")
                || name.contains("记忆索引")
                || name.contains("记忆向量")
                || name.contains("记忆巩固")
                || name.contains("记忆治理")
                || name.contains("记忆复核")
                || name.contains("画像巩固")
                || name.contains("决策信号")
                || name.contains("决策增强")
                || name.contains("后台增强")
                || name.contains("加载上下文")
                || name.contains("准备上下文")
                || name.contains("上下文装配")
                || name.contains("准备对话上下文")
                || name.contains("读取上下文")
                || name.contains("上下文加载")
                || name.contains("loading context")
                || name.contains("preparing context")
                || name.contains("decision.")
                || name.contains("adaptive.")
                || name.contains("context.")
                || name.contains("capability.");
    }

    private static boolean startsWithAny(String toolId, String... prefixes) {
        for (String prefix : prefixes) {
            if (toolId.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isKnowledgeTool(String toolId) {
        return startsWithAny(toolId, "knowledge.", "kb.", "rag.", "document.", "pdf.", "vector.");
    }

    private static boolean isWorkflowTool(String toolId) {
        return startsWithAny(toolId, "workflow.", "task.", "scheduled.", "reminder.", "proactive.");
    }

    private static boolean isIntegrationTool(String toolId) {
        return startsWithAny(toolId, "mcp.", "connector.", "integration.");
    }

    private static boolean isAgentTool(String toolId) {
        return startsWithAny(toolId, "agent.", "a2a.", "remote.");
    }

    private static boolean isModelTool(String toolId) {
        return startsWithAny(toolId, "llm.", "model.", "embedding.", "vision.", "image.", "audio.", "stt.", "tts.");
    }

    @Nullable
    public static String executionAction(String toolId) {
        if (isSkillLoad(toolId)) {
            return "加载技能";
        }
        if (isSkill(toolId)) {
            return "执行技能";
        }
        if ("shell.exec".equals(toolId) || "code".equals(toolId)) {
            return "执行命令";
        }
        if ("file.read".equals(toolId)) {
            return "读取文件";
        }
        if ("file.write".equals(toolId)) {
            return "写入文件";
        }
        if (toolId.startsWith("file.")) {
            return "操作文件";
        }
        if ("web.search".equals(toolId)) {
            return "搜索资料";
        }
        if ("web.fetch".equals(toolId)) {
            return "读取网页";
        }
        if (toolId.startsWith("web.")) {
            return "访问网络资料";
        }
        if ("browser".equals(toolId) || toolId.startsWith("browser.")) {
            return "操作浏览器";
        }
        if ("memory".equals(toolId) || toolId.startsWith("memory.")) {
            return "处理记忆";
        }
        if (toolId.startsWith("git.")) {
            return "操作仓库";
        }
        if (isKnowledgeTool(toolId)) {
            return "处理资料";
        }
        if (isWorkflowTool(toolId)) {
            return "执行工作流";
        }
        if (isIntegrationTool(toolId)) {
            return "调用连接器";
        }
        if (isAgentTool(toolId)) {
            return "协作智能体";
        }
        if (isModelTool(toolId)) {
            return "整理回答";
        }
        return null;
    }

    public static String failureCategory(String toolId) {
        return failureCategory(toolId, null, null, null);
    }

    public static String failureCategory(String toolId,
                                         @Nullable String outputSummary,
                                         @Nullable String outputDetail,
                                         @Nullable Object output) {
        if (isCapabilityRegistryFailure(outputSummary, outputDetail, output)) {
            return "CAPABILITY";
        }
        if ("shell.exec".equals(toolId) || "code".equals(toolId)) {
            return "COMMAND";
        }
        if (toolId.startsWith("file.")) {
            return "FILE";
        }
        if ("browser".equals(toolId) || toolId.startsWith("browser.")) {
            return "BROWSER";
        }
        if (toolId.startsWith("web.")) {
            return "NETWORK";
        }
        if ("memory".equals(toolId) || toolId.startsWith("memory.")) {
            return "MEMORY";
        }
        if (isSkill(toolId)) {
            return "SKILL";
        }
        if (toolId.startsWith("git.")) {
            return "REPOSITORY";
        }
        if (isKnowledgeTool(toolId)) {
            return "KNOWLEDGE";
        }
        if (isWorkflowTool(toolId)) {
            return "WORKFLOW";
        }
        if (isIntegrationTool(toolId)) {
            return "INTEGRATION";
        }
        if (isAgentTool(toolId)) {
            return "AGENT";
        }
        if (isModelTool(toolId)) {
            return "MODEL";
        }
        return "UNKNOWN";
    }

    public static List<Map<String, Object>> missingCapabilities(String toolId,
                                                                @Nullable String outputSummary,
                                                                @Nullable String outputDetail,
                                                                @Nullable Object output) {
        if (!isCapabilityRegistryFailure(outputSummary, outputDetail, output)) {
            return List.of();
        }
        var ids = new LinkedHashSet<String>();
        addMissingCapabilityIds(ids, outputSummary);
        addMissingCapabilityIds(ids, outputDetail);
        addMissingCapabilityIds(ids, output);
        if (ids.isEmpty() && toolId != null && !toolId.isBlank()) {
            ids.add(cleanCapabilityId(toolId));
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        String source = isSkillReferenceFailure(outputSummary, outputDetail, output)
                ? "skill_reference"
                : "runtime_tool_call";
        String reason = "skill_reference".equals(source)
                ? "Skill 引用了当前不可用工具"
                : "工具未注册或当前不可用";
        var result = new ArrayList<Map<String, Object>>();
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                continue;
            }
            var item = new LinkedHashMap<String, Object>();
            item.put("kind", "TOOL");
            item.put("id", id);
            item.put("source", source);
            item.put("reason", reason);
            result.add(Map.copyOf(item));
            if (result.size() >= MAX_MISSING_CAPABILITIES) {
                break;
            }
        }
        return result.isEmpty() ? List.of() : List.copyOf(result);
    }

    @Nullable
    public static String failureCategoryDisplay(@Nullable String failureCategory) {
        if (failureCategory == null || failureCategory.isBlank()) {
            return null;
        }
        String category = failureCategory.strip().toUpperCase(Locale.ROOT);
        return failureCategoryLabel(category) + "/" + category;
    }

    private static String failureCategoryLabel(String category) {
        return switch (category) {
            case "CAPABILITY" -> "能力缺口";
            case "COMMAND" -> "命令执行";
            case "FILE" -> "文件操作";
            case "BROWSER" -> "浏览器操作";
            case "NETWORK" -> "网络访问";
            case "MEMORY" -> "记忆";
            case "SKILL" -> "技能";
            case "KNOWLEDGE" -> "资料库";
            case "WORKFLOW" -> "工作流";
            case "INTEGRATION" -> "集成连接";
            case "AGENT" -> "智能体协作";
            case "MODEL" -> "模型调用";
            case "REPOSITORY" -> "仓库操作";
            default -> "未知";
        };
    }

    public static String recoveryHint(String toolId) {
        return recoveryHint(toolId, failureCategory(toolId));
    }

    public static String recoveryHint(String toolId, @Nullable String failureCategory) {
        if ("CAPABILITY".equals(failureCategory)) {
            return "依赖的工具或技能当前不可用，可以在能力中心修复连接或调整 Skill 元数据后继续。";
        }
        if ("shell.exec".equals(toolId) || "code".equals(toolId)) {
            return "命令或代码没有完成，可以修正错误后继续执行。";
        }
        if (toolId.startsWith("file.")) {
            return "文件操作没有完成，检查路径或权限后继续执行。";
        }
        if ("browser".equals(toolId) || toolId.startsWith("browser.")) {
            return "浏览器操作没有完成，可以检查页面状态、登录或元素选择后继续。";
        }
        if (toolId.startsWith("web.")) {
            return "外部访问没有完成，可以重试或换一种资料来源继续。";
        }
        if ("memory".equals(toolId) || toolId.startsWith("memory.")) {
            return "记忆操作没有完成，可以调整条件后继续。";
        }
        if (isSkillLoad(toolId)) {
            return "技能加载没有完成，可以检查技能名称或依赖后继续。";
        }
        if (isSkill(toolId)) {
            return "技能执行没有完成，可以检查输入、依赖或技能步骤后继续。";
        }
        if (toolId.startsWith("git.")) {
            return "仓库操作没有完成，可以检查分支、权限或冲突后继续。";
        }
        if (isKnowledgeTool(toolId)) {
            return "资料处理没有完成，可以检查资料来源、索引或解析结果后继续。";
        }
        if (isWorkflowTool(toolId)) {
            return "工作流没有完成，可以检查当前节点状态后继续执行。";
        }
        if (isIntegrationTool(toolId)) {
            return "连接器调用没有完成，可以检查服务连接或授权后继续。";
        }
        if (isAgentTool(toolId)) {
            return "智能体协作没有完成，可以等待返回、检查委托目标或切换为本地处理。";
        }
        if (isModelTool(toolId)) {
            return "模型处理没有完成，可以检查模型配置、输入或重试策略后继续。";
        }
        return "这一步没有完成，可以让知微从失败处继续。";
    }

    public static List<Map<String, Object>> recoveryActions(String toolId) {
        return recoveryActions(toolId, failureCategory(toolId));
    }

    public static List<Map<String, Object>> recoveryActions(String toolId, @Nullable String failureCategory) {
        String category = failureCategory != null ? failureCategory : failureCategory(toolId);
        return List.of(
                Map.<String, Object>of(
                        "id", "resume",
                        "label", resumeActionLabel(category),
                        "mode", "resume",
                        "category", category,
                        "description", resumeActionDescription(category)),
                Map.<String, Object>of(
                        "id", "restart",
                        "label", "重新开始",
                        "mode", "restart",
                        "category", category,
                        "description", restartActionDescription(category))
        );
    }

    public static String resumeActionLabel(@Nullable String category) {
        return switch (category != null ? category : "UNKNOWN") {
            case "CAPABILITY" -> "修复能力后继续";
            case "FILE" -> "检查后继续";
            case "BROWSER" -> "检查页面后继续";
            case "NETWORK" -> "重试后继续";
            case "MEMORY" -> "调整记忆后继续";
            case "SKILL" -> "检查技能后继续";
            case "KNOWLEDGE" -> "调整资料后继续";
            case "WORKFLOW" -> "检查流程后继续";
            case "INTEGRATION" -> "检查连接后继续";
            case "AGENT" -> "检查协作后继续";
            case "MODEL" -> "检查模型后继续";
            case "REPOSITORY" -> "检查仓库后继续";
            case "UNKNOWN" -> "继续处理";
            default -> "修正后继续";
        };
    }

    public static String resumeActionDescription(@Nullable String category) {
        return switch (category != null ? category : "UNKNOWN") {
            case "CAPABILITY" -> "保留当前进度，修复缺失工具或技能连接后从失败步骤接上。";
            case "SKILL" -> "保留当前进度，检查技能后从失败步骤接上。";
            case "COMMAND" -> "保留已完成步骤，修正命令或代码错误后继续验证。";
            case "FILE" -> "保留已完成修改，检查路径或权限后继续文件操作。";
            case "BROWSER" -> "保留当前浏览器上下文，检查页面状态、登录或元素选择后继续。";
            case "NETWORK" -> "保留已获取资料，恢复访问或更换来源后继续。";
            case "MEMORY" -> "保留当前判断，调整记忆条件后继续沉淀或检索。";
            case "KNOWLEDGE" -> "保留已处理资料，调整来源、索引或解析后继续。";
            case "WORKFLOW" -> "保留流程状态，从失败节点继续推进。";
            case "INTEGRATION" -> "保留调用上下文，连接或授权恢复后继续。";
            case "AGENT" -> "保留协作状态，合并已有结果后继续本地处理。";
            case "MODEL" -> "保留输入和失败原因，调整模型配置后继续生成。";
            case "REPOSITORY" -> "保留仓库状态，处理分支、权限或冲突后继续。";
            default -> "保留已有进度，从卡住的位置继续处理。";
        };
    }

    public static String restartActionDescription(@Nullable String category) {
        return switch (category != null ? category : "UNKNOWN") {
            case "CAPABILITY" -> "保留能力缺失线索，修复后重新执行相关步骤。";
            case "SKILL" -> "保留技能失败线索，重新加载或执行失败技能步骤。";
            case "COMMAND" -> "保留失败输出，重新开始并优先修正命令错误。";
            case "FILE" -> "保留已完成修改线索，重新规划文件操作。";
            case "BROWSER" -> "保留页面状态线索，重新打开或重新操作目标页面。";
            case "NETWORK" -> "保留已获取资料线索，重新选择资料来源后执行。";
            case "MEMORY" -> "保留当前判断依据，重新执行记忆沉淀或检索。";
            case "KNOWLEDGE" -> "保留已处理资料线索，重新检索或重建处理步骤。";
            case "WORKFLOW" -> "保留流程状态，重新执行失败节点或整段流程。";
            case "INTEGRATION" -> "保留连接器失败原因，恢复连接后重新调用。";
            case "AGENT" -> "保留远程协作状态，重新委托或改为本地处理。";
            case "MODEL" -> "保留模型失败原因，调整配置后重新生成。";
            case "REPOSITORY" -> "保留仓库失败状态，重新执行并处理冲突。";
            default -> "保留失败线索，重新开始这一轮并优先修正卡点。";
        };
    }

    private static boolean isCapabilityRegistryFailure(@Nullable Object... values) {
        if (values == null || values.length == 0) {
            return false;
        }
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value).strip().toLowerCase(Locale.ROOT);
            if (text.isBlank()) {
                continue;
            }
            if (text.contains("工具未注册")
                    || text.contains("工具没有注册")
                    || text.contains("工具尚未注册")
                    || (text.contains("工具") && text.contains("未注册"))
                    || text.contains("未找到工具")
                    || text.contains("找不到工具")
                    || text.contains("工具不存在")
                    || text.contains("unknown tool")
                    || text.contains("tool not found")
                    || text.contains("no such tool")
                    || text.contains("missing tool")
                    || (text.contains("tool") && text.contains("not registered"))
                    || text.contains("unknown tool reference")
                    || text.contains("unknown suggested tool")
                    || text.contains("suggestedtools")) {
                return true;
            }
        }
        return false;
    }

    private static void addMissingCapabilityIds(LinkedHashSet<String> target, @Nullable Object value) {
        if (value == null || target.size() >= MAX_MISSING_CAPABILITIES) {
            return;
        }
        String text = String.valueOf(value).strip();
        if (text.isBlank()) {
            return;
        }
        addPatternMatches(target, TOOL_ID_AFTER_FAILURE_MARKER, text);
        addPatternMatches(target, TOOL_ID_BEFORE_NOT_REGISTERED, text);
    }

    private static void addPatternMatches(LinkedHashSet<String> target, Pattern pattern, String text) {
        var matcher = pattern.matcher(text);
        while (matcher.find() && target.size() < MAX_MISSING_CAPABILITIES) {
            String id = cleanCapabilityId(matcher.group(1));
            if (id != null && !id.isBlank() && !looksLikeNoiseCapabilityId(id)) {
                target.add(id);
            }
        }
    }

    @Nullable
    private static String cleanCapabilityId(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String text = value.strip();
        while (!text.isBlank() && "`'\"，。；;:：,)]}".indexOf(text.charAt(text.length() - 1)) >= 0) {
            text = text.substring(0, text.length() - 1).strip();
        }
        while (!text.isBlank() && "`'\"([{".indexOf(text.charAt(0)) >= 0) {
            text = text.substring(1).strip();
        }
        return text.isBlank() ? null : text;
    }

    private static boolean looksLikeNoiseCapabilityId(String value) {
        String text = value.toLowerCase(Locale.ROOT);
        return text.equals("unknown")
                || text.equals("tool")
                || text.equals("not")
                || text.equals("registered")
                || text.equals("suggestedtools");
    }

    private static boolean isSkillReferenceFailure(@Nullable Object... values) {
        if (values == null) {
            return false;
        }
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value).toLowerCase(Locale.ROOT);
            if (text.contains("suggestedtool")
                    || text.contains("suggested tool")
                    || text.contains("suggestedtools")
                    || text.contains("skill")
                    || text.contains("引用")
                    || text.contains("reference")) {
                return true;
            }
        }
        return false;
    }
}
