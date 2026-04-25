package com.lifepilot.agent.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * ReactStep 序列化工具 — 将 ReAct 步骤序列转为 JSON 友好的 Map 列表。
 *
 * <p>用于 SSE DONE 事件的 reactSteps 字段和数据库持久化。
 * inputSummary / outputSummary 输出自然语言摘要，outputDetail 保留详细结果。</p>
 *
 * @author zsg
 * @since 2026-03-19
 */
public final class ReactStepSerializer {

    static final int THOUGHT_MAX_LENGTH = 500;
    static final int SUMMARY_MAX_LENGTH = 120;
    static final int DETAIL_MAX_LENGTH = 2000;

    /** 产出文件的工具 ID 集合 — 成功时 output 中含 "path" 字段。 */
    private static final Set<String> FILE_PRODUCING_TOOL_IDS = Set.of(
            "file.write", "file.edit", "file.manage"
    );

    /** 含工作目录的工具 ID 集合 — 成功时 output 中含 "workingDirectory" 字段。 */
    private static final Set<String> WORKDIR_TOOL_IDS = Set.of(
            "shell.exec", "code.execute"
    );

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ReactStepSerializer() {}

    /**
     * 将 ReactStep 列表序列化为 JSON 友好的 Map 列表。
     *
     * @param steps ReAct 步骤序列
     * @return 序列化后的 Map 列表，每个 Map 包含 type、index 和对应字段
     */
    public static List<Map<String, Object>> serialize(List<ReactStep> steps) {
        if (steps == null || steps.isEmpty()) return List.of();
        var result = new ArrayList<Map<String, Object>>(steps.size());
        for (int i = 0; i < steps.size(); i++) {
            result.add(serializeStep(steps.get(i), i));
        }
        return List.copyOf(result);
    }

    /**
     * 将单个 ReactStep 序列化为 Map。
     *
     * @param step  ReAct 步骤
     * @param index 步骤索引
     * @return 序列化后的 Map
     */
    static Map<String, Object> serializeStep(ReactStep step, int index) {
        return switch (step) {
            case ReactStep.Progress(var content) -> Map.of(
                    "type", "PROGRESS",
                    "index", index,
                    "content", truncate(content, THOUGHT_MAX_LENGTH)
            );
            case ReactStep.Thought(var content) -> Map.of(
                    "type", "THOUGHT",
                    "index", index,
                    "content", truncate(content, THOUGHT_MAX_LENGTH)
            );
            case ReactStep.ToolCall toolCall -> {
                var map = new LinkedHashMap<String, Object>();
                map.put("type", "TOOL_CALL");
                map.put("index", index);
                map.put("toolId", toolCall.toolId());
                if (toolCall.toolName() != null) map.put("toolName", toolCall.toolName());
                if (toolCall.callId() != null) map.put("callId", toolCall.callId());
                map.put("inputSummary", summarizeInput(toolCall.toolId(), toolCall.inputJson()));
                map.put("latencyMs", toolCall.latencyMs());
                yield Map.copyOf(map);
            }
            case ReactStep.Observation observation -> {
                var map = new LinkedHashMap<String, Object>();
                map.put("type", "OBSERVATION");
                map.put("index", index);
                map.put("toolId", observation.toolId());
                if (observation.toolName() != null) map.put("toolName", observation.toolName());
                if (observation.callId() != null) map.put("callId", observation.callId());
                map.put("success", observation.success());
                map.put("outputSummary", summarizeOutput(observation.toolId(), observation.output(), observation.success()));
                map.put("tokensUsed", observation.tokensUsed());
                String detail = extractOutputDetail(observation.toolId(), observation.output(), observation.success());
                if (detail != null) map.put("outputDetail", detail);
                String filePath = extractGeneratedFilePath(observation);
                if (filePath != null) map.put("generatedFilePath", filePath);
                String workDir = extractWorkingDirectory(observation);
                if (workDir != null) map.put("workingDirectory", workDir);
                yield Map.copyOf(map);
            }
            case ReactStep.Answer(var content) -> Map.of(
                    "type", "ANSWER",
                    "index", index,
                    "content", truncate(content, THOUGHT_MAX_LENGTH)
            );
            case ReactStep.Suspend(var reason, var suspendedAt, var stepIdx) -> Map.of(
                    "type", "SUSPEND",
                    "index", index,
                    "reason", reason.toString(),
                    "suspendedAt", suspendedAt.toString()
            );
            case ReactStep.Resume(var payload, var resumedAt, var duration) -> Map.of(
                    "type", "RESUME",
                    "index", index,
                    "resumedAt", resumedAt.toString(),
                    "suspendDurationMs", duration.toMillis()
            );
            case ReactStep.Reflect(var content, var trigger) -> Map.of(
                    "type", "REFLECT",
                    "index", index,
                    "content", truncate(content, THOUGHT_MAX_LENGTH),
                    "trigger", trigger.name()
            );
        };
    }

