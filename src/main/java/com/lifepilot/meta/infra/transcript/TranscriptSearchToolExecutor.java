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

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 历史 transcript 检索工具 —— 让 LLM 主动取压缩前的工具调用结果，避免重跑。
 *
 * <p>问题背景：CompactionEngine 在上下文窗口占用过高时把早期 transcript 条目压缩为摘要，
 * 摘要里看不到 tool_result 的完整 outputJson。如果当前轮需要前面某次工具调用的原始结果
 * （比如要引用 web.search 拿到的某个 URL），LLM 只能重新调用同一工具浪费 token，
 * 或基于摘要瞎猜导致回答不准。</p>
 *
 * <p>本工具暴露 transcript 表的检索能力给 LLM：底层数据从未被真正删除（只是从模型当前上下文
 * 移出），按 toolId / callId / 关键字 / 成功状态等条件检索后返回轻量 preview，LLM 决定是否
 * 调用 {@code transcript.get} 取完整原文。</p>
 *
 * <p>参数：
 * <ul>
 *   <li>{@code toolId}（可选）：精确匹配工具 ID（如 {@code "web.search"} / {@code "file.read"}）</li>
 *   <li>{@code callId}（可选）：精确匹配 tool_call 的 callId（如压缩摘要里给了索引）</li>
 *   <li>{@code keyword}（可选）：在 outputJson 中做不区分大小写的子串匹配</li>
 *   <li>{@code success}（可选）：true 仅成功 / false 仅失败 / null 不过滤</li>
 *   <li>{@code since}（可选）：ISO 8601 时间戳，仅返回此时刻之后的条目</li>
 *   <li>{@code limit}（可选）：返回上限，默认 5，最大 50</li>
 *   <li>{@code maxPreviewChars}（可选）：单条 outputJson preview 截断长度，默认 200</li>
 * </ul>
 *
 * <p>返回：{@code entries[]}，每条含 entryId / callId / toolId / success / createdAt /
 * outputPreview / outputTotalChars，按时间倒序。{@code outputPreview} 超过原文总长度时
 * 自动追加 "..." 提示需要 transcript.get 取完整。</p>
 *
 * @author zsg
 * @since 2026-04-28
 */
public final class TranscriptSearchToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(TranscriptSearchToolExecutor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 50;
    private static final int DEFAULT_PREVIEW_CHARS = 200;

    private final SessionTranscriptRepository transcriptRepository;

    public TranscriptSearchToolExecutor(SessionTranscriptRepository transcriptRepository) {
        this.transcriptRepository = transcriptRepository;
    }

    public ToolResult execute(ToolInput input) {
        Optional<String> sessionIdOpt = input.getContextValue(ToolContextKeys.SESSION_ID, String.class);
        if (sessionIdOpt.isEmpty()) {
            return ToolResult.error("缺少会话上下文，无法检索 transcript（context.sessionId 缺失）");
        }
        String sessionId = sessionIdOpt.get();

        String toolIdFilter = input.getOptionalParam("toolId", String.class).orElse(null);
        String callIdFilter = input.getOptionalParam("callId", String.class).orElse(null);
        String keywordFilter = input.getOptionalParam("keyword", String.class)
                .map(String::toLowerCase)
                .filter(s -> !s.isBlank())
                .orElse(null);
        Boolean successFilter = input.getOptionalParam("success", Boolean.class).orElse(null);
        Instant sinceFilter = input.getOptionalParam("since", String.class)
                .flatMap(s -> {
                    try {
                        return Optional.of(Instant.parse(s));
                    } catch (DateTimeParseException e) {
                        log.warn("transcript.search since 参数格式错误，忽略: {}", s);
                        return Optional.empty();
                    }
                })
                .orElse(null);
        int limit = input.getOptionalParam("limit", Number.class)
                .map(Number::intValue)
                .map(v -> Math.max(1, Math.min(MAX_LIMIT, v)))
                .orElse(DEFAULT_LIMIT);
        int maxPreviewChars = input.getOptionalParam("maxPreviewChars", Number.class)
                .map(Number::intValue)
                .map(v -> Math.max(50, v))
                .orElse(DEFAULT_PREVIEW_CHARS);

        List<SessionTranscriptEntryRow> rows;
        try {
            rows = transcriptRepository.findBySessionId(sessionId);
        } catch (Exception e) {
            log.warn("transcript.search 检索失败: sessionId={}, error={}", sessionId, e.getMessage());
            return ToolResult.error("检索 transcript 失败: " + e.getMessage());
        }

        // 倒序（最新先返）
        var iter = rows.listIterator(rows.size());
        var matches = new ArrayList<Map<String, Object>>();
        while (iter.hasPrevious() && matches.size() < limit) {
            SessionTranscriptEntryRow row = iter.previous();
            if (!TranscriptEntryType.TOOL_RESULT.value().equals(row.entryType())) continue;
            if (sinceFilter != null && row.createdAt().isBefore(sinceFilter)) continue;

            Map<String, Object> payload = parsePayload(row.payloadJson());
            if (payload == null) continue;

            String toolId = stringOrNull(payload.get("toolId"));
            String callId = stringOrNull(payload.get("callId"));
            Boolean success = payload.get("success") instanceof Boolean b ? b : null;
            String outputJson = stringOrNull(payload.get("outputJson"));

            if (toolIdFilter != null && !toolIdFilter.equals(toolId)) continue;
            if (callIdFilter != null && !callIdFilter.equals(callId)) continue;
            if (successFilter != null && !successFilter.equals(success)) continue;
            if (keywordFilter != null
                    && (outputJson == null || !outputJson.toLowerCase().contains(keywordFilter))) continue;

            matches.add(buildMatchEntry(row, toolId, callId, success, outputJson, maxPreviewChars));
        }

        var data = new LinkedHashMap<String, Object>();
        data.put("entries", List.copyOf(matches));
        data.put("totalReturned", matches.size());
        data.put("limit", limit);
        return ToolResult.success(Map.copyOf(data));
    }

    private Map<String, Object> buildMatchEntry(SessionTranscriptEntryRow row,
                                                String toolId,
                                                String callId,
                                                Boolean success,
                                                String outputJson,
                                                int maxPreviewChars) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("entryId", row.id());
        if (toolId != null) entry.put("toolId", toolId);
        if (callId != null) entry.put("callId", callId);
        if (success != null) entry.put("success", success);
        entry.put("createdAt", row.createdAt().toString());
        if (outputJson != null) {
            int total = outputJson.length();
            entry.put("outputTotalChars", total);
            String preview = total > maxPreviewChars
                    ? outputJson.substring(0, maxPreviewChars) + "...[更多内容请用 transcript.get 取]"
                    : outputJson;
            entry.put("outputPreview", preview);
        }
        return entry;
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
