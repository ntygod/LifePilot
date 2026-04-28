package com.lifepilot.meta.infra.transcript;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.conversation.transcript.SessionTranscriptRepository;
import com.lifepilot.conversation.transcript.SessionTranscriptRepository.SessionTranscriptEntryRow;
import com.lifepilot.conversation.transcript.TranscriptEntryType;
import com.lifepilot.tool.model.ToolContextKeys;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 按 entryId 或 callId 取单条 transcript 条目的完整 outputJson 原文。
 *
 * <p>配合 {@link TranscriptSearchToolExecutor} 使用：search 返回 preview + entryId / callId
 * 索引，LLM 决定有用就 get 取完整。</p>
 *
 * <p>参数（{@code entryId} 与 {@code callId} 二选一，前者优先）：
 * <ul>
 *   <li>{@code entryId}：transcript_entries 表的主键</li>
 *   <li>{@code callId}：tool_call 的 callId（同一会话内唯一）</li>
 *   <li>{@code maxChars}（可选）：截断长度，默认 30000，超出截断尾部</li>
 * </ul>
 *
 * <p>返回：{@code entryId / toolId / callId / success / outputJson / createdAt / truncated}，
 * 仅返回 {@code TOOL_RESULT} 类型的条目（拒绝读取 user/assistant 等其他类型条目以防误用）。</p>
 *
 * @author zsg
 * @since 2026-04-28
 */
public final class TranscriptGetToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(TranscriptGetToolExecutor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_MAX_CHARS = 30_000;

    private final SessionTranscriptRepository transcriptRepository;

    public TranscriptGetToolExecutor(SessionTranscriptRepository transcriptRepository) {
        this.transcriptRepository = transcriptRepository;
    }

    public ToolResult execute(ToolInput input) {
        Optional<String> sessionIdOpt = input.getContextValue(ToolContextKeys.SESSION_ID, String.class);
        if (sessionIdOpt.isEmpty()) {
            return ToolResult.error("缺少会话上下文，无法读取 transcript（context.sessionId 缺失）");
        }
        String sessionId = sessionIdOpt.get();

        String entryId = input.getOptionalParam("entryId", String.class).orElse(null);
        String callId = input.getOptionalParam("callId", String.class).orElse(null);
        if ((entryId == null || entryId.isBlank()) && (callId == null || callId.isBlank())) {
            return ToolResult.error("需要提供 entryId 或 callId 之一");
        }
        int maxChars = input.getOptionalParam("maxChars", Number.class)
                .map(Number::intValue)
                .map(v -> Math.max(100, v))
                .orElse(DEFAULT_MAX_CHARS);

        SessionTranscriptEntryRow row;
        try {
            if (entryId != null && !entryId.isBlank()) {
                row = transcriptRepository.findById(entryId).orElse(null);
                if (row != null && !row.sessionId().equals(sessionId)) {
                    return ToolResult.error("entryId 不属于当前会话: " + entryId);
                }
            } else {
                row = findByCallId(sessionId, callId);
            }
        } catch (Exception e) {
            log.warn("transcript.get 读取失败: sessionId={}, error={}", sessionId, e.getMessage());
            return ToolResult.error("读取 transcript 失败: " + e.getMessage());
        }

        if (row == null) {
            return ToolResult.error("transcript 条目不存在: "
                    + (entryId != null ? "entryId=" + entryId : "callId=" + callId));
        }
        if (!TranscriptEntryType.TOOL_RESULT.value().equals(row.entryType())) {
            return ToolResult.error("仅支持读取 tool_result 类型的条目，当前 entryType=" + row.entryType());
        }

        Map<String, Object> payload = parsePayload(row.payloadJson());
        if (payload == null) {
            return ToolResult.error("payload_json 解析失败: entryId=" + row.id());
        }
        String outputJson = stringOrNull(payload.get("outputJson"));
        boolean truncated = false;
        if (outputJson != null && outputJson.length() > maxChars) {
            outputJson = outputJson.substring(0, maxChars)
                    + "\n...[内容已截断，maxChars=" + maxChars + "，可调大参数取更多]";
            truncated = true;
        }

        var data = new LinkedHashMap<String, Object>();
        data.put("entryId", row.id());
        data.put("toolId", payload.get("toolId"));
        if (payload.get("callId") != null) data.put("callId", payload.get("callId"));
        data.put("success", payload.get("success"));
        data.put("createdAt", row.createdAt().toString());
        data.put("outputJson", outputJson);
        data.put("truncated", truncated);
        return ToolResult.success(Map.copyOf(data));
    }

    private SessionTranscriptEntryRow findByCallId(String sessionId, String callId) {
        var rows = transcriptRepository.findBySessionId(sessionId);
        // 倒序找：同一 callId 在工具重试场景下可能出现多条，最新的（最后一次）应优先
        for (int i = rows.size() - 1; i >= 0; i--) {
            SessionTranscriptEntryRow row = rows.get(i);
            if (!TranscriptEntryType.TOOL_RESULT.value().equals(row.entryType())) continue;
            Map<String, Object> payload = parsePayload(row.payloadJson());
            if (payload == null) continue;
            if (callId.equals(stringOrNull(payload.get("callId")))) {
                return row;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parsePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isEmpty()) return null;
        try {
            Object parsed = MAPPER.readValue(payloadJson, Map.class);
            return parsed instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String stringOrNull(Object value) {
        return value instanceof String s ? s : null;
    }
}