    /**
     * 从文件工具的成功输出中提取生成文件的绝对路径。
     *
     * @param observation 工具观察步骤
     * @return 文件路径，非文件工具或提取失败时返回 null
     */
    @org.springframework.lang.Nullable
    static String extractGeneratedFilePath(ReactStep.Observation observation) {
        return extractJsonField(observation, FILE_PRODUCING_TOOL_IDS, "path");
    }

    /**
     * 从 Shell / 代码执行工具的成功输出中提取工作目录。
     *
     * @param observation 工具观察步骤
     * @return 工作目录路径，非相关工具或提取失败时返回 null
     */
    /**
     * 从 Shell / 代码执行工具的输出中提取工作目录（成功和失败均提取，方便调试失败命令）。
     */
    @org.springframework.lang.Nullable
    static String extractWorkingDirectory(ReactStep.Observation observation) {
        if (!WORKDIR_TOOL_IDS.contains(observation.toolId())) return null;
        String output = observation.output();
        if (output == null || output.isBlank()) return null;
        try {
            JsonNode root = MAPPER.readTree(output);
            JsonNode node = root.get("workingDirectory");
            if (node != null && node.isTextual()) {
                return node.asText();
            }
        } catch (JsonProcessingException ignored) {
            // 输出非 JSON 格式，跳过提取
        }
        return null;
    }

    /**
     * 从工具观察的 JSON 输出中提取指定字段的文本值。
     *
     * @param observation 工具观察步骤
     * @param toolIds     目标工具 ID 集合
     * @param fieldName   要提取的 JSON 字段名
     * @return 字段文本值，不匹配或提取失败时返回 null
     */
    @org.springframework.lang.Nullable
    private static String extractJsonField(ReactStep.Observation observation,
                                           Set<String> toolIds, String fieldName) {
        if (!observation.success()) return null;
        if (!toolIds.contains(observation.toolId())) return null;
        String output = observation.output();
        if (output == null || output.isBlank()) return null;
        try {
            JsonNode root = MAPPER.readTree(output);
            JsonNode node = root.get(fieldName);
            if (node != null && node.isTextual()) {
                return node.asText();
            }
        } catch (JsonProcessingException ignored) {
            // 输出非 JSON 格式，跳过提取
        }
        return null;
    }

    // ─── 自然语言摘要生成 ───

