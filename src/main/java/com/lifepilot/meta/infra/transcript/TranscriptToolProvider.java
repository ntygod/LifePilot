package com.lifepilot.meta.infra.transcript;

import com.lifepilot.conversation.transcript.SessionTranscriptRepository;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.model.ToolSchedulingMode;
import com.lifepilot.tool.schema.JsonSchema;
import com.lifepilot.tool.semantics.ToolExecutionSemantics;

import java.util.List;
import java.util.Map;

/**
 * Transcript 检索工具提供者 —— 让 LLM 主动取历史工具调用的完整原文，避免重跑。
 *
 * <p>提供两个工具：
 * <ul>
 *   <li>{@code memory} —— 按 toolId / callId / keyword / success / since 检索 tool_result，
 *       返回轻量 preview 列表</li>
 *   <li>{@code memory} —— 按 entryId / callId 取单条 tool_result 的完整 outputJson</li>
 * </ul>
 *
 * <p>SessionTranscriptRepository 必须由 Spring 注入；Web 上下文未启用时本 provider
 * 不会被装配（{@link com.lifepilot.meta.infra.InfraToolProvider} 检查 nullable 依赖跳过）。</p>
 *
 * @author zsg
 * @since 2026-04-28
 */
public final class TranscriptToolProvider {

    private final SessionTranscriptRepository transcriptRepository;

    public TranscriptToolProvider(SessionTranscriptRepository transcriptRepository) {
        this.transcriptRepository = transcriptRepository;
    }

    public List<BuiltinTool> buildTranscriptTools() {
        return List.of(
                buildSearchTool(new TranscriptSearchToolExecutor(transcriptRepository)),
                buildGetTool(new TranscriptGetToolExecutor(transcriptRepository))
        );
    }

    private BuiltinTool buildSearchTool(TranscriptSearchToolExecutor executor) {
        var props = new java.util.LinkedHashMap<String, Object>();
        props.put("toolId", Map.of("type", "string",
                "description", "按工具 ID 精确过滤（如 \"web.search\" / \"file.read\"）。"));
        props.put("callId", Map.of("type", "string",
                "description", "按 tool_call 的 callId 精确过滤（压缩摘要里方括号给的索引）。"));
        props.put("keyword", Map.of("type", "string",
                "description", "在 tool 输出 JSON 中做不区分大小写的子串匹配（适合搜索关键字 / URL / 错误信息）。"));
        props.put("success", Map.of("type", "boolean",
                "description", "true 仅返回成功调用，false 仅返回失败调用，不传不过滤。"));
        props.put("since", Map.of("type", "string",
                "description", "ISO 8601 时间戳，仅返回该时刻之后的条目（如 \"2026-04-28T08:00:00Z\"）。"));
        props.put("limit", Map.of("type", "integer",
                "description", "返回上限，默认 5，最大 50。"));
        props.put("maxPreviewChars", Map.of("type", "integer",
                "description", "单条 outputJson preview 截断长度，默认 200。超长部分需调 transcript.get 取完整。"));

        return BuiltinTool.builder()
                .id("transcript.search")
                .category(ToolCategory.PERCEPTION)
                .name("搜索历史工具调用")
                .description(
                        "在当前会话 transcript 中检索过往工具调用结果（如 web.search / file.read 的旧 result）。"
                                + "用于压缩摘要后需要回看完整原文的场景，避免重新调用工具浪费 token。"
                                + "返回 entries 列表含 preview 和 callId，需要完整原文用 transcript.get。")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "properties", props
                )))
                .riskLevel(RiskLevel.LOW)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.READ_FILE,
                        ToolSchedulingMode.PARALLEL_SAFE,
                        com.lifepilot.tool.semantics.ToolScopeResolvers.none()
                ))
                .tags(List.of("transcript", "历史", "search", "查询", "工具结果", "过往", "回看"))
                .executor(executor::execute)
                .build();
    }

    private BuiltinTool buildGetTool(TranscriptGetToolExecutor executor) {
        var props = new java.util.LinkedHashMap<String, Object>();
        props.put("entryId", Map.of("type", "string",
                "description", "transcript 条目主键（与 callId 二选一）。从 transcript.search 返回的 entries[i].entryId 取得。"));
        props.put("callId", Map.of("type", "string",
                "description", "tool_call 的 callId（与 entryId 二选一）。压缩摘要里方括号内的索引。"));
        props.put("maxChars", Map.of("type", "integer",
                "description", "截断长度，默认 30000，超出截断尾部并标记 truncated=true。"));

        return BuiltinTool.builder()
                .id("transcript.get")
                .category(ToolCategory.PERCEPTION)
                .name("取历史工具结果原文")
                .description(
                        "按 entryId 或 callId 读取过往一次工具调用的完整 outputJson。"
                                + "和 transcript.search 配合使用：先 search 拿索引，再 get 取原文。")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "properties", props
                )))
                .riskLevel(RiskLevel.LOW)
                .executionSemantics(ToolExecutionSemantics.of(
                        PermissionActionType.READ_FILE,
                        ToolSchedulingMode.PARALLEL_SAFE,
                        com.lifepilot.tool.semantics.ToolScopeResolvers.none()
                ))
                .tags(List.of("transcript", "get", "原文", "完整", "工具结果", "history"))
                .executor(executor::execute)
                .build();
    }
}
