package com.lifepilot.agent.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.conversation.transcript.SessionTranscriptRepository;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * SessionPruningEngine 负责 transcript-first 上下文中的临时裁剪逻辑。
 * <p>当前只裁剪历史 {@code tool_result}，不会改写 transcript 事实源。</p>
 *
 * @author zsg
 * @since 2026-03-23
 */
public class SessionPruningEngine {

    private static final int DEFAULT_TOOL_RESULT_LIMIT = 4;
    private static final int DEFAULT_TOOL_RESULT_PREVIEW_CHARS = 240;
    private static final int SOFT_TRIM_MIN_THRESHOLD = 240;
    private static final int SOFT_TRIM_MAX_THRESHOLD = 1200;
    private static final String HARD_CLEAR_TEMPLATE =
            "结果过长已省略（原始约 %d 字符），如需细节请重新调用工具或查看产物。";

    private final AgentConfigProperties config;
    private final ObjectMapper objectMapper;

    public record PruningSnapshot(
            Set<String> selectedEntryIds,
            boolean pruningApplied,
            int tokenCount,
            int selectedCount,
            int totalCount
    ) {
        public static PruningSnapshot empty() {
            return new PruningSnapshot(Set.of(), false, 0, 0, 0);
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
            return new PruningSnapshot(Set.of(), true, 0, 0, candidates.size());
        }

        List<String> selectedEntryIds = new ArrayList<>();
        int usedTokens = 0;
        int selectedCount = 0;
        boolean pruned = false;
        boolean recentOnlyMode = isRecentOnlyMode();
        int resultLimit = resolveRecentToolResultLimit();

        for (int i = candidates.size() - 1; i >= 0; i--) {
            SessionTranscriptRepository.SessionTranscriptEntryRow candidate = candidates.get(i);
            String preview = formatToolResultPreview(readPayload(candidate.payloadJson()));
            if (preview.isBlank()) {
                continue;
            }
            int lineTokens = estimateTokens(preview);
            boolean overRecentLimit = recentOnlyMode && selectedCount >= resultLimit;
            boolean overTokenBudget = usedTokens + lineTokens > toolResultBudget;
            if (overRecentLimit || overTokenBudget) {
                pruned = true;
                continue;
            }
            selectedEntryIds.add(0, candidate.id());
            usedTokens += lineTokens;
            selectedCount++;
        }

        if (selectedEntryIds.isEmpty()) {
            return new PruningSnapshot(Set.of(), !candidates.isEmpty(), 0, 0, candidates.size());
        }

        return new PruningSnapshot(
                Set.copyOf(new LinkedHashSet<>(selectedEntryIds)),
                pruned || selectedCount < candidates.size(),
                usedTokens,
                selectedCount,
                candidates.size()
        );
    }

    String formatToolResultPreview(Map<String, Object> payload) {
        return extractPreview(payload.get("outputJson"));
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

    private String extractPreview(@Nullable Object outputJsonValue) {
        if (outputJsonValue == null) {
            return "";
        }

        String outputJson = outputJsonValue.toString();
        if (outputJson.isBlank()) {
            return "";
        }

        String normalized;
        try {
            Object parsed = objectMapper.readValue(outputJson, Object.class);
            normalized = normalizeWhitespace(summarizeObject(parsed));
        } catch (Exception e) {
            normalized = normalizeWhitespace(outputJson);
        }
        return trimForPrompt(normalized, resolveToolResultPreviewChars());
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

    private String trimForPrompt(String text, int maxChars) {
        if (text == null || text.isBlank()) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }

        int softTrimThreshold = Math.min(
                Math.max(maxChars * 4, SOFT_TRIM_MIN_THRESHOLD),
                SOFT_TRIM_MAX_THRESHOLD
        );
        if (text.length() > softTrimThreshold) {
            return HARD_CLEAR_TEMPLATE.formatted(text.length());
        }
        return softTrim(text, maxChars);
    }

    private String softTrim(String text, int maxChars) {
        int tailLength = Math.max(12, Math.min(maxChars / 4, 48));
        int headLength = Math.max(24, maxChars - tailLength - 18);
        if (headLength + tailLength >= text.length()) {
            return text;
        }

        int omittedChars = text.length() - headLength - tailLength;
        if (omittedChars <= 0) {
            return text;
        }

        String head = text.substring(0, headLength).stripTrailing();
        String tail = text.substring(text.length() - tailLength).stripLeading();
        return head + " …（中间省略" + omittedChars + "字符）… " + tail;
    }

    private String normalizeWhitespace(@Nullable String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
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