    /**
     * 工具输入 JSON → 自然语言标签（≤120 字符）。
     */
    static String summarizeInput(String toolId, String inputJson) {
        JsonNode root = safeParse(inputJson);
        return switch (toolId) {
            case "web.search", "knowledge.search" -> {
                String q = textField(root, "query");
                yield q != null ? "搜索「" + truncate(q, 50) + "」" : "搜索";
            }
            case "web.fetch" -> {
                String url = textField(root, "url");
                if (url != null) {
                    try {
                        String host = URI.create(url).getHost();
                        yield "抓取 " + (host != null ? host : truncate(url, 50));
                    } catch (Exception e) { yield "抓取 " + truncate(url, 50); }
                }
                yield "抓取网页";
            }
            case "file.read" -> {
                String path = textField(root, "path");
                if (path == null) path = textField(root, "skill");
                yield path != null ? "读取 " + truncate(path, 60) : "读取文件";
            }
            case "file.write" -> {
                String path = textField(root, "path");
                yield path != null ? "写入 " + truncate(path, 60) : "写入文件";
            }
            case "file.edit" -> {
                String path = textField(root, "path");
                int ops = arrayLength(root, "operations");
                String suffix = ops > 0 ? "（" + ops + " 处修改）" : "";
                yield path != null ? "编辑 " + truncate(path, 50) + suffix : "编辑文件" + suffix;
            }
            case "file.list" -> {
                String action = textField(root, "action");
                String path = textField(root, "path");
                yield switch (action != null ? action : "") {
                    case "search" -> "搜索 " + (path != null ? truncate(path, 40) : "文件");
                    case "info" -> "查询 " + (path != null ? truncate(path, 40) : "文件") + " 信息";
                    default -> "列出 " + (path != null ? truncate(path, 40) : "文件");
                };
            }
            case "file.manage" -> {
                String action = textField(root, "action");
                String src = textField(root, "source");
                String dest = textField(root, "destination");
                String target = src != null ? truncate(src, 40) : (dest != null ? truncate(dest, 40) : "");
                yield (action != null ? action : "操作") + (target.isEmpty() ? "" : " " + target);
            }
            case "file.undo" -> "撤销编辑";
            case "file.redo" -> "重做编辑";
            case "file.diff" -> "对比文件";
            case "shell.exec" -> {
                String cmd = textField(root, "command");
                yield cmd != null ? "执行 `" + truncate(cmd, 50) + "`" : "执行命令";
            }
            case "code.execute" -> {
                String lang = textField(root, "language");
                yield lang != null ? "执行 " + lang + " 代码" : "执行代码";
            }
            case "memory" -> summarizeActionTool(root, Map.of(
                    "search", "搜索记忆", "recall", "回忆", "create", "创建记忆",
                    "update", "更新记忆", "delete", "删除记忆", "tag", "标记记忆",
                    "query-at-time", "查询历史记忆", "search-experience", "搜索经验"
            ), "query", "name");
            case "cron" -> summarizeActionTool(root, Map.of(
                    "create", "创建定时任务", "list", "列出定时任务",
                    "update", "更新任务", "remove", "删除任务"
            ), "name", "taskId");
            case "git.query" -> {
                String action = textField(root, "action");
                yield switch (action != null ? action : "") {
                    case "status" -> "查看 Git 状态";
                    case "diff" -> "查看 Git 差异";
                    case "log" -> "查看 Git 日志";
                    case "blame" -> "查看提交归属";
                    default -> "Git 查询";
                };
            }
            case "git.mutate" -> {
                String action = textField(root, "action");
                String msg = textField(root, "message");
                yield switch (action != null ? action : "") {
                    case "commit" -> msg != null ? "提交「" + truncate(msg, 30) + "」" : "提交";
                    case "stash" -> "暂存更改";
                    case "branch" -> "创建分支";
                    default -> "Git 变更";
                };
            }
            case "browser" -> {
                String action = textField(root, "action");
                yield switch (action != null ? action : "") {
                    case "navigate" -> {
                        String url = textField(root, "url");
                        if (url != null) {
                            try {
                                String host = URI.create(url).getHost();
                                yield "访问 " + (host != null ? host : truncate(url, 40));
                            } catch (Exception e) { yield "访问 " + truncate(url, 40); }
                        }
                        yield "访问页面";
                    }
                    case "screenshot" -> "截取页面截图";
                    case "click" -> "点击 " + truncate(textFieldOr(root, "selector", "元素"), 30);
                    case "input" -> "输入到 " + truncate(textFieldOr(root, "selector", "元素"), 30);
                    case "evaluate" -> "执行 JavaScript";
                    case "accessibility" -> "获取无障碍树";
                    default -> action != null ? "浏览器 " + action : "浏览器操作";
                };
            }
            case "notify.send_message" -> {
                String title = textField(root, "title");
                yield title != null ? "通知「" + truncate(title, 30) + "」" : "推送通知";
            }
            default -> {
                // 通用兜底：尝试常见字段
                String q = textField(root, "query");
                if (q != null) yield "搜索「" + truncate(q, 50) + "」";
                String url = textField(root, "url");
                if (url != null) {
                    try {
                        String host = URI.create(url).getHost();
                        yield host != null ? host : truncate(url, 50);
                    } catch (Exception e) { yield truncate(url, 50); }
                }
                String path = textField(root, "path");
                if (path != null) yield truncate(path, 60);
                String name = textField(root, "name");
                if (name != null) yield truncate(name, 60);
                yield truncate(inputJson, SUMMARY_MAX_LENGTH);
            }
        };
    }

