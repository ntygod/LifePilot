package com.lifepilot.agent.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.conversation.transcript.SessionTranscriptRepository;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * SessionPruningEngine 负责 transcript-first 上下文中的临时裁剪逻辑。
 * <p>当前仅处理历史 {@code tool_result} 片段，不改写 transcript 事实源。</p>
 *
 * @author zsg
 * @since 2026-03-23
 */
public class SessionPruningEngine {

    private static final int DEFAULT_TOOL_RESULT_LIMIT = 4;
    private static final int DEFAULT_TOOL_RESULT_PREVIEW_CHARS = 240;

    private final AgentConfigProperties config;
    private final ObjectMapper objectMapper;

    public record PruningSnapshot(
            String section,
            boolean pruningApplied,
            int tokenCount,
            int selectedCount,
            int totalCount
    ) {
        public static PruningSnapshot empty() {
            return new PruningSnapshot("", false, 0, 0, 0);
        }
    }

    public SessionPruningEngine(AgentConfigProperties config, ObjectMapper objectMapper) {
        this.config = Objects.requireNonNull(config);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    public PruningSnapshot pruneToolResults(
            @Nullable List<SessionTranscriptRepository.SessionTranscriptEntryRow> rows,
            int totalContextTokens
    ) {
        if (rows == null || rows.isEmpty()) {
            return PruningSnapshot.empty();
        }

        List<SessionTranscriptRepository.SessionTranscriptEntryRow> candidates = rows.stream()
                .filter(SessionTranscriptRepository.SessionTranscriptEntryRow::visibleToModel)
                .filter(row -> "tool_result".equals(row.entryType()))
                .toList();
        if (candidates.isEmpty()) {
            return PruningSnapshot.empty();
        }

        TokenBudget budget = TokenBudget.allocateDefault(
                Math.max(0, totalContextTokens),
                config.getContext().getTokenAllocation()
        );
        int toolResultBudget = budget.toolResultBudget();
        if (toolResultBudget <= 0) {
            return new PruningSnapshot("", true, 0, 0, candidates.size());
        }

        List<String> selectedLines = new ArrayList<>();
        int usedTokens = 0;
        int selectedCount = 0;
        boolean pruned = false;
        boolean recentOnlyMode = isRecentOnlyMode();
        int resultLimit = resolveRecentToolResultLimit();

        for (int i = candidates.size() - 1; i >= 0; i--) {
            String line = formatToolResultLine(readPayload(candidates.get(i).payloadJson()));
            if (line.isBlank()) {
                continue;
            }
            int lineTokens = estimateTokens(line);
            boolean overRecentLimit = recentOnlyMode && selectedCount >= resultLimit;
            boolean overTokenBudget = usedTokens + lineTokens > toolResultBudget;
            if (overRecentLimit || overTokenBudget) {
                pruned = true;
                continue;
            }
            selectedLines.add(0, line);
            usedTokens += lineTokens;
            selectedCount++;
        }

        if (selectedLines.isEmpty()) {
            return new PruningSnapshot("", !candidates.isEmpty(), 0, 0, candidates.size());
        }

        StringBuilder section = new StringBuilder("\n最近工具结果:\n");
        selectedLines.forEach(line -> section.append(line).append('\n'));
        return new PruningSnapshot(
                section.toString(),
                pruned || selectedCount < candidates.size(),
                usedTokens,
                selectedCount,
                candidates.size()
        );
    }

    private boolean isRecentOnlyMode() {
        String mode = config.getContext().getPruning().getToolResultMode();
        if (mode == null || mode.isBlank()) {
            return true;
        }
        return !"off".equalsIgnoreCase(mode.trim());
    }

    private int resolveRecentToolResultLimit() {
        int configured = config.getContext().getPruning().getRecentToolResultLimit();
        return configured > 0 ? configured : DEFAULT_TOOL_RESULT_LIMIT;
    }

    private int resolveToolResultPreviewChars() {
        int configured = config.getContext().getPruning().getToolResultPreviewChars();
        return configured > 0 ? configured : DEFAULT_TOOL_RESULT_PREVIEW_CHARS;
    }

    private String formatToolResultLine(Map<String, Object> payload) {
        String toolId = stringValue(payload.get("toolId"));
        String preview = extractPreview(payload.get("outputJson"));
        if ((toolId == null || toolId.isBlank()) && preview.isBlank()) {
            return "";
        }
        boolean success = booleanValue(payload.get("success"));
        StringBuilder line = new StringBuilder("- [");
        line.append(toolId != null ? toolId : "tool");
        line.append("] ").append(success ? "成功" : "失败");
        String callId = stringValue(payload.get("callId"));
        if (callId != null) {
            line.append(" (callId=").append(callId).append(")");
        }
        if (!preview.isBlank()) {
            line.append(": ").append(preview);
        }
        return line.toString();
    }

    private String extractPreview(@Nullable Object outputJsonValue) {
        if (outputJsonValue == null) {
            return "";
        }
        String outputJson = outputJsonValue.toString();
        if (outputJson.isBlank()) {
            return "";
        }
        try {
            Object parsed = objectMapper.readValue(outputJson, Object.class);
            return abbreviate(normalizeWhitespace(summarizeObject(parsed)), resolveToolResultPreviewChars());
        } catch (Exception e) {
            return abbreviate(normalizeWhitespace(outputJson), resolveToolResultPreviewChars());
        }
    }

    private String summarizeObject(@Nullable Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Map<?, ?> map) {
            for (String key : List.of("summary", "content", "message", "result", "text", "output")) {
                Object candidate = map.get(key);
                if (candidate != null) {
                    return summarizeObject(candidate);
                }
            }
            try {
                return objectMapper.writeValueAsString(map);
            } catch (JsonProcessingException e) {
                return map.toString();
            }
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .limit(3)
                    .map(this::summarizeObject)
                    .filter(item -> !item.isBlank())
                    .reduce((left, right) -> left + "; " + right)
                    .orElse("");
        }
        return value.toString();
    }

    Map<String, Object> readPayload(@Nullable String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(payloadJson, new TypeReference<>() { });
            return payload != null ? payload : Map.of();
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    @Nullable
    String stringValue(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private boolean booleanValue(@Nullable Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(value.toString());
    }

    private String normalizeWhitespace(@Nullable String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private String abbreviate(String text, int maxChars) {
        if (text == null || text.isBlank()) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, Math.max(0, maxChars - 3)) + "...";
    }

    private int estimateTokens(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        long cjkChars = text.chars()
                .filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)
                .count();
        long otherChars = text.length() - cjkChars;
        return Math.max(1, (int) (cjkChars + otherChars / 4));
    }
}