    /**
     * 工具输出 → 自然语言标签（≤120 字符）。
     */
    static String summarizeOutput(String toolId, String output, boolean success) {
        if (!success) {
            JsonNode root = safeParse(output);
            String err = textField(root, "error");
            if (err == null) err = textField(root, "message");
            if (err != null) return truncate(err, SUMMARY_MAX_LENGTH);
            if (output != null && !output.isBlank()) {
                return truncate(output.lines().findFirst().orElse(output), SUMMARY_MAX_LENGTH);
            }
            return "执行失败";
        }
        JsonNode root = safeParse(output);
        return switch (toolId) {
            case "web.search", "knowledge.search" -> {
                int count = arrayLength(root, "results");
                if (count >= 0) yield "找到 " + count + " 条结果";
                yield intFieldLabel(root, "count", "共 %d 条", "搜索完成");
            }
            case "web.fetch" -> {
                String content = textField(root, "content");
                yield content != null ? "获取了 " + content.length() + " 字符内容" : "获取成功";
            }
            case "file.read" -> intFieldLabel(root, "lineCount", "读取了 %d 行", "读取完成");
            case "file.write" -> {
                String path = textField(root, "path");
                yield path != null ? "已写入 " + truncate(path, 60) : "写入成功";
            }
            case "file.edit" -> intFieldLabel(root, "operationsApplied", "已修改 %d 处", "编辑完成");
            case "file.list" -> intFieldLabel(root, "count", "%d 个条目", "列出完成");
            case "file.manage", "file.undo", "file.redo", "file.diff" -> "操作成功";
            case "shell.exec" -> intFieldLabel(root, "exitCode", "退出码 %d", "执行完成");
            case "code.execute" -> intFieldLabel(root, "exitCode", "执行完成（退出码 %d）", "执行完成");
            case "memory" -> {
                int count = arrayLength(root, "results");
                yield count >= 0 ? "找到 " + count + " 条记忆" : "操作成功";
            }
            case "cron" -> {
                String name = textField(root, "name");
                yield name != null ? "任务「" + truncate(name, 30) + "」" : "操作成功";
            }
            case "git.query" -> "查询完成";
            case "git.mutate" -> "操作成功";
            case "browser" -> "操作成功";
            case "notify.send_message" -> "通知已发送";
            default -> "操作成功";
        };
    }

    /**
     * 工具输出 → 详细结果内容（≤2000 字符），供面板详情展示。
     *
     * @return 详情文本，无有效内容时返回 null
     */
    @org.springframework.lang.Nullable
    static String extractOutputDetail(String toolId, String output, boolean success) {
        if (output == null || output.isBlank()) return null;
        JsonNode root = safeParse(output);

        // 失败：完整错误信息
        if (!success) {
            String err = textField(root, "error");
            if (err == null) err = textField(root, "message");
            return err != null ? truncate(err, DETAIL_MAX_LENGTH) : truncate(output, DETAIL_MAX_LENGTH);
        }

        if (root == null) {
            // 纯文本输出
            return output.length() > 10 ? truncate(output, DETAIL_MAX_LENGTH) : null;
        }

        // 搜索结果列表
        JsonNode results = firstArray(root, "results", "items");
        if (results != null && !results.isEmpty()) {
            var sj = new StringJoiner("\n");
            int limit = Math.min(results.size(), 15);
            for (int i = 0; i < limit; i++) {
                JsonNode item = results.get(i);
                String title = firstText(item, "title", "name", "id");
                String url = firstText(item, "url", "link");
                String snippet = firstText(item, "snippet", "description", "summary");
                var line = new StringBuilder();
                line.append(i + 1).append(". ").append(title != null ? title : "");
                if (url != null) line.append("\n   ").append(url);
                if (snippet != null) line.append("\n   ").append(truncate(snippet, 100));
                sj.add(line.toString());
            }
            if (results.size() > limit) sj.add("... 共 " + results.size() + " 条");
            return sj.toString();
        }

        // 文件列表
        JsonNode entries = root.get("entries");
        if (entries != null && entries.isArray() && !entries.isEmpty()) {
            var sj = new StringJoiner("\n");
            int limit = Math.min(entries.size(), 30);
            for (int i = 0; i < limit; i++) {
                JsonNode e = entries.get(i);
                String name = firstText(e, "name");
                String type = firstText(e, "type");
                sj.add(("DIR".equals(type) ? "\uD83D\uDCC1 " : "\uD83D\uDCC4 ") + (name != null ? name : ""));
            }
            if (entries.size() > limit) sj.add("... 共 " + entries.size() + " 个");
            return sj.toString();
        }

        // commits
        JsonNode commits = root.get("commits");
        if (commits != null && commits.isArray() && !commits.isEmpty()) {
            var sj = new StringJoiner("\n");
            for (int i = 0; i < Math.min(commits.size(), 15); i++) {
                JsonNode c = commits.get(i);
                String hash = firstText(c, "hash");
                String msg = firstText(c, "message");
                sj.add((hash != null ? truncate(hash, 8) : "") + " " + (msg != null ? msg : ""));
            }
            return sj.toString();
        }

        // 文件/网页/命令输出内容
        String content = firstText(root, "content", "stdout", "result");
        if (content != null && content.length() > 10) return truncate(content, DETAIL_MAX_LENGTH);

        // stderr（代码执行）
        String stderr = textField(root, "stderr");
        if (stderr != null && !stderr.isBlank()) return truncate(stderr, DETAIL_MAX_LENGTH);

        return null;
    }

    // ─── 辅助方法 ───

    /** 安全解析 JSON，失败返回 null */
    @org.springframework.lang.Nullable
    private static JsonNode safeParse(String json) {
        if (json == null || json.isBlank()) return null;
        String t = json.trim();
        if (!t.startsWith("{") && !t.startsWith("[")) return null;
        try { return MAPPER.readTree(t); }
        catch (JsonProcessingException e) { return null; }
    }

    /** 从 JsonNode 提取文本字段，不存在或非文本返回 null */
    @org.springframework.lang.Nullable
    private static String textField(JsonNode root, String field) {
        if (root == null) return null;
        JsonNode node = root.get(field);
        return node != null && node.isTextual() ? node.asText() : null;
    }

    /** 提取文本字段，不存在时返回默认值 */
    private static String textFieldOr(JsonNode root, String field, String defaultValue) {
        String v = textField(root, field);
        return v != null ? v : defaultValue;
    }

    /** 从多个候选字段名中返回第一个存在的文本值 */
    @org.springframework.lang.Nullable
    private static String firstText(JsonNode root, String... fields) {
        if (root == null) return null;
        for (String f : fields) {
            String v = textField(root, f);
            if (v != null) return v;
        }
        return null;
    }

    /** 从多个候选字段名中返回第一个存在的数组节点 */
    @org.springframework.lang.Nullable
    private static JsonNode firstArray(JsonNode root, String... fields) {
        if (root == null) return null;
        for (String f : fields) {
            JsonNode node = root.get(f);
            if (node != null && node.isArray()) return node;
        }
        return null;
    }

    /** 获取数组字段长度，不存在返回 -1 */
    private static int arrayLength(JsonNode root, String field) {
        if (root == null) return -1;
        JsonNode node = root.get(field);
        return node != null && node.isArray() ? node.size() : -1;
    }

    /** 获取整数字段并格式化，不存在时返回默认标签 */
    private static String intFieldLabel(JsonNode root, String field, String format, String fallback) {
        if (root == null) return fallback;
        JsonNode node = root.get(field);
        if (node != null && node.isNumber()) return String.format(format, node.asInt());
        return fallback;
    }

    /** action 分派型工具的通用摘要：action 标签 +「名称」 */
    private static String summarizeActionTool(JsonNode root, Map<String, String> actionLabels,
                                               String... nameFields) {
        String action = textField(root, "action");
        String label = action != null ? actionLabels.getOrDefault(action, action) : "操作";
        String name = firstText(root, nameFields);
        return name != null ? label + "「" + truncate(name, 30) + "」" : label;
    }

    static String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "…";
    }

    /**
     * 将 ReactStep 列表序列化为 JSON 字符串（用于数据库持久化）。
     *
     * @param steps        ReAct 步骤序列
     * @param objectMapper Jackson ObjectMapper
     * @return JSON 字符串，步骤为空时返回 null
     */
    @org.springframework.lang.Nullable
    public static String serializeToJson(List<ReactStep> steps, ObjectMapper objectMapper) {
        var maps = serialize(steps);
        if (maps.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(maps);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
